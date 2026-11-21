package com.example.wiprober

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.wiprober.databinding.ItemWifiNetworkBinding
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

internal class WifiNetworkAdapter(private val onClick: (WifiNetworkSnapshot) -> Unit) :
    ListAdapter<WifiTableRow, RecyclerView.ViewHolder>(Diff) {
    private class SectionHolder(val label: TextView) : RecyclerView.ViewHolder(label)

    private class NetworkHolder(val binding: ItemWifiNetworkBinding) : RecyclerView.ViewHolder(binding.root) {
        private val baseTypefaces = with(binding) {
            listOf(bssid, band, channel, width, rssi, security, ssid).associateWith { it.typeface }
        }
        private val highlight = MaterialColors.getColor(itemView, MaterialR.attr.colorPrimaryContainer)
        private val onHighlight = MaterialColors.getColor(itemView, MaterialR.attr.colorOnPrimaryContainer)
        private val normalText = MaterialColors.getColor(itemView, MaterialR.attr.colorOnSurface)
        private val signalText = MaterialColors.getColor(itemView, MaterialR.attr.colorPrimary)

        fun showConnection(connected: Boolean) {
            itemView.isActivated = connected
            itemView.stateDescription = if (connected) itemView.context.getString(R.string.monitor_current_connection) else null
            itemView.setBackgroundColor(if (connected) highlight else Color.TRANSPARENT)
            for ((cell, baseTypeface) in baseTypefaces) {
                val isSignal = cell === binding.rssi
                cell.setTypeface(baseTypeface, if (connected || isSignal) Typeface.BOLD else Typeface.NORMAL)
                cell.setTextColor(when {
                    connected -> onHighlight
                    isSignal -> signalText
                    else -> normalText
                })
            }
        }
    }

    private var connectedBssid: String? = null

    fun setConnectedBssid(bssid: String?) {
        if (connectedBssid == bssid) return
        val previous = connectedBssid
        connectedBssid = bssid
        currentList.forEachIndexed { index, row ->
            if (row is WifiTableRow.Network && (row.snapshot.bssid == previous || row.snapshot.bssid == bssid)) {
                notifyItemChanged(index)
            }
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is WifiTableRow.Network -> R.layout.item_wifi_network
        is WifiTableRow.Section -> R.layout.item_wifi_band_header
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            R.layout.item_wifi_band_header -> SectionHolder(inflater.inflate(viewType, parent, false) as TextView)
            else -> NetworkHolder(ItemWifiNetworkBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is WifiTableRow.Section -> {
                val label = (holder as SectionHolder).label
                val band = WifiMonitorText(label.context).band(row.band)
                label.text = label.context.getString(R.string.monitor_band_section, band, row.count)
            }
            is WifiTableRow.Network -> bindNetwork(holder as NetworkHolder, row)
        }
    }

    private fun bindNetwork(holder: NetworkHolder, row: WifiTableRow.Network) {
        val b = holder.binding
        val network = row.snapshot
        val text = WifiMonitorText(b.root.context)
        b.bssid.text = network.vendor?.let {
            b.root.context.getString(R.string.monitor_vendor_bssid, it.shortName, network.bssid.takeLast(8))
        } ?: network.bssid
        b.bssid.contentDescription = network.vendor?.let {
            b.root.context.getString(R.string.monitor_vendor_bssid_accessibility, it.fullName, network.bssid)
        }
        b.band.isVisible = row.showBand
        b.band.text = b.root.context.getString(when (network.band) {
            WifiBand.GHZ_2_4 -> R.string.monitor_band_short_24; WifiBand.GHZ_5 -> R.string.monitor_band_short_5
            WifiBand.GHZ_6 -> R.string.monitor_band_short_6
            WifiBand.UNKNOWN -> R.string.monitor_not_available
        })
        b.channel.text = network.channel?.toString() ?: text.unknown()
        b.width.text = when {
            network.widthCode == 4 -> b.root.context.getString(R.string.monitor_width_short_80_80)
            else -> WifiRadioModel.widthMhz(network.widthCode)?.toString() ?: text.unknown()
        }
        b.rssi.text = network.rssi.toString()
        val security = network.security
        b.security.text = text.security(security, compact = true)
        b.security.contentDescription = text.security(security)
        b.ssid.text = text.ssid(network.ssid)
        holder.showConnection(network.bssid == connectedBssid)
        b.root.isFocusable = true
        b.root.setOnClickListener { onClick(network) }
    }
    private object Diff : DiffUtil.ItemCallback<WifiTableRow>() {
        override fun areItemsTheSame(old: WifiTableRow, new: WifiTableRow) = when (old) {
            is WifiTableRow.Network -> new is WifiTableRow.Network && old.snapshot.bssid == new.snapshot.bssid
            is WifiTableRow.Section -> new is WifiTableRow.Section && old.band == new.band
        }
        override fun areContentsTheSame(old: WifiTableRow, new: WifiTableRow) = old == new
    }
}
