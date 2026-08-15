package com.example.wiprober

import com.google.gson.Gson
import java.io.File

internal fun Gson.writeJson(file: File, value: Any) {
    file.parentFile?.mkdirs()
    file.bufferedWriter().use { writer -> toJson(value, writer) }
}

internal fun <T> Gson.readJson(
    file: File,
    type: Class<T>,
    maxBytes: Long,
    errorMessage: String
): T {
    require(file.isFile && file.length() in 1..maxBytes) { errorMessage }
    return file.bufferedReader().use { reader -> fromJson(reader, type) }
}
