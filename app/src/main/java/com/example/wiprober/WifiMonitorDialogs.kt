package com.example.wiprober

import android.content.Context
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
}
