package com.example.wiprober

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryDataSerializer {

    private val BYTE_ORDER = ByteOrder.BIG_ENDIAN

    /**
     * Модель для передачи данных в сериализатор.
     * @param relTimestamp - время измерения относительно начала трека (в миллисекундах?? Нет, в единицах Ekahau, см. ниже)
     * @param apIndex - индекс AP в глобальном списке accessPointMeasurements
     * @param network - данные сети (RSSI, частота)
     */
    data class MeasurementEntry(
        val relTimestamp: Int,
        val apIndex: Int,
        val network: WifiNetworkInfo
    )

    /**
     * Сериализует данные сканирования (одиночного или множественного) в бинарный формат.
     */
    fun serialize(measurements: List<MeasurementEntry>): ByteArray {
        // Размер буфера: 7 (Header) + N * 17 (Data) + 1 (Footer)
        val bufferSize = 7 + measurements.size * 17 + 1
        val buffer = ByteBuffer.allocate(bufferSize).order(BYTE_ORDER)

        // 1. Header
        buffer.put(byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00))

        // 2. Data Blocks
        measurements.forEach { item ->
            buffer.put(0x01.toByte()) // Start marker

            buffer.put(0x02.toByte()) // Timestamp marker
            // ВАЖНО: Ekahau вроде бы использует единицы, похожие на миллисекунды,
            // но в CONTINUOUS файлах мы видим большие числа.
            // Судя по анализу, это offset от начала survey.
            buffer.putInt(item.relTimestamp)

            buffer.put(0x04.toByte()) // AP Index marker
            buffer.putShort(item.apIndex.toShort())

            buffer.put(0x05.toByte()) // RSSI marker
            buffer.put(item.network.level.toByte())

            buffer.put(0x11.toByte()) // Frequency marker
            buffer.putInt(item.network.frequency)

            buffer.put(0x09.toByte()) // End marker
        }

        // 3. Footer
        buffer.put(0x10.toByte())

        return buffer.array()
    }
}
