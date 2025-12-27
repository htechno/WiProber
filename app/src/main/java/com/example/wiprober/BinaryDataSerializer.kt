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
        // 1. Группируем измерения по времени.
        // Каждая уникальная временная метка создает отдельный блок "Scan" (измерение).
        val groupedScans = measurements
            .groupBy { it.relTimestamp }
            .toSortedMap()

        // 2. Рассчитываем итоговый размер буфера
        // Global Header: 2 байта
        var totalSize = 2

        groupedScans.forEach { (_, apList) ->
            // Для каждого скана:
            // 5 байт (Scan ID: 00 + Int)
            // N * 17 байт (AP Data)
            // 1 байт (Scan Footer: 10)
            totalSize += 5 + (apList.size * 17) + 1
        }

        val buffer = ByteBuffer.allocate(totalSize).order(BYTE_ORDER)

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
}
