package github.magnusp.thoughtless

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform