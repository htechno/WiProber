package com.example.wiprober

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** One foreground observation segment. All callbacks and path selection are main-thread confined. */
internal class WifiMonitorTelemetry(private val context: Context, private val source: WifiMonitorSource) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private data class Gateway(val network: Network, val address: InetAddress, val iface: String?)
    private class Path {
        var ip: MonitorEvent.Ip? = null
        var validation: MonitorEvent.Validation? = null
        var gateway: Gateway? = null
    }

    @Suppress("DEPRECATION")
    suspend fun observe(model: WifiMonitorViewModel) = coroutineScope {
        val paths = mutableMapOf<Network, Path>()
        val refresh = Channel<Unit>(Channel.CONFLATED)
        var active = true
        var networkState: String? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!active) return
                paths[network] = Path()
                model.record(MonitorEvent.Path(network.networkHandle, true), SystemClock.elapsedRealtime())
                refresh.trySend(Unit)
            }
            override fun onLost(network: Network) {
                if (!active || paths.remove(network) == null) return
                model.record(MonitorEvent.Path(network.networkHandle, false), SystemClock.elapsedRealtime())
                refresh.trySend(Unit)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val path = paths[network] ?: return
                if (!active) return
                val status = MonitorEvent.Validation(network.networkHandle,
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
                if (path.validation != status) model.record(status, SystemClock.elapsedRealtime())
                path.validation = status
                refresh.trySend(Unit)
            }
            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
                val path = paths[network] ?: return
                if (!active) return
                val gateways = props.routes.filter { it.isDefaultRoute && it.hasGateway() }.mapNotNull { it.gateway }
                val gateway = gateways.firstOrNull { it is Inet4Address } ?: gateways.firstOrNull()
                path.gateway = gateway?.let { Gateway(network, it, props.interfaceName) }
                val ip = MonitorEvent.Ip(network.networkHandle, props.linkAddresses.map { it.toString() },
                    gateway?.hostAddress, props.dnsServers.mapNotNull { it.hostAddress })
                if (ip != path.ip) model.record(ip, SystemClock.elapsedRealtime())
                path.ip = ip
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!active) return
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    model.record(MonitorEvent.SystemScan(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)),
                        SystemClock.elapsedRealtime())
                    return
                }
                if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
                val state = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)?.detailedState?.name
                if (state != null && state != networkState) model.record(MonitorEvent.NetworkState(state), SystemClock.elapsedRealtime())
                networkState = state
                refresh.trySend(Unit)
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        var networkRegistered = false
        var receiverRegistered = false
        try {
            networkRegistered = try {
                cm.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
                true
            } catch (_: RuntimeException) {
                // Other monitor sources still work if Android declines the callback.
                false
            }
            // NETWORK_STATE_CHANGED_ACTION is a protected framework broadcast.
            val broadcasts = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION).apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, broadcasts,
                ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            launch {
                for (ignored in refresh) {
                    val at = SystemClock.elapsedRealtime()
                    val reading = withContext(Dispatchers.IO) { source.radio() }
                    model.observeRadio(at, reading)
                }
            }
            var sequence = 0
            while (isActive) {
                val start = SystemClock.elapsedRealtime()
                // Multiple visible Wi-Fi interfaces are ambiguous; never silently probe a different STA.
                val target = paths.values.singleOrNull()?.gateway
                val sample = when {
                    !networkRegistered || paths.size > 1 -> ProbeSample(start, null, ProbeResult.UNAVAILABLE)
                    target == null -> ProbeSample(start, null, ProbeResult.NO_ROUTE)
                    else -> withContext(Dispatchers.IO) {
                        val result = echo(target, ++sequence)
                        ProbeSample(SystemClock.elapsedRealtime(), target.address.hostAddress, result)
                    }
                }
                model.observeProbe(sample)
                delay((start + WifiEventJournal.PROBE_INTERVAL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            }
        } finally {
            active = false
            refresh.close()
            if (receiverRegistered) context.unregisterReceiver(receiver)
            if (networkRegistered) cm.unregisterNetworkCallback(callback)
        }
    }

    /** Linux ICMP datagram (ping) socket. Android supplies the identifier and checksum. */
    private fun echo(target: Gateway, seq: Int): ProbeResult {
        val ipv4 = target.address is Inet4Address
        return try {
            val fd = Os.socket(if (ipv4) OsConstants.AF_INET else OsConstants.AF_INET6, OsConstants.SOCK_DGRAM,
                if (ipv4) OsConstants.IPPROTO_ICMP else OsConstants.IPPROTO_ICMPV6)
            try {
                target.network.bindSocket(fd)
                val address = target.address
                val peer = if (address is Inet6Address && address.isLinkLocalAddress && address.scopeId == 0) {
                    val iface = target.iface?.let(NetworkInterface::getByName) ?: return ProbeResult.UNAVAILABLE
                    Inet6Address.getByAddress(null, address.address, iface)
                } else address
                Os.connect(fd, peer, 0)
                val timeout = StructTimeval.fromMillis(200)
                Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, timeout)
                Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, timeout)
                val packet = ByteArray(8)
                packet[0] = (if (ipv4) OsConstants.ICMP_ECHO else OsConstants.ICMP6_ECHO_REQUEST).toByte()
                packet[6] = (seq ushr 8).toByte()
                packet[7] = seq.toByte()
                Os.write(fd, packet, 0, packet.size)
                val reply = ByteArray(64)
                val size = Os.read(fd, reply, 0, reply.size)
                val replyType = if (ipv4) OsConstants.ICMP_ECHOREPLY else OsConstants.ICMP6_ECHO_REPLY
                if (size >= 8 && reply[0] == replyType.toByte() && reply[6] == packet[6] && reply[7] == packet[7])
                    ProbeResult.REPLY else ProbeResult.UNAVAILABLE
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            when (e.errno) {
                OsConstants.EAGAIN, OsConstants.ETIMEDOUT -> ProbeResult.TIMEOUT
                OsConstants.ENETUNREACH, OsConstants.EHOSTUNREACH -> ProbeResult.NO_ROUTE
                else -> ProbeResult.UNAVAILABLE
            }
        } catch (_: InterruptedIOException) {
            ProbeResult.TIMEOUT
        } catch (_: IOException) {
            ProbeResult.UNAVAILABLE
        } catch (_: SecurityException) {
            ProbeResult.UNAVAILABLE
        }
    }
}
