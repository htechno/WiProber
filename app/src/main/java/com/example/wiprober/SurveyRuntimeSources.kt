package com.example.wiprober

import java.util.UUID

fun interface MillisClock {
    fun now(): Long
}

fun interface IdSource {
    fun newId(): String
}

internal object SystemMillisClock : MillisClock {
    override fun now(): Long = System.currentTimeMillis()
}

internal object UuidIdSource : IdSource {
    override fun newId(): String = UUID.randomUUID().toString()
}
