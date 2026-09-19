package github.magnusp.thoughtless.util

import java.time.Instant
import java.util.UUID

actual fun randomId(): String = UUID.randomUUID().toString()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatIsoTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).toString()

actual fun parseIsoTimestamp(isoString: String): Long =
    try {
        Instant.parse(isoString).toEpochMilli()
    } catch (_: Exception) {
        currentTimeMillis()
    }

