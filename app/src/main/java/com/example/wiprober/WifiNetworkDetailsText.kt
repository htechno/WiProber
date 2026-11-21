package com.example.wiprober

import android.content.Context

internal object WifiNetworkDetailsText {
    /** Formatting/IE decoding runs on a worker; the resulting full-screen view is a frozen scan snapshot. */
    fun format(context: Context, network: WifiNetworkSnapshot, nowMillis: Long): String {
        val t = WifiMonitorText(context)
        fun s(id: Int, vararg values: Any) = context.getString(id, *values)
        return buildString {
            appendLine("${s(R.string.monitor_ssid)}: ${t.ssid(network.ssid)}")
            appendLine("${s(R.string.monitor_bssid)}: ${network.bssid}")
            network.vendor?.let { appendLine(s(R.string.monitor_vendor, it.fullName)) }
            appendLine(s(R.string.monitor_dbm, network.rssi))
            appendLine(s(R.string.monitor_details_radio, network.frequencyMhz, network.channel?.toString() ?: t.unknown(),
                t.width(network), t.frequency(network.center0Mhz), t.frequency(network.center1Mhz), t.standard(network.standard)))
            appendLine(t.security(network.security))
            appendLine(s(R.string.monitor_details_age, ((nowMillis * 1000 - network.seenAtMicros) / 1_000_000).coerceAtLeast(0), network.seenAtMicros))
            appendLine(s(R.string.monitor_details_flags, t.yesNo(network.responder80211mc), t.yesNo(network.responder80211az), t.yesNo(network.passpoint)))
            appendLine(s(R.string.monitor_details_modern_flags, t.yesNo(network.twtResponder),
                t.yesNo(network.secureHeLtf), t.yesNo(network.rangingFrameProtection)))
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
                appendLine("\n${s(R.string.monitor_ie_heading, ie.id, t.ieName(ie))}")
                val decoded = WifiIeDecoder.decode(ie)
                decoded.facts.forEach { appendLine(t.fact(it)) }
                if (decoded.malformed) appendLine(s(R.string.monitor_ie_malformed))
                else if (decoded.facts.isEmpty()) appendLine(s(R.string.monitor_ie_not_decoded))
            }
        }
    }

}
