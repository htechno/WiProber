package com.example.wiprober

import org.junit.Assert.*
import org.junit.Test

class WifiMonitorTest {
    private fun network(bssid: String = "10:20:30:40:50:60", ssid: String = "Office", rssi: Int = -50,
                        frequency: Int = 5180, width: Int = 0, center: Int = 0, center1: Int = 0) =
        WifiNetworkSnapshot(bssid, ssid, rssi, frequency, width, center, center1)

    @Test fun frequencyFixturesIncludeBandEdgesAndSpecialChannels() {
        listOf(2412 to 1, 2472 to 13, 2484 to 14, 4920 to 184, 5180 to 36, 5825 to 165,
            5935 to 2, 5955 to 1, 7115 to 233, 58320 to 1, 69120 to 6).forEach { (mhz, channel) ->
            assertEquals("$mhz MHz", channel, WifiRadioModel.channel(mhz))
        }
        listOf(0, 2401, 2413, 5900, 5940, 7116, 8000).forEach { assertNull(WifiRadioModel.channel(it)) }
        assertEquals(WifiBand.GHZ_6, WifiRadioModel.band(5935))
        assertEquals(WifiBand.GHZ_5, WifiRadioModel.band(4920))
        assertEquals(WifiBand.GHZ_60, WifiRadioModel.band(58320))
        assertEquals(WifiBand.UNKNOWN, WifiRadioModel.band(5900))
    }

    @Test fun channelRectanglesUseCentersAndKeepDiscontiguousSegmentsSeparate() {
        assertEquals(listOf(WifiChannelSpan(5170f, 5250f)), WifiRadioModel.spans(network(width = 2, center = 5210)))
        assertEquals(listOf(WifiChannelSpan(5170f, 5250f), WifiChannelSpan(5490f, 5570f)),
            WifiRadioModel.spans(network(width = 4, center = 5210, center1 = 5530)))
        assertEquals(listOf(WifiChannelSpan(5945f, 6265f)),
            WifiRadioModel.spans(network(frequency = 5955, width = 5, center = 6105)))
        assertTrue(WifiRadioModel.spans(network(width = 2)).isEmpty())
        assertTrue(WifiRadioModel.spans(network(width = 4, center = 5210)).isEmpty())
        assertTrue(WifiRadioModel.spans(network(frequency = 58320)).isEmpty())
    }

    @Test fun securityFixturesDistinguishTransitionEnterpriseAndUnrecognizedModes() {
        val fixtures = mapOf(
            "[ESS]" to setOf(WifiSecurity.OPEN),
            "[WEP][ESS]" to setOf(WifiSecurity.WEP),
            "[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]" to setOf(WifiSecurity.WPA_PERSONAL, WifiSecurity.WPA2_PERSONAL),
            "[RSN-PSK+SAE-CCMP][ESS]" to setOf(WifiSecurity.WPA2_PERSONAL, WifiSecurity.WPA3_PERSONAL),
            "[RSN-SAE-CCMP][ESS]" to setOf(WifiSecurity.WPA3_PERSONAL),
            "[RSN-EAP/SHA1+EAP/SHA256-CCMP][ESS]" to setOf(WifiSecurity.WPA2_ENTERPRISE),
            "[RSN-EAP/SHA1+EAP/SHA256-CCMP][MFPC][ESS]" to setOf(WifiSecurity.WPA2_ENTERPRISE, WifiSecurity.WPA3_ENTERPRISE),
            "[RSN-EAP/SHA256-CCMP][MFPC][MFPR][ESS]" to setOf(WifiSecurity.WPA3_ENTERPRISE),
            "[RSN-EAP/SHA256-TKIP][MFPC][MFPR][ESS]" to setOf(WifiSecurity.WPA2_ENTERPRISE),
            "[RSN-EAP_SUITE_B_192-GCMP-256][ESS]" to setOf(WifiSecurity.WPA3_ENTERPRISE),
            "[RSN-OWE-CCMP][ESS]" to setOf(WifiSecurity.OWE),
            "[OWE_TRANSITION][ESS]" to setOf(WifiSecurity.OWE_TRANSITION),
            "[WAPI-CERT-SMS4][ESS]" to setOf(WifiSecurity.UNKNOWN),
            "[WAPI-PSK-SMS4][ESS]" to setOf(WifiSecurity.UNKNOWN),
            "" to setOf(WifiSecurity.UNKNOWN)
        )
        fixtures.forEach { (caps, expected) -> assertEquals(caps, expected, WifiRadioModel.security(caps)) }
    }

    @Test fun filtersComposeWithoutMergingBssidsAndSortBySignal() {
        val a = network(rssi = -75)
        val b = network(bssid = "10:20:30:40:50:61", rssi = -40)
        val c = network(bssid = "10:20:30:40:50:62", ssid = "Office guest", frequency = 2412)
        assertEquals(listOf(b, c, a), WifiMonitorFilter().apply(listOf(a, b, c)))
        assertEquals(listOf(b), WifiMonitorFilter("oFfIcE", WifiBand.GHZ_5, 36, -60).apply(listOf(a, b, c)))
        assertEquals("AA:BB:CC:DD:EE:FF", WifiRadioModel.normalizeBssid("aa:bb:cc:dd:ee:ff"))
        assertNull(WifiRadioModel.normalizeBssid("02:00:00:00:00:00"))
        assertNull(WifiRadioModel.normalizeBssid("invalid"))
    }

    @Test fun throttleIsAHardGateAndFailuresDoNotRefreshResultsOrAge() {
        val model = WifiMonitorViewModel()
        model.tick(1_000, null, WifiScanAccessRequirement.READY, true)
        assertFalse(model.state.value.canScan)
        assertTrue(model.state.value.history.isEmpty())
        model.tick(2_000, null, WifiScanAccessRequirement.READY, false)
        assertTrue(model.state.value.canScan)
        model.scanStarted(2_000)
        model.scanFinished(listOf(network()), WifiMonitorStatus.UPDATED, 2_000, 3_000)
        model.scanStarted(6_000)
        model.scanFinished(null, WifiMonitorStatus.TIMEOUT, 6_000, 21_000)
        assertEquals(3_000L, model.state.value.lastScanMillis)
        assertEquals(listOf(network()), model.state.value.networks)
        assertEquals(31_000L, model.state.value.nextScanMillis)
        model.tick(22_000, null, WifiScanAccessRequirement.READY, true)
        assertFalse(model.state.value.canScan)
        model.tick(23_000, null, WifiScanAccessRequirement.GRANT_PERMISSIONS, false)
        assertFalse(model.state.value.canScan)
    }

    @Test fun historyIsBoundedAndPreservesDisconnectsAndPauseState() {
        val model = WifiMonitorViewModel()
        val connection = WifiConnectionSnapshot("Office", "10:20:30:40:50:60", -50, 5180, 5, 866, 866, 866)
        for (second in 0..200) model.tick(second * 1_000L, connection, WifiScanAccessRequirement.READY, false)
        assertEquals(121, model.state.value.history.size)
        model.tick(201_000, null, WifiScanAccessRequirement.READY, false)
        assertNull(model.state.value.history.last().rssi)
        model.toggleRunning()
        model.tick(202_000, connection, WifiScanAccessRequirement.READY, false)
        assertEquals(201_000L, model.state.value.history.last().elapsedMillis)
        assertFalse(model.state.value.canScan)
    }
}
