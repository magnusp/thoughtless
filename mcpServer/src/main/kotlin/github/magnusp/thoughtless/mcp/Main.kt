package github.magnusp.thoughtless.mcp

import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskProposalRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskRepository
import github.magnusp.thoughtless.mcp.tools.ThoughtlessMcpTools
import github.magnusp.thoughtless.service.DAGDecomposerService
import github.magnusp.thoughtless.service.TaskProposalService
import java.io.File

fun main(args: Array<String>) {
    val dbPath = System.getenv("THOUGHTLESS_DB_PATH")
        ?: (System.getProperty("user.home") + File.separator + ".thoughtless" + File.separator + "arcadedb")

    System.err.println("[MCP] Initializing Thoughtless ArcadeDB engine at: $dbPath")
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
