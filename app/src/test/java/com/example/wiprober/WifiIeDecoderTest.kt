package com.example.wiprober

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class WifiIeDecoderTest {
    private fun ie(id: Int, hex: String) = WifiInformationElement(id, 0,
        hex.split(" ").filter(String::isNotBlank).map { it.toInt(16) })

    @Test fun decodesBeaconFixtureFieldsInTheirSpecifiedUnits() {
        val rates = WifiIeDecoder.decode(ie(1, "8c 12 98 24 b0 48 60 6c"))
        assertEquals("6.0*, 9.0, 12.0*, 18.0, 24.0*, 36.0, 48.0, 54.0", rates.facts.single().values.single())
        val load = WifiIeDecoder.decode(ie(11, "0b 00 13 00 00"))
        assertEquals(listOf("11", "7.5", "0"), load.facts.single().values)
        val country = WifiIeDecoder.decode(ie(7, "52 55 20 24 08 17 84 03 17 95 05 17"))
        assertEquals(listOf("36", "8", "23"), country.facts[1].values)
        assertEquals(listOf("132", "3", "23"), country.facts[2].values)
        val vendor = WifiIeDecoder.decode(ie(221, "00 50 f2 02"))
        assertEquals(listOf("00:50:F2", "2"), vendor.facts.single().values)
    }

    @Test fun rsnFixturePreservesCipherAndAkmSelectorsAndPmfBits() {
        val decoded = WifiIeDecoder.decode(ie(48, "01 00 00 0f ac 04 01 00 00 0f ac 04 01 00 00 0f ac 08 c0 00"))
        assertFalse(decoded.malformed)
        assertEquals(listOf("1", "00:0F:AC:04", "00:0F:AC:04", "00:0F:AC:08", "true", "true"), decoded.facts.single().values)
    }

    @Test fun truncatedAndMaliciousLengthsNeverCrashOrReadPastTheElement() {
        val valid = ie(48, "01 00 00 0f ac 04 01 00 00 0f ac 04 01 00 00 0f ac 08 c0 00")
        for (size in 0..17) assertTrue(WifiIeDecoder.decode(valid.copy(bytes = valid.bytes.take(size))).malformed)
        assertTrue(WifiIeDecoder.decode(ie(48, "01 00 00 0f ac 04 ff ff")).malformed)
        assertTrue(WifiIeDecoder.decode(ie(11, "0b 00 13")).malformed)
        val random = Random(871)
        for (id in listOf(0, 1, 3, 7, 11, 45, 48, 50, 61, 191, 192, 221, 255)) {
            repeat(256) { size -> WifiIeDecoder.decode(WifiInformationElement(id, 0, List(size) { random.nextInt(256) })) }
        }
    }

    @Test fun unknownElementsKeepTheirExactUnsignedHex() {
        val unknown = ie(254, "00 7f 80 ff")
        assertTrue(WifiIeDecoder.decode(unknown).facts.isEmpty())
        assertFalse(WifiIeDecoder.decode(unknown).malformed)
        assertEquals("00 7F 80 FF", WifiIeDecoder.hex(unknown.bytes))
    }
}
