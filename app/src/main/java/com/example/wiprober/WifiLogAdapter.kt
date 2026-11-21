package com.example.wiprober

import android.content.Context
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

internal class WifiLogAdapter : ListAdapter<MonitorLogRow, WifiLogAdapter.Holder>(Diff) {
    var origin = 0L
    class Holder(val line: TextView) : RecyclerView.ViewHolder(line) {
        val labels = WifiLogText(line.context)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val line = TextView(parent.context)
        line.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        line.textSize = 12f
        val pad = (3 * line.resources.displayMetrics.density).toInt()
        line.setPadding(0, pad, 0, pad)
        return Holder(line)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.line.text = holder.labels.row(row, origin)
    }
    private object Diff : DiffUtil.ItemCallback<MonitorLogRow>() {
        override fun areItemsTheSame(a: MonitorLogRow, b: MonitorLogRow) =
            if (a is ApChange && b is ApChange) a.number == b.number else a === b
        override fun areContentsTheSame(a: MonitorLogRow, b: MonitorLogRow) = a == b
    }
}

internal class WifiLogText(private val context: Context) {
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private val dash get() = s(R.string.monitor_not_available)
    fun probe(status: ProbeStatus?): String {
        val state = when (status?.result) {
            ProbeResult.REPLY -> R.string.monitor_echo_reply
            ProbeResult.TIMEOUT -> R.string.monitor_echo_timeout
            ProbeResult.NO_ROUTE -> R.string.monitor_echo_no_route
            else -> R.string.monitor_echo_unknown
        }
        return s(R.string.monitor_echo_status, status?.target ?: dash, s(state), WifiEventJournal.PROBE_INTERVAL_MS)
    }
    fun row(row: MonitorLogRow, origin: Long): String {
        val body = when (row) {
            is TimedMonitorEvent -> event(row.event)
            is ApChange -> change(row) + "\n" + gap(row)
        }
        return s(R.string.monitor_log_time, (row.at - origin) / 1000.0, body)
    }
    private fun ap(radio: WifiConnectionSnapshot) = s(R.string.monitor_log_ap, radio.bssid,
        WifiRadioModel.channel(radio.frequencyMhz)?.toString() ?: dash, radio.rssi)
    private fun change(value: ApChange): String {
        val type = when (value.kind) {
            ChangeKind.HANDOVER -> R.string.monitor_handover
            ChangeKind.NETWORK_SWITCH -> R.string.monitor_network_switch
            ChangeKind.MLO_LINK -> R.string.monitor_mlo_change
        }
        val names = if (value.from.ssid == value.to.ssid) value.from.ssid
            else s(R.string.monitor_log_names, value.from.ssid, value.to.ssid)
        return s(R.string.monitor_log_change, value.number, s(type), names, ap(value.from), ap(value.to))
    }
    private fun gap(change: ApChange): String = when (val result = change.gap) {
        ReplyGap.Pending -> s(R.string.monitor_gap_pending)
        ReplyGap.Unknown -> s(R.string.monitor_gap_unknown)
        ReplyGap.NoMiss -> s(R.string.monitor_gap_no_miss)
        is ReplyGap.Measured -> s(R.string.monitor_gap_measured, result.millis, result.misses)
    }
    private fun event(event: MonitorEvent): String = when (event) {
        is MonitorEvent.Connected -> s(R.string.monitor_log_connected, event.radio.ssid, ap(event.radio))
        MonitorEvent.Disconnected -> s(R.string.monitor_log_disconnected)
        is MonitorEvent.Change -> change(event.change)
        is MonitorEvent.SystemScan -> s(if (event.updated) R.string.monitor_log_system_scan else R.string.monitor_log_system_scan_stale)
        is MonitorEvent.Supplicant -> s(R.string.monitor_log_phase, event.state)
        is MonitorEvent.NetworkState -> s(R.string.monitor_log_state, event.state)
        is MonitorEvent.Path -> s(if (event.available) R.string.monitor_log_available else R.string.monitor_log_lost, event.id)
        is MonitorEvent.Ip -> s(R.string.monitor_log_ip, event.id, event.addresses.joinToString().ifEmpty { dash },
            event.gateway ?: dash, event.dns.joinToString().ifEmpty { dash })
        is MonitorEvent.Validation -> s(R.string.monitor_log_validation, event.id, s(when {
            event.captive -> R.string.monitor_log_captive
            event.validated -> R.string.monitor_log_validated
            else -> R.string.monitor_log_unvalidated
        }))
        is MonitorEvent.Monitoring -> s(if (event.active) R.string.monitor_log_started else R.string.monitor_log_stopped)
        is MonitorEvent.Access -> s(when {
            event.throttled -> R.string.monitor_throttled
            event.access == WifiScanAccessRequirement.GRANT_PERMISSIONS -> R.string.monitor_permission_required
            event.access == WifiScanAccessRequirement.ENABLE_WIFI -> R.string.monitor_wifi_disabled
            event.access == WifiScanAccessRequirement.ENABLE_LOCATION -> R.string.monitor_location_disabled
            else -> R.string.monitor_log_ready
        })
        is MonitorEvent.Scan -> when (event.status) {
            WifiMonitorStatus.UPDATED -> s(R.string.monitor_log_scan_done, event.count)
            WifiMonitorStatus.REJECTED -> s(R.string.monitor_log_scan_rejected)
            WifiMonitorStatus.TIMEOUT -> s(R.string.monitor_log_scan_timeout)
            WifiMonitorStatus.SCANNING -> s(R.string.monitor_log_scan_start)
            else -> s(R.string.monitor_log_scan_unavailable)
        }
    }
}
