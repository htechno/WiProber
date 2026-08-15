package com.example.wiprober

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryDataSerializer {

    private val BYTE_ORDER = ByteOrder.BIG_ENDIAN

    /**
     * Модель для передачи данных в сериализатор.
     * @param relTimestamp - время измерения относительно начала трека в МИЛЛИСЕКУНДАХ (Int)
     * @param apIndex - индекс AP в глобальном списке accessPointMeasurements
     * @param network - данные сети (RSSI, частота)
     */
    data class MeasurementEntry(
        val relTimestamp: Int,
        val apIndex: Int,
        val network: WifiNetworkInfo
    )

    /**
     * Сериализует данные сканирования в бинарный формат Ekahau.
     * Структура файла:
     * [Global Header 02 01]
     * [Scan Block 0: (ID 0) (AP Data...) (Footer 10)]
     * [Scan Block 1: (ID 1) (AP Data...) (Footer 10)]
     * ...
     */
    fun serialize(measurements: List<MeasurementEntry>): ByteArray {
        measurements.forEach { item ->
            require(item.relTimestamp >= 0) { "Binary track timestamp must be non-negative" }
            require(item.apIndex in 0..MAX_AP_INDEX) { "Binary track AP index is out of range" }
            require(item.network.level in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                "Binary track signal level is out of range"
            }
            require(item.network.frequency > 0) { "Binary track frequency must be positive" }
        }
        // 1. Группируем измерения по времени.
        // Каждая уникальная временная метка создает отдельный блок "Scan" (измерение).
        val groupedScans = measurements
            .groupBy { it.relTimestamp }
            .toSortedMap()

        // 2. Рассчитываем итоговый размер буфера
        // Global Header: 2 байта
        var totalSize = 2L

        groupedScans.forEach { (_, apList) ->
            require(apList.map(MeasurementEntry::apIndex).distinct().size == apList.size) {
                "Binary track contains duplicate AP measurements in one scan"
            }
            // Для каждого скана:
            // 5 байт (Scan ID: 00 + Int)
            // N * 17 байт (AP Data)
            // 1 байт (Scan Footer: 10)
            totalSize += SCAN_OVERHEAD_BYTES + apList.size.toLong() * AP_MEASUREMENT_BYTES
            require(totalSize <= MAX_BINARY_TRACK_BYTES) { "Binary track is too large" }
        }

        val buffer = ByteBuffer.allocate(totalSize.toInt()).order(BYTE_ORDER)

        // 3. Пишем Глобальный заголовок (ОДИН РАЗ)
        buffer.put(0x02.toByte())
        buffer.put(0x01.toByte())

        // 4. Пишем блоки сканирований
        var scanIndex = 0 // Порядковый номер измерения (00 00 00 00 00 ...)

        groupedScans.forEach { (_, apList) ->

            // --- Scan ID (5 байт) ---
            buffer.put(0x00.toByte()) // Padding? Marker?
            buffer.putInt(scanIndex)  // Сам индекс

            // --- БЛОКИ AP ВНУТРИ ЭТОГО СКАНИРОВАНИЯ ---
            apList.forEach { item ->
                buffer.put(0x01.toByte()) // Start marker

                buffer.put(0x02.toByte()) // Timestamp marker
                buffer.putInt(item.relTimestamp) // timestamp (ms)

                buffer.put(0x04.toByte()) // AP Index marker
                buffer.putShort(item.apIndex.toShort())

                buffer.put(0x05.toByte()) // RSSI marker
                buffer.put(item.network.level.toByte())

                buffer.put(0x11.toByte()) // Frequency marker
                buffer.putInt(item.network.frequency)

                buffer.put(0x09.toByte()) // End marker (для AP)
            }

            // --- Scan Footer (1 байт) ---
            // Конец данных по этому конкретному измерению
            buffer.put(0x10.toByte())

            scanIndex++
        }

        return buffer.array()
    }

    private const val MAX_AP_INDEX = 0xFFFF
    private const val AP_MEASUREMENT_BYTES = 17L
    private const val SCAN_OVERHEAD_BYTES = 6L
    private const val MAX_BINARY_TRACK_BYTES = 64L * 1024L * 1024L
}
