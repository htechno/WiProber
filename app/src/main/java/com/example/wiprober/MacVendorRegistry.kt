package com.example.wiprober

import java.io.DataInputStream
import java.io.InputStream

internal data class MacVendor(val shortName: String, val fullName: String)

internal class MacVendorRegistry private constructor(
    private val prefixes: LongArray,
    private val vendorIds: IntArray,
    private val vendors: Array<MacVendor?>
) {
    /** Input is a normalized six-octet BSSID from the scan boundary. */
    fun lookup(bssid: String): MacVendor? {
        val address = bssid.replace(":", "").toLong(16)
        if ((address ushr 40) and 3L != 0L) return null
        for (bits in prefixLengths) {
            val prefix = address ushr (48 - bits)
            // Include the length so that leading zeroes in nested assignments are significant.
            val key = (prefix shl 8) or bits.toLong()
            val index = prefixes.binarySearch(key)
            if (index >= 0) return vendors[vendorIds[index]]
        }
        return null
    }

    companion object {
        private val prefixLengths = intArrayOf(36, 28, 24)

        fun read(input: InputStream): MacVendorRegistry = DataInputStream(input.buffered()).use { data ->
            check(data.readInt() == 0x4F554931) { "Invalid MAC vendor resource" } // OUI1
            val vendors = arrayOfNulls<MacVendor>(data.readUnsignedShort() + 1)
            fun readText(): String {
                val bytes = ByteArray(data.readUnsignedShort())
                data.readFully(bytes)
                return bytes.toString(Charsets.UTF_8)
            }
            for (id in 1 until vendors.size) vendors[id] = MacVendor(readText(), readText())
            val count = data.readInt()
            val prefixes = LongArray(count)
            val ids = IntArray(count)
            for (i in prefixes.indices) {
                prefixes[i] = data.readLong()
                ids[i] = data.readUnsignedShort()
            }
            MacVendorRegistry(prefixes, ids, vendors)
        }
    }
}
