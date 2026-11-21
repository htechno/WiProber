package com.example.wiprober

import java.util.Locale

internal enum class WifiIeFactKind { SSID, RATES, CHANNEL, COUNTRY, COUNTRY_CHANNELS,
    BSS_LOAD, HT_CAPABILITIES, HT_OPERATION, VHT_CAPABILITIES, VHT_OPERATION, VENDOR, RSN }
internal data class WifiIeFact(val kind: WifiIeFactKind, val values: List<String>)
internal data class WifiIeDecoded(val facts: List<WifiIeFact>, val malformed: Boolean)

/** Only decodes fields whose lengths are known. Unknown/extended IEs remain in the snapshot without speculative decoding. */
internal object WifiIeDecoder {
    fun decode(ie: WifiInformationElement): WifiIeDecoded {
        val b = ie.bytes
        val facts = mutableListOf<WifiIeFact>()
        fun requireSize(size: Int) { require(b.size >= size) }
        fun u16(offset: Int): Int { requireSize(offset + 2); return b[offset] or (b[offset + 1] shl 8) }
        fun fact(kind: WifiIeFactKind, vararg values: Any) {
            facts += WifiIeFact(kind, values.map(Any::toString))
        }
        return try {
            when (ie.id) {
                0 -> { require(b.size <= 32); fact(WifiIeFactKind.SSID, b.map(Int::toByte).toByteArray().toString(Charsets.UTF_8)) }
                1, 50 -> { requireSize(1); fact(WifiIeFactKind.RATES, b.joinToString(", ") {
                    String.format(Locale.ROOT, "%.1f%s", (it and 127) / 2.0, if (it and 128 != 0) "*" else "")
                }) }
                3 -> { requireSize(1); fact(WifiIeFactKind.CHANNEL, b[0]) }
                7 -> {
                    requireSize(3)
                    fact(WifiIeFactKind.COUNTRY, b.take(3).map(Int::toByte).toByteArray().toString(Charsets.US_ASCII))
                    var offset = 3
                    while (offset + 2 < b.size) {
                        // Values >= 201 are regulatory extension triplets, not channel ranges.
                        if (b[offset] < 201) fact(WifiIeFactKind.COUNTRY_CHANNELS,
                            b[offset], b[offset + 1], b[offset + 2].toByte().toInt())
                        offset += 3
                    }
                    require(b.size - offset <= 1)
                }
                11 -> { requireSize(5); fact(WifiIeFactKind.BSS_LOAD, u16(0),
                    String.format(Locale.ROOT, "%.1f", b[2] * 100.0 / 255), u16(3)) }
                45 -> { requireSize(26); val caps = u16(0)
                    fact(WifiIeFactKind.HT_CAPABILITIES, caps and 1 != 0, caps and 2 != 0,
                        caps and 32 != 0, caps and 64 != 0, (caps shr 2) and 3) }
                61 -> { requireSize(22); fact(WifiIeFactKind.HT_OPERATION, b[0], b[1] and 3, b[1] and 4 != 0) }
                191 -> { requireSize(12); val caps = u16(0)
                    fact(WifiIeFactKind.VHT_CAPABILITIES, (caps shr 2) and 3,
                        caps and 16 != 0, caps and 32 != 0, caps and 64 != 0) }
                192 -> { requireSize(5); fact(WifiIeFactKind.VHT_OPERATION, b[0], b[1], b[2]) }
                221 -> { requireSize(3); fact(WifiIeFactKind.VENDOR, hex(b.take(3), ":"), b.getOrNull(3) ?: "—") }
                48 -> {
                    requireSize(8)
                    val version = u16(0)
                    val group = hex(b.subList(2, 6), ":")
                    var offset = 8
                    val pairwiseCount = u16(6)
                    require(pairwiseCount <= (b.size - offset) / 4)
                    val pairwise = (0 until pairwiseCount).map {
                        hex(b.subList(offset, offset + 4), ":").also { offset += 4 }
                    }
                    val akmCount = u16(offset); offset += 2
                    require(akmCount <= (b.size - offset) / 4)
                    val akms = (0 until akmCount).map {
                        hex(b.subList(offset, offset + 4), ":").also { offset += 4 }
                    }
                    val caps = if (offset < b.size) u16(offset) else 0
                    fact(WifiIeFactKind.RSN, version, group, pairwise.joinToString(", "),
                        akms.joinToString(", "), caps and 128 != 0, caps and 64 != 0)
                }
            }
            WifiIeDecoded(facts.toList(), false)
        } catch (_: IllegalArgumentException) {
            WifiIeDecoded(facts.toList(), true)
        }
    }

    fun hex(bytes: List<Int>, separator: String = " "): String =
        bytes.joinToString(separator) { String.format(Locale.ROOT, "%02X", it and 255) }
}
