package com.example.wiprober

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

internal object WifiMonitorDialogs {
    fun filter(context: Context, current: WifiMonitorFilter, onApply: (WifiMonitorFilter) -> Unit): AlertDialog {
        val density = context.resources.displayMetrics.density
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        }
        fun input(label: Int, value: String, type: Int): TextInputEditText {
            val wrapper = TextInputLayout(context).apply { hint = context.getString(label) }
            val edit = TextInputEditText(context).apply { inputType = type; setSingleLine(); setText(value) }
            wrapper.addView(edit); column.addView(wrapper)
            return edit
        }
        val ssid = input(R.string.monitor_filter_ssid, current.ssid, InputType.TYPE_CLASS_TEXT)
        val bands = listOf(null) + WifiBand.entries
        val text = WifiMonitorText(context)
        column.addView(TextView(context).apply { setText(R.string.monitor_band) })
        val band = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                bands.map { it?.let(text::band) ?: context.getString(R.string.monitor_all_bands) })
            setSelection(bands.indexOf(current.band))
            contentDescription = context.getString(R.string.monitor_band)
            minimumHeight = (48 * density).toInt()
        }
        column.addView(band)
        val channel = input(R.string.monitor_filter_channel, current.channel?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        val rssi = input(R.string.monitor_filter_rssi, current.minimumRssi?.toString().orEmpty(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.monitor_filter)
            .setView(ScrollView(context).apply { addView(column) })
            .setPositiveButton(R.string.monitor_apply, null)
            .setNeutralButton(R.string.monitor_reset) { _, _ -> onApply(WifiMonitorFilter()) }
            .setNegativeButton(R.string.action_cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val channelValue = channel.text.toString().trim()
                val rssiValue = rssi.text.toString().trim()
                val parsedChannel = channelValue.toIntOrNull()
                val parsedRssi = rssiValue.toIntOrNull()
                when {
                    channelValue.isNotEmpty() && (parsedChannel == null || parsedChannel !in 1..233) ->
                        channel.error = context.getString(R.string.monitor_filter_invalid)
                    rssiValue.isNotEmpty() && (parsedRssi == null || parsedRssi !in -126..0) ->
                        rssi.error = context.getString(R.string.monitor_filter_invalid)
                    else -> {
                        onApply(WifiMonitorFilter(ssid.text.toString(), bands[band.selectedItemPosition], parsedChannel, parsedRssi))
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
        return dialog
    }

    /** Formatting/IE decoding runs on a worker; the resulting dialog is a frozen scan snapshot. */
    fun detailText(context: Context, network: WifiNetworkSnapshot, nowMillis: Long): String {
        val t = WifiMonitorText(context)
        fun s(id: Int, vararg values: Any) = context.getString(id, *values)
        return buildString {
            appendLine("${s(R.string.monitor_ssid)}: ${t.ssid(network.ssid)}")
            appendLine("${s(R.string.monitor_bssid)}: ${network.bssid}")
            appendLine(s(R.string.monitor_dbm, network.rssi))
            appendLine(s(R.string.monitor_details_radio, network.frequencyMhz, network.channel?.toString() ?: t.unknown(),
                t.width(network), t.frequency(network.center0Mhz), t.frequency(network.center1Mhz), t.standard(network.standard)))
            appendLine(t.security(network))
            appendLine(s(R.string.monitor_details_age, ((nowMillis * 1000 - network.seenAtMicros) / 1_000_000).coerceAtLeast(0), network.seenAtMicros))
            appendLine(s(R.string.monitor_details_flags, t.yesNo(network.responder80211mc), t.yesNo(network.responder80211az), t.yesNo(network.passpoint)))
            appendLine(s(R.string.monitor_details_modern_flags, t.yesNo(network.twtResponder),
                t.yesNo(network.secureHeLtf), t.yesNo(network.rangingFrameProtection)))
            if (network.ssidBytes.isNotEmpty()) appendLine(s(R.string.monitor_ssid_bytes, WifiIeDecoder.hex(network.ssidBytes)))
            appendLine("\n${s(R.string.monitor_capabilities)}\n${network.capabilities}")
            if (network.securityTypes.isNotEmpty()) appendLine(s(R.string.monitor_platform_security, network.securityTypes.joinToString(", ")))
            appendLine(s(R.string.monitor_venue_operator, network.venue.ifEmpty(t::unknown), network.operator.ifEmpty(t::unknown)))
            if (network.mldAddress != null) appendLine(s(R.string.monitor_mlo, network.mldAddress,
                network.mloLinkId?.toString() ?: t.unknown(), network.affiliatedLinks.joinToString("\n")))
            appendLine("\n${s(R.string.monitor_ie_title, network.informationElements.size)}")
            appendLine(s(R.string.monitor_ie_explanation))
            if (network.informationElements.isEmpty()) appendLine(s(R.string.monitor_ie_empty))
            if (network.informationElementsTruncated) appendLine(s(R.string.monitor_ie_truncated))
            network.informationElements.forEach { ie ->
                appendLine("\n${s(R.string.monitor_ie_heading, ie.id, t.ieName(ie), ie.bytes.size)}")
                val decoded = WifiIeDecoder.decode(ie)
                decoded.facts.forEach { appendLine(t.fact(it)) }
                if (decoded.malformed) appendLine(s(R.string.monitor_ie_malformed))
                appendLine(s(R.string.monitor_ie_raw, WifiIeDecoder.hex(ie.bytes)))
            }
        }
    }

    fun details(context: Context, text: String): AlertDialog {
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val content = TextView(context).apply {
            this.text = text; textSize = 13f; typeface = Typeface.MONOSPACE
            setTextIsSelectable(true); setPadding(padding, padding / 2, padding, padding)
        }
        val scroll = ScrollView(context).apply { addView(content) }
        return MaterialAlertDialogBuilder(context).setTitle(R.string.monitor_details)
            .setView(scroll).setPositiveButton(android.R.string.ok, null).show().also {
                it.window?.setLayout((context.resources.displayMetrics.widthPixels * .95).toInt(),
                    (context.resources.displayMetrics.heightPixels * .85).toInt())
            }
    }
}
