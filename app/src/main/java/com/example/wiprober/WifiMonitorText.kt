package com.example.wiprober

import android.content.Context

internal class WifiMonitorText(private val context: Context) {
    fun unknown() = context.getString(R.string.monitor_not_available)
    fun ssid(value: String) = value.ifEmpty { context.getString(R.string.monitor_hidden_ssid) }
    fun band(value: WifiBand) = context.getString(when (value) {
        WifiBand.GHZ_2_4 -> R.string.monitor_band_24
        WifiBand.GHZ_5 -> R.string.monitor_band_5
        WifiBand.GHZ_6 -> R.string.monitor_band_6
        WifiBand.UNKNOWN -> R.string.monitor_unknown
    })
    fun standard(value: Int) = context.getString(when (value) {
        1 -> R.string.monitor_standard_legacy; 4 -> R.string.monitor_standard_n
        5 -> R.string.monitor_standard_ac; 6 -> R.string.monitor_standard_ax
        8 -> R.string.monitor_standard_be
        else -> R.string.monitor_unknown
    })
    fun width(network: WifiNetworkSnapshot): String = when {
        network.widthCode == 4 -> context.getString(R.string.monitor_width_80_80)
        else -> WifiRadioModel.widthMhz(network.widthCode)?.let { context.getString(R.string.monitor_mhz, it) } ?: unknown()
    }
    fun security(modes: Set<WifiSecurity>, compact: Boolean = false): String {
        return modes.sorted().joinToString(if (compact) "/" else " / ") {
            context.getString(securityLabel(it, compact))
        }
    }

    private fun securityLabel(mode: WifiSecurity, compact: Boolean): Int = when (mode) {
        WifiSecurity.OPEN -> R.string.monitor_security_open
        WifiSecurity.WEP -> R.string.monitor_security_wep
        WifiSecurity.WPA_PERSONAL -> if (compact) R.string.monitor_short_wpa_p else R.string.monitor_security_wpa_psk
        WifiSecurity.WPA_ENTERPRISE -> if (compact) R.string.monitor_short_wpa_e else R.string.monitor_security_wpa_eap
        WifiSecurity.WPA2_PERSONAL -> if (compact) R.string.monitor_short_wpa2_p else R.string.monitor_security_wpa2_psk
        WifiSecurity.WPA2_ENTERPRISE -> if (compact) R.string.monitor_short_wpa2_e else R.string.monitor_security_wpa2_eap
        WifiSecurity.WPA3_PERSONAL -> if (compact) R.string.monitor_short_wpa3_p else R.string.monitor_security_wpa3_psk
        WifiSecurity.WPA3_ENTERPRISE -> if (compact) R.string.monitor_short_wpa3_e else R.string.monitor_security_wpa3_eap
        WifiSecurity.OWE -> R.string.monitor_security_owe
        WifiSecurity.OWE_TRANSITION -> if (compact) R.string.monitor_short_owe_transition else R.string.monitor_security_owe_transition
        WifiSecurity.UNKNOWN -> if (compact) R.string.monitor_short_unknown else R.string.monitor_unknown
    }
    fun yesNo(value: Boolean?) = value?.let { context.getString(if (it) R.string.monitor_yes else R.string.monitor_no) } ?: unknown()
    fun frequency(value: Int) = if (value > 0) context.getString(R.string.monitor_mhz, value) else unknown()
    fun speed(value: Int) = if (value > 0) context.getString(R.string.monitor_mbps, value) else unknown()
    fun ieName(ie: WifiInformationElement): String {
        return context.getString(when (ie.id) {
        0 -> R.string.monitor_ie_ssid; 1 -> R.string.monitor_ie_rates; 3 -> R.string.monitor_ie_dsss
        7 -> R.string.monitor_ie_country; 11 -> R.string.monitor_ie_load; 45 -> R.string.monitor_ie_ht_cap
        48 -> R.string.monitor_ie_rsn; 50 -> R.string.monitor_ie_extended_rates; 61 -> R.string.monitor_ie_ht_op
        191 -> R.string.monitor_ie_vht_cap; 192 -> R.string.monitor_ie_vht_op; 221 -> R.string.monitor_ie_vendor
        255 -> when (ie.extensionId) {
            35 -> R.string.monitor_ie_he_cap; 36 -> R.string.monitor_ie_he_op; 106 -> R.string.monitor_ie_eht_op
            107 -> R.string.monitor_ie_multilink; 108 -> R.string.monitor_ie_eht_cap
            else -> return context.getString(R.string.monitor_ie_extension, ie.extensionId)
        }
        else -> R.string.monitor_ie_generic
    })
    }
    fun fact(fact: WifiIeFact): String {
        val values = fact.values.toMutableList()
        if (fact.kind == WifiIeFactKind.RSN) {
            values[1] = suites(values[1], R.array.monitor_rsn_ciphers)
            values[2] = suites(values[2], R.array.monitor_rsn_ciphers)
            values[3] = suites(values[3], R.array.monitor_rsn_akms)
        }
        // Vendor OUI is a byte identifier; only expose the decoded decimal type.
        if (fact.kind == WifiIeFactKind.VENDOR) return context.getString(R.string.monitor_ie_vendor_value, values[1])
        val booleanFields = when (fact.kind) {
            WifiIeFactKind.RSN -> setOf(4, 5)
            WifiIeFactKind.HT_CAPABILITIES -> setOf(0, 1, 2, 3)
            WifiIeFactKind.HT_OPERATION -> setOf(2)
            WifiIeFactKind.VHT_CAPABILITIES -> setOf(1, 2, 3)
            else -> emptySet()
        }
        booleanFields.forEach { values[it] = yesNo(values[it].toBooleanStrictOrNull()) }
        return context.getString(when (fact.kind) {
        WifiIeFactKind.SSID -> R.string.monitor_ie_ssid_value
        WifiIeFactKind.RATES -> R.string.monitor_ie_rates_value
        WifiIeFactKind.CHANNEL -> R.string.monitor_ie_channel_value
        WifiIeFactKind.COUNTRY -> R.string.monitor_ie_country_value
        WifiIeFactKind.COUNTRY_CHANNELS -> R.string.monitor_ie_country_channels
        WifiIeFactKind.BSS_LOAD -> R.string.monitor_ie_load_value
        WifiIeFactKind.HT_CAPABILITIES -> R.string.monitor_ie_ht_value
        WifiIeFactKind.HT_OPERATION -> R.string.monitor_ie_ht_op_value
        WifiIeFactKind.VHT_CAPABILITIES -> R.string.monitor_ie_vht_value
        WifiIeFactKind.VHT_OPERATION -> R.string.monitor_ie_vht_op_value
        WifiIeFactKind.VENDOR -> R.string.monitor_ie_vendor_value
        WifiIeFactKind.RSN -> R.string.monitor_ie_rsn_value
    }, *values.toTypedArray())
    }

    private fun suites(selectors: String, namesResource: Int): String {
        val names = context.resources.getStringArray(namesResource)
        return selectors.split(",").joinToString(", ") { selector ->
            val fields = selector.trim().split(":")
            val type = if (fields.size == 4 && fields.take(3).joinToString(":").equals("00:0F:AC", true))
                fields[3].toIntOrNull(16) else null
            type?.let { names.getOrNull(it) }?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.monitor_unknown)
        }
    }
}
