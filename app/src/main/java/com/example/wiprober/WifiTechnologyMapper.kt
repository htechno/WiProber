package com.example.wiprober

import android.net.wifi.ScanResult

object WifiTechnologyMapper {
    fun map(wifiStandard: Int, frequency: Int): List<String> {
        val technologies = linkedSetOf<String>()
        when (wifiStandard) {
            ScanResult.WIFI_STANDARD_11BE -> {
                technologies += "BE"
                technologies += "AX"
            }
            ScanResult.WIFI_STANDARD_11AX -> technologies += "AX"
            ScanResult.WIFI_STANDARD_11AC -> technologies += "AC"
            ScanResult.WIFI_STANDARD_11N -> technologies += "N"
            ScanResult.WIFI_STANDARD_11AD -> technologies += "AD"
            ScanResult.WIFI_STANDARD_LEGACY,
            ScanResult.WIFI_STANDARD_UNKNOWN -> Unit
        }

        when (frequency) {
            in 2_400..2_500 -> {
                if (wifiStandard == ScanResult.WIFI_STANDARD_11BE || wifiStandard == ScanResult.WIFI_STANDARD_11AX) {
                    technologies += "N"
                }
                technologies += "G"
                technologies += "B"
            }
            in 4_900..5_900 -> {
                if (wifiStandard == ScanResult.WIFI_STANDARD_11BE || wifiStandard == ScanResult.WIFI_STANDARD_11AX) {
                    technologies += "AC"
                    technologies += "N"
                } else if (wifiStandard == ScanResult.WIFI_STANDARD_11AC) {
                    technologies += "N"
                }
                technologies += "A"
            }
        }
        return technologies.sorted()
    }
}
