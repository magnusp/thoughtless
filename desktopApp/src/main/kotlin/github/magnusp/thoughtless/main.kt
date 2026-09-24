package github.magnusp.thoughtless

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.mcp.McpServer
import github.magnusp.thoughtless.mcp.tools.ThoughtlessMcpTools
import github.magnusp.thoughtless.ui.DesktopApp
import github.magnusp.thoughtless.ui.WorkspaceViewModel

fun main() = application {
    // 1. Initialize shared ViewModel and underlying repositories
    val viewModel = WorkspaceViewModel.createDefault()

    // 2. Start in-process MCP Server sidecar listening on local TCP socket
    val sidecarPort = System.getenv("THOUGHTLESS_MCP_PORT")?.toIntOrNull() ?: McpServer.DEFAULT_TCP_PORT
    val mcpTools = ThoughtlessMcpTools(
        taskRepository = viewModel.taskRepository,
        taskProposalRepository = viewModel.taskProposalRepository ?: error("taskProposalRepository missing"),
        contextGraphRepository = viewModel.contextGraphRepository,
        taskProposalService = viewModel.taskProposalService ?: error("taskProposalService missing"),
        dagDecomposerService = viewModel.dagDecomposerService,
        arcadeDBEngine = ArcadeDBEngine.getDefault(),
    )
    val mcpServer = McpServer(tools = mcpTools)
    try {
        mcpServer.startSocketServer(port = sidecarPort)
    } catch (e: Exception) {
        System.err.println("[MCP Sidecar] Could not bind socket on port $sidecarPort: ${e.message}")
    }

    val windowState = rememberWindowState(width = 1050.dp, height = 720.dp)
    Window(
        onCloseRequest = {
            mcpServer.stop()
            exitApplication()
        },
        title = "thoughtless — Local-first Task Tracker",
        state = windowState,
    ) {
        DesktopApp(viewModel = viewModel)
    }
}
