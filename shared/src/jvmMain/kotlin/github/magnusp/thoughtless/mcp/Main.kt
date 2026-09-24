package github.magnusp.thoughtless.mcp

import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskProposalRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskRepository
import github.magnusp.thoughtless.mcp.tools.ThoughtlessMcpTools
import github.magnusp.thoughtless.service.DAGDecomposerService
import github.magnusp.thoughtless.service.TaskProposalService
import java.io.File
import java.net.Socket

fun main(args: Array<String>) {
    val port = System.getenv("THOUGHTLESS_MCP_PORT")?.toIntOrNull() ?: McpServer.DEFAULT_TCP_PORT

    // 1. Try to connect to an existing running in-process desktop sidecar first!
    if (tryConnectToSidecar(port)) {
        return
    }

    // 2. Otherwise run standalone headless with embedded ArcadeDB
    val dbPath = System.getenv("THOUGHTLESS_DB_PATH")
        ?: ArcadeDBEngine.DEFAULT_DATABASE_PATH

    System.err.println("[MCP] Desktop app sidecar not detected. Launching standalone embedded engine at: $dbPath")
    val engine = ArcadeDBEngine(databasePath = dbPath)
    engine.open()

    val taskRepo = ArcadeDBTaskRepository(engine)
    val proposalRepo = ArcadeDBTaskProposalRepository(engine)
    val graphRepo = ArcadeDBContextGraphRepository(engine)

    val proposalService = TaskProposalService(
        proposalRepository = proposalRepo,
        taskRepository = taskRepo,
        contextGraphRepository = graphRepo
    )
    val dagService = DAGDecomposerService(
        taskRepository = taskRepo,
        contextGraphRepository = graphRepo
    )

    val tools = ThoughtlessMcpTools(
        taskRepository = taskRepo,
        taskProposalRepository = proposalRepo,
        contextGraphRepository = graphRepo,
        taskProposalService = proposalService,
        dagDecomposerService = dagService,
        arcadeDBEngine = engine
    )

    val server = McpServer(tools = tools)

    Runtime.getRuntime().addShutdownHook(Thread {
        System.err.println("[MCP] Shutting down Thoughtless MCP Server...")
        try {
            engine.close()
        } catch (_: Exception) {}
    })

    server.start()
}

/**
 * Attempts to bridge standard input/output to a running Desktop app MCP socket sidecar.
 * Returns true if connection was successfully established and served until EOF.
 */
private fun tryConnectToSidecar(port: Int): Boolean {
    val socket = try {
        Socket("127.0.0.1", port)
    } catch (_: Exception) {
        return false
    }

    System.err.println("[MCP] Connected to active Thoughtless Desktop App sidecar on 127.0.0.1:$port")

    val socketIn = socket.getInputStream().bufferedReader()
    val socketOut = java.io.PrintStream(socket.getOutputStream(), true)
    val stdIn = System.`in`.bufferedReader()
    val stdOut = System.out

    // Thread 1: Pipe socket responses to stdout
    val readerThread = kotlin.concurrent.thread(name = "Sidecar-To-Stdout", isDaemon = false) {
        try {
            while (true) {
                val line = socketIn.readLine() ?: break
                synchronized(stdOut) {
                    stdOut.println(line)
                    stdOut.flush()
                }
            }
        } catch (_: Exception) {}
    }

    // Thread 2: Pipe stdin requests to socket
    try {
        while (true) {
            val line = stdIn.readLine() ?: break
            socketOut.println(line)
            socketOut.flush()
        }
    } catch (_: Exception) {}

    try {
        socket.shutdownOutput()
        readerThread.join(2000)
        socket.close()
    } catch (_: Exception) {}

    return true
}
