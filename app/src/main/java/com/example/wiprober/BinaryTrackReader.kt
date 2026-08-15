package com.example.wiprober

import java.io.EOFException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compatibility reader used by writer tests. Imported ESX projects do not invoke it. */
internal object BinaryTrackReader {
    data class Value(
        val timestamp: Int,
        val apIndex: Int,
        val level: Int,
        val frequency: Int,
        val scanIndex: Int
    )

    fun read(bytes: ByteArray): List<Value> = runCatching { readStrict(bytes) }.getOrDefault(emptyList())

    fun readStrict(bytes: ByteArray): List<Value> {
        require(bytes.size >= 2 && bytes[0] == 0x02.toByte() && bytes[1] == 0x01.toByte()) {
            "Invalid Wi-Fi track header"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(2)
        val values = mutableListOf<Value>()
        var scanIndex: Int? = null
        return try {
            while (buffer.hasRemaining()) {
                when (buffer.get().toInt() and 0xff) {
                    0x00 -> scanIndex = buffer.int
                    0x01 -> {
                        val currentScanIndex = scanIndex ?: throw EOFException()
                        if ((buffer.get().toInt() and 0xff) != 0x02) throw EOFException()
                        val timestamp = buffer.int
                        if ((buffer.get().toInt() and 0xff) != 0x04) throw EOFException()
                        val apIndex = buffer.short.toInt() and 0xffff
                        if ((buffer.get().toInt() and 0xff) != 0x05) throw EOFException()
                        val level = buffer.get().toInt()
                        var fieldMarker = buffer.get().toInt() and 0xff
                        if (fieldMarker == 0x06) {
                            buffer.get()
                            fieldMarker = buffer.get().toInt() and 0xff
                        }
                        if (fieldMarker != 0x11) throw EOFException()
                        val frequency = buffer.int
                        if ((buffer.get().toInt() and 0xff) != 0x09) throw EOFException()
                        values += Value(timestamp, apIndex, level, frequency, currentScanIndex)
                    }
                    0x10 -> Unit
                    else -> throw EOFException()
                }
            }
            values
        } catch (error: BufferUnderflowException) {
            throw IllegalArgumentException("Truncated Wi-Fi track payload", error)
        } catch (error: EOFException) {
            throw IllegalArgumentException("Unsupported or malformed Wi-Fi track payload", error)
        }
    }
}
