package com.example.wiprober

import java.util.Locale

internal enum class WifiBand {
    GHZ_2_4, GHZ_5, GHZ_6, UNKNOWN;

    companion object {
        val descending = listOf(GHZ_6, GHZ_5, GHZ_2_4)
        val supported = descending.toSet()
    }
}
internal enum class WifiSecurity { OPEN, WEP, WPA_PERSONAL, WPA_ENTERPRISE, WPA2_PERSONAL,
    WPA2_ENTERPRISE, WPA3_PERSONAL, WPA3_ENTERPRISE, OWE, OWE_TRANSITION, UNKNOWN }

internal data class WifiNetworkSnapshot(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val widthCode: Int,
    val center0Mhz: Int = 0,
    val center1Mhz: Int = 0,
    val standard: Int = 0,
    val capabilities: String = "",
    val seenAtMicros: Long = 0,
    val passpoint: Boolean = false,
    val responder80211mc: Boolean = false,
    val responder80211az: Boolean? = null,
    val twtResponder: Boolean? = null,
    val secureHeLtf: Boolean? = null,
    val rangingFrameProtection: Boolean? = null,
    val ssidBytes: List<Int> = emptyList(),
    val venue: String = "",
    val operator: String = "",
    val mldAddress: String? = null,
    val mloLinkId: Int? = null,
    val affiliatedLinks: List<String> = emptyList(),
    val securityTypes: List<Int> = emptyList(),
    val informationElements: List<WifiInformationElement> = emptyList(),
    val informationElementsTruncated: Boolean = false,
    val vendor: MacVendor? = null
) {
    val band get() = WifiRadioModel.band(frequencyMhz)
    val channel get() = WifiRadioModel.channel(frequencyMhz)
    val security get() = WifiRadioModel.security(capabilities)
}

internal data class WifiConnectionSnapshot(
    val ssid: String, val bssid: String, val rssi: Int, val frequencyMhz: Int,
    val standard: Int, val linkMbps: Int, val txMbps: Int, val rxMbps: Int,
    val mldAddress: String? = null
)

internal data class WifiSignalSample(val elapsedMillis: Long, val bssid: String?, val rssi: Int?)
internal data class WifiInformationElement(val id: Int, val extensionId: Int, val bytes: List<Int>)
internal data class WifiChannelSpan(val startMhz: Float, val endMhz: Float)

internal data class WifiMonitorFilter(
    val ssid: String = "", val bands: Set<WifiBand> = WifiBand.supported,
    val channel: Int? = null, val minimumRssi: Int? = null
) {
    val isActive get() = this != WifiMonitorFilter()
    fun apply(networks: List<WifiNetworkSnapshot>): List<WifiNetworkSnapshot> = networks.filter {
        it.ssid.contains(ssid.trim(), ignoreCase = true) &&
            it.band in bands &&
            (channel == null || it.channel == channel) &&
            (minimumRssi == null || it.rssi >= minimumRssi)
    }.sortedWith(compareByDescending<WifiNetworkSnapshot> { it.rssi }.thenBy { it.bssid })
}

internal sealed interface WifiTableRow {
    data class Section(val band: WifiBand, val count: Int) : WifiTableRow
    data class Network(val snapshot: WifiNetworkSnapshot, val showBand: Boolean) : WifiTableRow
}

/** Keeps the RSSI order of the filtered results within each selected band. */
internal fun wifiTableRows(networks: List<WifiNetworkSnapshot>, split: Boolean): List<WifiTableRow> {
    if (!split) return networks.map { WifiTableRow.Network(it, showBand = true) }
    val groups = networks.groupBy { it.band }
    return buildList {
        for (band in WifiBand.descending) {
            val group = groups[band] ?: continue
            add(WifiTableRow.Section(band, group.size))
            group.mapTo(this) { WifiTableRow.Network(it, showBand = false) }
        }
    }
}

/** Public ScanResult constants represented as integers to keep this boundary JVM-testable. */
internal object WifiRadioModel {
    fun normalizeBssid(value: String?): String? = value?.takeIf {
        it.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")) &&
            it != "00:00:00:00:00:00" && it != "02:00:00:00:00:00"
    }?.uppercase(Locale.ROOT)

