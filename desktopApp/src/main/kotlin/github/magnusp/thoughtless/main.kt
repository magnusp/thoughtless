package github.magnusp.thoughtless

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "thoughtless",
    ) {
        App()
    }
}