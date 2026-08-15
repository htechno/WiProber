package com.example.wiprober

import java.nio.charset.StandardCharsets
import java.util.UUID

/** Creates strict UUID strings for ESX entities, even with deterministic test entropy. */
internal object EsxIdFactory {
    fun create(scope: String, entropy: String): String = UUID.nameUUIDFromBytes(
        "wiprober:$scope:$entropy".toByteArray(StandardCharsets.UTF_8)
    ).toString()

    fun isUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value.lowercase()
    }.getOrDefault(false)
}
