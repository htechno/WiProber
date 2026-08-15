package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BinaryTrackReaderTest {
    @Test
    fun readsDataWrittenBySerializer() {
        val firstNetwork = network("00:11:22:33:44:55", -47, 2412)
        val secondNetwork = network("00:11:22:33:44:66", -72, 5180)
        val bytes = BinaryDataSerializer.serialize(
            listOf(
                BinaryDataSerializer.MeasurementEntry(150, 0, firstNetwork),
                BinaryDataSerializer.MeasurementEntry(150, 1, secondNetwork),
                BinaryDataSerializer.MeasurementEntry(300, 0, firstNetwork.copy(level = -50))
            )
        )

        assertEquals(
            listOf(
                BinaryTrackReader.Value(150, 0, -47, 2412, 0),
                BinaryTrackReader.Value(150, 1, -72, 5180, 0),
                BinaryTrackReader.Value(300, 0, -50, 2412, 1)
            ),
            BinaryTrackReader.read(bytes)
        )
    }

    @Test
    fun rejectsInvalidData() {
        assertEquals(emptyList<BinaryTrackReader.Value>(), BinaryTrackReader.read(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun readsEkahauNoiseFloorField() {
        val bytes = byteArrayOf(
            0x02, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00,
            0x01,
            0x02, 0x00, 0x00, 0x09, 0x27,
            0x04, 0x00, 0x00,
            0x05, 0xd2.toByte(),
            0x06, 0xa6.toByte(),
            0x11, 0x00, 0x00, 0x09, 0x6c,
            0x09,
            0x10
        )

        assertEquals(
            listOf(BinaryTrackReader.Value(2343, 0, -46, 2412, 0)),
            BinaryTrackReader.readStrict(bytes)
        )
    }

    @Test
    fun serializerRejectsValuesThatCannotBeRepresentedByTheTrackFormat() {
        assertThrows(IllegalArgumentException::class.java) {
            BinaryDataSerializer.serialize(
                listOf(BinaryDataSerializer.MeasurementEntry(-1, 0, network("a", -50, 2_412)))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BinaryDataSerializer.serialize(
                listOf(BinaryDataSerializer.MeasurementEntry(1, 65_536, network("a", -50, 2_412)))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BinaryDataSerializer.serialize(
                listOf(BinaryDataSerializer.MeasurementEntry(1, 0, network("a", -200, 2_412)))
            )
        }
    }

    private fun network(bssid: String, level: Int, frequency: Int) = WifiNetworkInfo(
        ssid = "test",
        bssid = bssid,
        level = level,
        frequency = frequency,
        security = "WPA2",
        technologies = listOf("N"),
        informationElements = ""
    )
}
