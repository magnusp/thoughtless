package github.magnusp.thoughtless

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val windowState = rememberWindowState(width = 1050.dp, height = 720.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "thoughtless — Local-first Task Tracker",
        state = windowState,
    ) {
        App()
    }
}