package com.example.wiprober

import java.util.UUID
import android.net.Uri

// Этот класс будет представлять одну точку сканирования на карте
data class ScanPoint(
    val timestamp: Long, // Время сканирования, чтобы отличать одно от другого
    val x: Float,        // Координата X на карте
    val y: Float,        // Координата Y на карте
    val wifiNetworks: List<WifiNetworkInfo> // Список сетей, найденных в этой точке
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
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val photoUri: Uri?,
    val photoWidth: Int?,
    val photoHeight: Int?,
    val photoId: String? = if (photoUri != null) UUID.randomUUID().toString() else null,
    val x: Float, // Координата на оригинальной карте
    val y: Float,
    val pictureNoteId: String = UUID.randomUUID().toString()
)

// Новый класс для непрерывного сканирования
data class ContinuousScanSession(
    val id: String = UUID.randomUUID().toString(),
    val startTime: Long,
    var endTime: Long = 0,
    val waypoints: MutableList<RoutePointWrapper>, // Точки нажатий (повороты)
    val scanResults: MutableList<ScanResultWrapper> // Все результаты сканирований в процессе ходьбы
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