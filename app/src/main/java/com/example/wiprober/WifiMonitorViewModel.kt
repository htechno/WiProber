package com.example.wiprober

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class WifiMonitorStatus { WAITING, SCANNING, UPDATED, REJECTED, TIMEOUT, UNAVAILABLE }
internal data class WifiNetworkSelection(val network: WifiNetworkSnapshot, val selectedAtMillis: Long)
internal data class WifiMonitorState(
    val running: Boolean = true,
    val pane: MonitorPane = MonitorPane.BSSIDS,
    val journal: MonitorJournal = MonitorJournal(),
    val networks: List<WifiNetworkSnapshot> = emptyList(),
    val connection: WifiConnectionSnapshot? = null,
    val history: List<WifiSignalSample> = emptyList(),
    val sessionStartedAtMillis: Long? = null,
    val selectedNetwork: WifiNetworkSelection? = null,
    val filter: WifiMonitorFilter = WifiMonitorFilter(),
    val splitBands: Boolean = false,
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

    private val journal = WifiEventJournal()
    private var gate: Pair<WifiScanAccessRequirement, Boolean>? = null
    private var observing = false

    fun selectPane(pane: MonitorPane) = mutableState.update { it.copy(pane = pane) }

    fun endObservation(now: Long) {
        if (!observing) return
        observing = false
        journal.event(now, MonitorEvent.Monitoring(false))
        journal.endSegment()
        publishJournal()
    }

    fun toggleRunning(now: Long = state.value.nowMillis) {
        if (state.value.running) endObservation(now)
        mutableState.update { it.copy(running = !it.running) }
    }

    fun record(event: MonitorEvent, at: Long) {
        if (!observing) return
        journal.event(at, event)
        publishJournal()
    }

    fun observeRadio(at: Long, reading: MonitorRadioReading) {
        if (!observing) return
        journal.radio(at, reading.connection, reading.phase)
        publishJournal()
    }

    fun observeProbe(sample: ProbeSample) {
        if (!observing) return
        journal.probe(sample)
        publishJournal()
    }

    private fun publishJournal() {
        if (mutableState.value.journal !== journal.snapshot) mutableState.update { it.copy(journal = journal.snapshot) }
    }
    fun setFilter(filter: WifiMonitorFilter) = mutableState.update { it.copy(filter = filter) }
    fun setSplitBands(split: Boolean) = mutableState.update { it.copy(splitBands = split) }
    fun toggleChannels() = mutableState.update { it.copy(channelsExpanded = !it.channelsExpanded) }
    fun selectNetwork(network: WifiNetworkSnapshot, now: Long) = mutableState.update {
        it.copy(selectedNetwork = WifiNetworkSelection(network, now))
    }
    fun closeDetails() = mutableState.update { it.copy(selectedNetwork = null) }
    fun tick(now: Long, connection: WifiConnectionSnapshot?, access: WifiScanAccessRequirement, throttled: Boolean,
             phase: String? = null) {
        val sampling = state.value.running && !throttled && access == WifiScanAccessRequirement.READY
        val currentGate = access to throttled
        if (state.value.running && gate != currentGate) {
            gate = currentGate
            journal.event(now, MonitorEvent.Access(access, throttled))
        }
        if (sampling) {
            if (!observing) journal.event(now, MonitorEvent.Monitoring(true))
            observing = true
            journal.radio(now, connection, phase)
        } else endObservation(now)
        mutableState.update { state ->
            val sample = WifiSignalSample(now, connection?.bssid, connection?.rssi)
            val history = if (sampling) (state.history + sample)
                .filter { it.elapsedMillis >= now - 120_000L }.takeLast(121) else state.history
            state.copy(journal = journal.snapshot, nowMillis = now, connection = connection, history = history, access = access,
                sessionStartedAtMillis = state.sessionStartedAtMillis ?: now.takeIf { sampling },
                throttled = throttled)
        }
    }
    fun scanStarted(now: Long) {
        record(MonitorEvent.Scan(WifiMonitorStatus.SCANNING), now)
        mutableState.update { it.copy(status = WifiMonitorStatus.SCANNING,
            nextScanMillis = now + WifiMonitorCadence.INTERVAL_MILLIS) }
    }
    fun scanFinished(networks: List<WifiNetworkSnapshot>?, status: WifiMonitorStatus, started: Long, now: Long) {
        record(MonitorEvent.Scan(status, networks?.size ?: 0), now)
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
