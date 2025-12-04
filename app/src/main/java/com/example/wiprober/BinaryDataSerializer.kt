package com.example.wiprober

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryDataSerializer {

    private val BYTE_ORDER = ByteOrder.BIG_ENDIAN

    /**
     * Сериализует данные одного сканирования в бинарный формат Ekahau.
     * @param measurements Список пар (Порядковый номер, Данные сети).
     *                     Этот список должен быть УЖЕ отсортирован по порядковому номеру!
     */
    fun serialize(measurements: List<Pair<Int, WifiNetworkInfo>>): ByteArray {
        // Рассчитываем размер буфера: 7 (заголовок) + N * 17 (данные) + 1 (футер)
        val bufferSize = 7 + measurements.size * 17 + 1
        val buffer = ByteBuffer.allocate(bufferSize).order(BYTE_ORDER)

        // 1. Пишем заголовок
        buffer.put(byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00))

        // 2. Пишем блоки измерений
        measurements.forEach { (index, network) ->
            buffer.put(0x01.toByte()) // Маркер начала блока

            buffer.put(0x02.toByte()) // Маркер Timestamp
            buffer.putInt(1)         // Значение Timestamp (int32)

            buffer.put(0x04.toByte()) // Маркер Порядковый номер
            buffer.putShort(index.toShort()) // Значение Порядковый номер (int16)

            buffer.put(0x05.toByte()) // Маркер RSSI
            buffer.put(network.level.toByte()) // Значение RSSI (int8, знаковый)

            buffer.put(0x11.toByte()) // Маркер Частота
            buffer.putInt(network.frequency) // Значение Частота (int32)

            buffer.put(0x09.toByte()) // Маркер конца блока
        }

        // 3. Пишем футер
        buffer.put(0x10.toByte())

        // Возвращаем готовый массив байт
        return buffer.array()
    }
}
