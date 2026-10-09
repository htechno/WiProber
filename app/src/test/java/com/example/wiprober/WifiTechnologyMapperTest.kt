package com.example.wiprober

import android.net.wifi.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiTechnologyMapperTest {
    @Test
    fun mapsWifi6ByBandWithoutAddingLegacyFiveGhzToSixGhz() {
        assertEquals(
            listOf("AX"),
            WifiTechnologyMapper.map(ScanResult.WIFI_STANDARD_11AX, 5_955)
        )
        assertEquals(
            listOf("A", "AC", "AX", "N"),
            WifiTechnologyMapper.map(ScanResult.WIFI_STANDARD_11AX, 5_180)
        )
        assertEquals(
            listOf("AX", "B", "G", "N"),
            WifiTechnologyMapper.map(ScanResult.WIFI_STANDARD_11AX, 2_412)
        )
    }

    @Test
    fun mapsWifi7AndWigigExplicitly() {
        assertEquals(
            listOf("AX", "BE"),
            WifiTechnologyMapper.map(ScanResult.WIFI_STANDARD_11BE, 6_115)
        )
        assertEquals(
            listOf("AD"),
            WifiTechnologyMapper.map(ScanResult.WIFI_STANDARD_11AD, 60_480)
        )
    }
}
