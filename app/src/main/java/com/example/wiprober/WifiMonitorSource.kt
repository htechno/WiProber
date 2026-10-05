package com.example.wiprober

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Android reads and defensive snapshots; called on Dispatchers.IO, never by a drawing view. */
internal class WifiMonitorSource(private val context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    fun access(): WifiScanAccessRequirement = WifiScanAccessPolicy.requirement(WifiScanAccessSnapshot(
        wifiEnabled = wifi.isWifiEnabled,
        permissionsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED,
        locationEnabled = context.getSystemService(LocationManager::class.java).isLocationEnabled
    ))
    fun throttled(): Boolean = runCatching { wifi.isScanThrottleEnabled }.getOrDefault(true)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun connection(): WifiConnectionSnapshot? {
        if (access() != WifiScanAccessRequirement.READY) return null
        // Deliberately sample the primary connection, including when cellular/VPN is the default.
        // getConnectionInfo remains available on API 30–36; callbacks alone do not deliver 1 Hz RSSI.
        return runCatching {
            val info = wifi.connectionInfo ?: return null
            val bssid = WifiRadioModel.normalizeBssid(info.bssid) ?: return null
            if (info.supplicantState != SupplicantState.COMPLETED || info.rssi !in -126..0) return null
            WifiConnectionSnapshot(info.ssid.orEmpty().removeSurrounding("\""), bssid, info.rssi,
                info.frequency, info.wifiStandard, info.linkSpeed, info.txLinkSpeedMbps, info.rxLinkSpeedMbps)
        }.getOrNull()
    }

    companion object {
        @Suppress("DEPRECATION")
        fun snapshot(result: ScanResult): WifiNetworkSnapshot? {
            val bssid = WifiRadioModel.normalizeBssid(result.BSSID) ?: return null
            var remaining = 65_536
            var truncated = false
            val allIes = result.informationElements.orEmpty()
            val elements = allIes.take(512).map { ie ->
                val buffer = ie.bytes.duplicate()
                val size = minOf(buffer.remaining(), remaining, 4096)
                if (size < buffer.remaining()) truncated = true
                remaining -= size
                WifiInformationElement(ie.id, ie.idExt, List(size) { buffer.get().toInt() and 255 })
            }
            return WifiNetworkSnapshot(
                bssid = bssid, ssid = result.SSID.orEmpty(), rssi = result.level,
                frequencyMhz = result.frequency, widthCode = result.channelWidth,
                center0Mhz = result.centerFreq0, center1Mhz = result.centerFreq1,
                standard = result.wifiStandard, capabilities = result.capabilities.orEmpty(),
                seenAtMicros = result.timestamp, passpoint = result.isPasspointNetwork,
                responder80211mc = result.is80211mcResponder,
                responder80211az = if (Build.VERSION.SDK_INT >= 35) result.is80211azNtbResponder else null,
                twtResponder = if (Build.VERSION.SDK_INT >= 35) result.isTwtResponder else null,
                secureHeLtf = if (Build.VERSION.SDK_INT >= 36) result.isSecureHeLtfSupported else null,
                rangingFrameProtection = if (Build.VERSION.SDK_INT >= 36) result.isRangingFrameProtectionRequired else null,
                ssidBytes = if (Build.VERSION.SDK_INT >= 33) result.wifiSsid?.bytes?.map { it.toInt() and 255 }.orEmpty() else emptyList(),
                venue = result.venueName?.toString().orEmpty(), operator = result.operatorFriendlyName?.toString().orEmpty(),
                mldAddress = if (Build.VERSION.SDK_INT >= 33) result.apMldMacAddress?.toString() else null,
                mloLinkId = if (Build.VERSION.SDK_INT >= 33) result.apMloLinkId.takeIf { it >= 0 } else null,
                affiliatedLinks = if (Build.VERSION.SDK_INT >= 33) result.affiliatedMloLinks.map { it.toString() } else emptyList(),
                securityTypes = if (Build.VERSION.SDK_INT >= 33) result.securityTypes.toList() else emptyList(),
                informationElements = elements,
                informationElementsTruncated = truncated || allIes.size > elements.size
            )
        }
    }
}
