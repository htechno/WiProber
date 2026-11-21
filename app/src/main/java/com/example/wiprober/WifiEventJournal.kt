package com.example.wiprober

internal enum class MonitorPane { BSSIDS, EVENTS, HANDOVERS }
internal enum class ProbeResult { REPLY, TIMEOUT, NO_ROUTE, UNAVAILABLE }
internal data class ProbeSample(val at: Long, val target: String?, val result: ProbeResult)
internal data class ProbeStatus(val target: String?, val result: ProbeResult)
internal sealed interface ReplyGap {
    data object Pending : ReplyGap
    data object Unknown : ReplyGap
    data object NoMiss : ReplyGap
    data class Measured(val millis: Long, val misses: Int) : ReplyGap
}

internal enum class ChangeKind { HANDOVER, NETWORK_SWITCH, MLO_LINK }
internal sealed interface MonitorLogRow { val at: Long }
internal data class ApChange(
    val number: Long, val oldSeen: Long, override val at: Long,
    val from: WifiConnectionSnapshot, val to: WifiConnectionSnapshot,
    val kind: ChangeKind, val gap: ReplyGap
) : MonitorLogRow

internal sealed interface MonitorEvent {
    data class Scan(val status: WifiMonitorStatus, val count: Int = 0) : MonitorEvent
    data class SystemScan(val updated: Boolean) : MonitorEvent
    data class Connected(val radio: WifiConnectionSnapshot) : MonitorEvent
    data object Disconnected : MonitorEvent
    data class Change(val change: ApChange) : MonitorEvent
    data class Supplicant(val state: String) : MonitorEvent
    data class NetworkState(val state: String) : MonitorEvent
    data class Path(val id: Long, val available: Boolean) : MonitorEvent
    data class Ip(val id: Long, val addresses: List<String>, val gateway: String?, val dns: List<String>) : MonitorEvent
    data class Validation(val id: Long, val validated: Boolean, val captive: Boolean) : MonitorEvent
    data class Access(val access: WifiScanAccessRequirement, val throttled: Boolean) : MonitorEvent
    data class Monitoring(val active: Boolean) : MonitorEvent
}
internal data class TimedMonitorEvent(override val at: Long, val event: MonitorEvent) : MonitorLogRow
internal data class MonitorJournal(
    val startedAt: Long? = null,
    val events: List<TimedMonitorEvent> = emptyList(),
    val changes: List<ApChange> = emptyList(),
    val probe: ProbeStatus? = null
)

/** Confined to the monitor's main-thread callbacks. Only immutable snapshots escape. */
internal class WifiEventJournal {
    var snapshot = MonitorJournal()
        private set
    private var lastRadio: WifiConnectionSnapshot? = null
    private var lastSeen = 0L
    private var lastReading = -1L
    private var disconnected = false
    private var supplicant: String? = null
    private var sequence = 0L
    private val probes = ArrayDeque<ProbeSample>()

    fun event(at: Long, event: MonitorEvent) {
        val row = TimedMonitorEvent(at, event)
        // An IO sample can arrive after a newer framework callback.
        val rows = snapshot.events.toMutableList()
        val insertion = rows.indexOfLast { it.at <= at } + 1
        rows.add(insertion, row)
        snapshot = snapshot.copy(startedAt = snapshot.startedAt ?: at, events = rows.takeLast(400))
    }

    fun endSegment() {
        lastRadio = null
        lastReading = -1
        disconnected = false
        supplicant = null
        probes.clear()
        finishPending(ReplyGap.Unknown)
        snapshot = snapshot.copy(probe = null)
    }

