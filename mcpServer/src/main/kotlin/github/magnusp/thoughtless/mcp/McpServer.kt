package github.magnusp.thoughtless.mcp

import github.magnusp.thoughtless.mcp.protocol.CallToolResult
import github.magnusp.thoughtless.mcp.protocol.InitializeResult
import github.magnusp.thoughtless.mcp.protocol.JsonRpcError
import github.magnusp.thoughtless.mcp.protocol.JsonRpcRequest
import github.magnusp.thoughtless.mcp.protocol.JsonRpcResponse
import github.magnusp.thoughtless.mcp.protocol.ServerCapabilities
import github.magnusp.thoughtless.mcp.protocol.ServerInfo
import github.magnusp.thoughtless.mcp.tools.ThoughtlessMcpTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.PrintStream

class McpServer(
    private val tools: ThoughtlessMcpTools,
    private val inputStream: InputStream = System.`in`,
    private val outputStream: PrintStream = System.out,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun start() {
        val reader = inputStream.bufferedReader()
        System.err.println("[MCP] Thoughtless MCP Server listening on standard input...")

        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue

                val response = handleLine(line.trim())
                if (response != null) {
                    val responseJson = json.encodeToString(JsonRpcResponse.serializer(), response)
                    synchronized(outputStream) {
                        outputStream.println(responseJson)
                        outputStream.flush()
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[MCP] Fatal read error: ${e.message}")
        }
    }

    fun handleLine(line: String): JsonRpcResponse? {
        val request = try {
            json.decodeFromString(JsonRpcRequest.serializer(), line)
        } catch (e: Exception) {
            return JsonRpcResponse(
                id = null,
                error = JsonRpcError(
                    code = JsonRpcError.PARSE_ERROR,
                    message = "Parse error: ${e.message}"
                )
            )
        }

        // Notification: methods without an id expect no response
        val isNotification = request.id == null
        val response = runBlocking { processRequest(request) }

        return if (isNotification) null else response
    }

    suspend fun processRequest(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            when (request.method) {
                "initialize" -> {
                    val result = InitializeResult(
                        protocolVersion = "2024-11-05",
                        capabilities = ServerCapabilities(tools = buildJsonObject {}),
                        serverInfo = ServerInfo(
                            name = "thoughtless-mcp-server",
                            version = "1.0.0"
                        )
                    )
                    JsonRpcResponse(
                        id = request.id,
                        result = json.encodeToJsonElement(InitializeResult.serializer(), result)
                    )
                }

                "notifications/initialized" -> {
                    // Acknowledgement notification, no response required
                    JsonRpcResponse(id = request.id)
                }

                "tools/list" -> {
                    val list = tools.listToolDefinitions()
                    val result = buildJsonObject {
                        put("tools", json.encodeToJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(
                                github.magnusp.thoughtless.mcp.protocol.ToolDefinition.serializer()
                            ),
                            list
                        ))
                    }
                    JsonRpcResponse(id = request.id, result = result)
                }

                "tools/call" -> {
                    val paramsObj = request.params?.jsonObject
                        ?: return JsonRpcResponse(
                            id = request.id,
                            error = JsonRpcError(
                                code = JsonRpcError.INVALID_PARAMS,
                                message = "Missing params object"
                            )
                        )

                    val name = paramsObj["name"]?.let {
                        if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                    } ?: return JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(
                            code = JsonRpcError.INVALID_PARAMS,
                            message = "Missing parameter 'name'"
                        )
                    )

                    val args = paramsObj["arguments"]?.let {
                        if (it is JsonObject) it else buildJsonObject {}
                    } ?: buildJsonObject {}

                    val toolResult = tools.executeTool(name, args)
                    JsonRpcResponse(
                        id = request.id,
                        result = json.encodeToJsonElement(CallToolResult.serializer(), toolResult)
                    )
                }

                else -> {
                    JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(
                            code = JsonRpcError.METHOD_NOT_FOUND,
                            message = "Method '${request.method}' not found"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = JsonRpcError.INTERNAL_ERROR,
                    message = e.message ?: "Internal error"
                )
            )
        }
    }
}
