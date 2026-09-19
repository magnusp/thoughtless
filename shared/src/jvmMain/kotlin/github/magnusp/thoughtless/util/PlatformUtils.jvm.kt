package github.magnusp.thoughtless.util

import java.util.UUID

actual fun randomId(): String = UUID.randomUUID().toString()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
