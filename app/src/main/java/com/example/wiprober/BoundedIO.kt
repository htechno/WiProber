package com.example.wiprober

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream

internal object BoundedIo {
    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        errorMessage: String
    ): Long {
        require(maxBytes >= 0L)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { errorMessage }
            output.write(buffer, 0, count)
        }
        return total
    }

    fun copyToFile(
        input: InputStream,
        destination: File,
        maxBytes: Long,
        errorMessage: String
    ): Long {
        destination.parentFile?.mkdirs()
        return try {
            destination.outputStream().buffered().use { output ->
                copy(input, output, maxBytes, errorMessage)
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    fun readBytes(
        input: InputStream,
        declaredSize: Long,
        maxBytes: Long,
        errorMessage: String
    ): ByteArray {
        require(declaredSize in 0..maxBytes) { errorMessage }
        val output = ByteArrayOutputStream(declaredSize.toInt())
        copy(input, output, maxBytes, errorMessage)
        return output.toByteArray()
    }
}

/** Enforces a byte limit even when a decoder owns the read loop. */
internal class ByteLimitInputStream(
    input: InputStream,
    private val maxBytes: Long,
    private val errorMessage: String
) : FilterInputStream(input) {
    private var consumed = 0L
    private var markedConsumed = 0L

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) account(1L)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { count ->
            if (count > 0) account(count.toLong())
        }

    override fun skip(count: Long): Long = super.skip(count).also(::account)

    @Synchronized
    override fun mark(readLimit: Int) {
        super.mark(readLimit)
        markedConsumed = consumed
    }

    @Synchronized
    override fun reset() {
        super.reset()
        consumed = markedConsumed
    }

    private fun account(count: Long) {
        consumed += count
        require(consumed <= maxBytes) { errorMessage }
    }
}
