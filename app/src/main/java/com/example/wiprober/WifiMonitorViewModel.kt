package com.example.wiprober

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class WifiMonitorStatus { WAITING, SCANNING, UPDATED, REJECTED, TIMEOUT, UNAVAILABLE }
internal data class WifiMonitorState(
    val running: Boolean = true,
    val networks: List<WifiNetworkSnapshot> = emptyList(),
    val connection: WifiConnectionSnapshot? = null,
    val history: List<WifiSignalSample> = emptyList(),
    val filter: WifiMonitorFilter = WifiMonitorFilter(),
    val channelsExpanded: Boolean = false,
    val lastScanMillis: Long? = null,
    val nowMillis: Long = 0,
    val nextScanMillis: Long = 0,
    val throttled: Boolean = true,
    val status: WifiMonitorStatus = WifiMonitorStatus.WAITING,
    val access: WifiScanAccessRequirement = WifiScanAccessRequirement.READY
) {
    val canScan get() = running && !throttled && access == WifiScanAccessRequirement.READY
}

internal class WifiMonitorViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(WifiMonitorState())
    val state = mutableState.asStateFlow()

    fun toggleRunning() = mutableState.update { it.copy(running = !it.running) }
    fun setFilter(filter: WifiMonitorFilter) = mutableState.update { it.copy(filter = filter) }
    fun toggleChannels() = mutableState.update { it.copy(channelsExpanded = !it.channelsExpanded) }
    fun tick(now: Long, connection: WifiConnectionSnapshot?, access: WifiScanAccessRequirement, throttled: Boolean) {
        mutableState.update { state ->
            val sample = WifiSignalSample(now, connection?.bssid, connection?.rssi)
            val history = if (state.running && !throttled && access == WifiScanAccessRequirement.READY) (state.history + sample)
                .filter { it.elapsedMillis >= now - 120_000L }.takeLast(121) else state.history
            state.copy(nowMillis = now, connection = connection, history = history, access = access,
                throttled = throttled)
        }
    }
    fun scanStarted(now: Long) = mutableState.update {
        it.copy(status = WifiMonitorStatus.SCANNING,
            nextScanMillis = now + WifiMonitorCadence.INTERVAL_MILLIS)
    }
    fun scanFinished(networks: List<WifiNetworkSnapshot>?, status: WifiMonitorStatus, started: Long, now: Long) {
        mutableState.update {
            it.copy(networks = networks ?: it.networks,
                lastScanMillis = if (networks != null) now else it.lastScanMillis,
                status = status,
                nextScanMillis = WifiMonitorCadence.nextDeadline(started, now, networks == null))
        }
    }
    fun scanCancelled() = mutableState.update {
        if (it.status == WifiMonitorStatus.SCANNING) it.copy(status = WifiMonitorStatus.WAITING) else it
    }
}
