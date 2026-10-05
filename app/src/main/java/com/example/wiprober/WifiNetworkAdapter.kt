package com.example.wiprober

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.wiprober.databinding.ItemWifiNetworkBinding
import com.google.android.material.color.MaterialColors

internal class WifiNetworkAdapter(private val onClick: (WifiNetworkSnapshot) -> Unit) :
    ListAdapter<WifiNetworkSnapshot, WifiNetworkAdapter.Holder>(Diff) {
    class Holder(val binding: ItemWifiNetworkBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemWifiNetworkBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val network = getItem(position)
        val b = holder.binding
        val text = WifiMonitorText(b.root.context)
        b.bssid.text = network.bssid
        b.band.text = b.root.context.getString(when (network.band) {
            WifiBand.GHZ_2_4 -> R.string.monitor_band_short_24; WifiBand.GHZ_5 -> R.string.monitor_band_short_5
            WifiBand.GHZ_6 -> R.string.monitor_band_short_6; WifiBand.GHZ_60 -> R.string.monitor_band_short_60
            WifiBand.UNKNOWN -> R.string.monitor_not_available
        })
        b.channel.text = network.channel?.toString() ?: text.unknown()
        b.width.text = when {
            network.band == WifiBand.GHZ_60 -> text.unknown()
            network.widthCode == 4 -> b.root.context.getString(R.string.monitor_width_short_80_80)
            else -> WifiRadioModel.widthMhz(network.widthCode)?.toString() ?: text.unknown()
        }
        b.rssi.text = network.rssi.toString()
        b.rssi.setTypeface(null, Typeface.BOLD)
        b.rssi.setTextColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorPrimary))
        b.security.text = text.security(network)
        b.ssid.text = text.ssid(network.ssid)
        b.root.isFocusable = true
        b.root.setOnClickListener { onClick(network) }
    }
    private object Diff : DiffUtil.ItemCallback<WifiNetworkSnapshot>() {
        override fun areItemsTheSame(old: WifiNetworkSnapshot, new: WifiNetworkSnapshot) = old.bssid == new.bssid
        override fun areContentsTheSame(old: WifiNetworkSnapshot, new: WifiNetworkSnapshot) = old == new
    }
}