    fun radio(at: Long, radio: WifiConnectionSnapshot?, phase: String?) {
        if (at < lastReading) return
        // A long *unobserved* interval breaks continuity. A continuously observed disconnect
        // can last much longer than a typical roam and should still be reported on reconnection.
        val continuous = lastReading >= 0 && at - lastReading <= MAX_OBSERVATION_GAP_MS
        lastReading = at
        if (!continuous) {
            lastRadio = null
            disconnected = false
        }
        if (phase != null && phase != supplicant) {
            supplicant = phase
            event(at, MonitorEvent.Supplicant(phase))
        }
        if (radio == null) {
            if (lastRadio != null && !disconnected && phase in setOf("DISCONNECTED", "INACTIVE", "INTERFACE_DISABLED", "ASSOCIATING", "AUTHENTICATING")) {
                disconnected = true
                event(at, MonitorEvent.Disconnected)
            }
            return
        }
        val old = lastRadio
        if (old == null) {
            event(at, MonitorEvent.Connected(radio))
        } else if (old.bssid != radio.bssid) {
            val kind = when {
                old.ssid.isEmpty() || old.ssid != radio.ssid -> ChangeKind.NETWORK_SWITCH
                old.mldAddress != null && old.mldAddress == radio.mldAddress -> ChangeKind.MLO_LINK
                else -> ChangeKind.HANDOVER
            }
            finishPending(ReplyGap.Unknown)
            val change = ApChange(++sequence, lastSeen, at, old, radio, kind,
                if (kind == ChangeKind.HANDOVER) ReplyGap.Pending else ReplyGap.Unknown)
            snapshot = snapshot.copy(changes = (snapshot.changes + change).takeLast(160))
            event(at, MonitorEvent.Change(change))
        } else if (disconnected) {
            event(at, MonitorEvent.Connected(radio))
        }
        lastRadio = radio
        lastSeen = at
        disconnected = false
    }

    fun probe(sample: ProbeSample) {
        probes.addLast(sample)
        while (probes.size > 256 || probes.first().at < sample.at - 60_000) probes.removeFirst()
        val status = ProbeStatus(sample.target, sample.result)
        if (snapshot.probe != status) snapshot = snapshot.copy(probe = status)
        val change = snapshot.changes.lastOrNull() ?: return
        if (change.gap != ReplyGap.Pending || sample.at < change.at + PROBE_INTERVAL_MS) return
        val before = probes.indexOfLast { it.at <= change.oldSeen && it.result == ProbeResult.REPLY }
        if (before < 0) {
            finishPending(ReplyGap.Unknown)
            return
        }
        // Bracket the observation with successful replies from the same gateway. An interval
        // without a scheduled measurement (sleep, overload) is not evidence of packet loss.
        val window = probes.drop(before)
        val after = window.indexOfFirst { it.at >= change.at + PROBE_INTERVAL_MS && it.result == ProbeResult.REPLY }
        val samples = if (after < 0) window else window.take(after + 1)
        if (samples.any { it.result == ProbeResult.UNAVAILABLE || (it.target != null && it.target != samples.first().target) } ||
            samples.zipWithNext().any { (a, b) -> b.at - a.at > 3 * PROBE_INTERVAL_MS }) {
            finishPending(ReplyGap.Unknown)
        } else if (after >= 0) {
            var previousReply = samples.first().at
            var missed = 0
            var longest = 0L
            var totalMissed = 0
            for (point in samples.drop(1)) {
                if (point.result == ProbeResult.REPLY) {
                    if (missed > 0 && point.at >= change.oldSeen && previousReply <= change.at + PROBE_INTERVAL_MS) {
                        longest = maxOf(longest, point.at - previousReply)
                        totalMissed += missed
                    }
                    previousReply = point.at
                    missed = 0
                } else missed++
            }
            finishPending(if (totalMissed == 0) ReplyGap.NoMiss else ReplyGap.Measured(longest, totalMissed))
        }
    }

    private fun finishPending(gap: ReplyGap) {
        val last = snapshot.changes.lastOrNull() ?: return
        if (last.gap == ReplyGap.Pending) {
            snapshot = snapshot.copy(changes = snapshot.changes.dropLast(1) + last.copy(gap = gap))
        }
    }

    companion object {
        const val PROBE_INTERVAL_MS = 250L
        private const val MAX_OBSERVATION_GAP_MS = 5_000L
    }
}