    fun band(frequency: Int): WifiBand = when (frequency) {
        in 2400..2500 -> WifiBand.GHZ_2_4
        in 4900..5895 -> WifiBand.GHZ_5
        in 5925..7125 -> WifiBand.GHZ_6
        else -> WifiBand.UNKNOWN
    }

    fun channel(frequency: Int): Int? = when {
        frequency == 2484 -> 14
        frequency in 2412..2472 && (frequency - 2407) % 5 == 0 -> (frequency - 2407) / 5
        frequency in 4910..4980 && frequency % 5 == 0 -> (frequency - 4000) / 5
        frequency in 5005..5895 && frequency % 5 == 0 -> (frequency - 5000) / 5
        frequency == 5935 -> 2
        frequency in 5955..7115 && (frequency - 5950) % 5 == 0 -> (frequency - 5950) / 5
        else -> null
    }

    fun widthMhz(code: Int): Int? = when (code) {
        0 -> 20; 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 160; 5 -> 320
        else -> null
    }

    fun spans(network: WifiNetworkSnapshot): List<WifiChannelSpan> {
        if (network.band == WifiBand.UNKNOWN) return emptyList()
        val width = widthMhz(network.widthCode) ?: return emptyList()
        fun span(center: Int, mhz: Int) = WifiChannelSpan(center - mhz / 2f, center + mhz / 2f)
        if (network.widthCode == 4) {
            if (network.center0Mhz <= 0 || network.center1Mhz <= 0) return emptyList()
            return listOf(span(network.center0Mhz, 80), span(network.center1Mhz, 80))
        }
        val center = if (width == 20) network.frequencyMhz else network.center0Mhz
        return if (center > 0 && band(center) == network.band) listOf(span(center, width)) else emptyList()
    }

    fun security(capabilities: String): Set<WifiSecurity> {
        val caps = capabilities.uppercase(Locale.ROOT)
        val result = linkedSetOf<WifiSecurity>()
        val tags = Regex("\\[([^]]+)]").findAll(caps).map { it.groupValues[1] }.toList()
        // EAP/SHA256 alone is not WPA3. Match Android's PMF/cipher conditions.
        val enterprisePmfCapable = "RSN" in caps && "EAP/SHA256" in caps &&
            "WEP" !in caps && "TKIP" !in caps && "MFPC" in tags
        val enterpriseWpa3Only = enterprisePmfCapable && "EAP/SHA1" !in caps && "MFPR" in tags
        val enterpriseTransition = enterprisePmfCapable && "EAP/SHA1" in caps && "MFPR" !in tags
        for (tag in tags) {
            val wpaOrRsn = listOf("WPA-", "WPA2-", "WPA3-", "RSN-").any(tag::startsWith)
            if ("WEP" in tag) result += WifiSecurity.WEP
            if ("OWE_TRANSITION" in tag) result += WifiSecurity.OWE_TRANSITION
            else if ("OWE" in tag) result += WifiSecurity.OWE
            if ("SAE" in tag) result += WifiSecurity.WPA3_PERSONAL
            if (wpaOrRsn && "PSK" in tag) result += if (tag.startsWith("WPA-"))
                WifiSecurity.WPA_PERSONAL else WifiSecurity.WPA2_PERSONAL
            if (wpaOrRsn && ("EAP" in tag || "SUITE_B" in tag)) result += when {
                tag.startsWith("WPA-") -> WifiSecurity.WPA_ENTERPRISE
                "SUITE_B" in tag || tag.startsWith("WPA3-") || enterpriseWpa3Only -> WifiSecurity.WPA3_ENTERPRISE
                else -> WifiSecurity.WPA2_ENTERPRISE
            }
        }
        if (enterpriseTransition) result += WifiSecurity.WPA3_ENTERPRISE
        if (result.isEmpty()) result += if (tags.isNotEmpty() && tags.all {
                it == "ESS" || it == "IBSS" || it == "WPS"
            }) WifiSecurity.OPEN else WifiSecurity.UNKNOWN
        return result
    }
}

/** Monotonic deadline survives pause/resume and rotation without a burst of requests. */
internal object WifiMonitorCadence {
    const val INTERVAL_MILLIS = 4_000L
    fun nextDeadline(startedAt: Long, finishedAt: Long, failed: Boolean): Long =
        maxOf(startedAt + INTERVAL_MILLIS, finishedAt + if (failed) 10_000L else 1_000L)
}
