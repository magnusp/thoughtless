package github.magnusp.thoughtless.util

expect fun randomId(): String

expect fun currentTimeMillis(): Long

expect fun formatIsoTimestamp(epochMillis: Long): String

expect fun parseIsoTimestamp(isoString: String): Long

