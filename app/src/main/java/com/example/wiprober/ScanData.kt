package com.example.wiprober

enum class LastAction { SCAN, NOTE, SCAN_SESSION }

// Этот класс будет представлять одну точку сканирования на карте
data class ScanPoint(
    val timestamp: Long, // Время сканирования, чтобы отличать одно от другого
    val x: Float,        // Координата X на карте
    val y: Float,        // Координата Y на карте
    val wifiNetworks: List<WifiNetworkInfo>, // Список сетей, найденных в этой точке
    val importedFromEsx: Boolean = false
)

// Этот класс описывает информацию об одной Wi-Fi сети
data class WifiNetworkInfo(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequency: Int,
    val security: String,
    val technologies: List<String>,
    val informationElements: String
)

data class MapInfo(val fileName: String, val width: Int, val height: Int)

data class AppNote(
    val id: String,
    val text: String,
    /** String form of the source URI. Android URI parsing stays at storage/UI boundaries. */
    val photoUri: String?,
    val photoWidth: Int?,
    val photoHeight: Int?,
    val photoId: String?,
    val x: Float, // Координата на оригинальной карте
    val y: Float,
    val pictureNoteId: String,
    val photoFormat: String? = null,
    val importedFromEsx: Boolean = false
)

// Новый класс для непрерывного сканирования
data class ContinuousScanSession(
    val id: String,
    val startTime: Long,
    val endTime: Long = 0,
    val waypoints: List<RoutePointWrapper>, // Точки нажатий (повороты)
    val scanResults: List<ScanResultWrapper>, // Все результаты сканирований в процессе ходьбы
    val importedFromEsx: Boolean = false
)

data class RoutePointWrapper(
    val timestamp: Long, // Время нажатия относительно начала (startTime)
    val x: Float,
    val y: Float
)

data class ScanResultWrapper(
    val timestamp: Long, // Время получения результатов относительно начала
    val duration: Long,  // Сколько длился этот конкретный скан (разница между startScan и onResults)
    val wifiNetworks: List<WifiNetworkInfo>
)
