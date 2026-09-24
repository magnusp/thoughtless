package github.magnusp.thoughtless.mcp

import github.magnusp.thoughtless.mcp.protocol.CallToolResult
import github.magnusp.thoughtless.mcp.protocol.JsonRpcRequest
import github.magnusp.thoughtless.mcp.protocol.JsonRpcResponse
import github.magnusp.thoughtless.mcp.protocol.ToolContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JsonRpcTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testParseRequest() {
        val jsonStr = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
        val req = json.decodeFromString(JsonRpcRequest.serializer(), jsonStr)
        assertEquals("2.0", req.jsonrpc)
        assertEquals(JsonPrimitive(1), req.id)
        assertEquals("initialize", req.method)
        assertNotNull(req.params)
    }

    @Test
    fun testSerializeResponse() {
        val resp = JsonRpcResponse(
            id = JsonPrimitive(42),
            result = buildJsonObject { put("status", "ok") }
        )
        val out = json.encodeToString(JsonRpcResponse.serializer(), resp)
        val decoded = json.decodeFromString(JsonRpcResponse.serializer(), out)
        assertEquals(JsonPrimitive(42), decoded.id)
        assertNull(decoded.error)
        assertNotNull(decoded.result)
    }

    @Test
    fun testCallToolResult() {
        val result = CallToolResult(
            content = listOf(ToolContent(text = "sample output")),
            isError = false
        )
        val encoded = json.encodeToString(CallToolResult.serializer(), result)
        val decoded = json.decodeFromString(CallToolResult.serializer(), encoded)
        assertEquals(1, decoded.content.size)
        assertEquals("sample output", decoded.content[0].text)
        assertEquals(false, decoded.isError)
    }
}
