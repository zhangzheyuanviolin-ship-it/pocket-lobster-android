package com.codex.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhoneUiSummaryResponseTest {
    private fun text(response: JSONObject) = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")

    @Test fun responsesPreservesUnicodeTextAndReportsTruncation() {
        val raw = JSONObject().put("status", "incomplete").put("output", JSONArray().put(JSONObject()
            .put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "尚未加入购物车")))))
        val result = PhoneUiAgentModelClient.normalizeSummaryResponse(raw, "responses")
        assertEquals("尚未加入购物车", text(result))
        assertEquals("length", result.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    }

    @Test fun anthropicAndGeminiSupportUserSelectedSummaryEndpoints() {
        val anthropic = JSONObject().put("stop_reason", "end_turn").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "已搜索，未购买")))
        assertEquals("已搜索，未购买", text(PhoneUiAgentModelClient.normalizeSummaryResponse(anthropic, "anthropic")))
        val gemini = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "用户改为比较价格"))))))
        assertEquals("用户改为比较价格", text(PhoneUiAgentModelClient.normalizeSummaryResponse(gemini, "gemini")))
    }

    @Test fun emptySummaryResponseCannotEraseHistory() {
        assertThrows(IllegalStateException::class.java) {
            PhoneUiAgentModelClient.normalizeSummaryResponse(JSONObject().put("status", "completed"), "responses")
        }
    }

    @Test fun selectedEndpointReceivesOnlyTheSummaryAndPreservesChinese() {
        for ((protocol, path, response) in listOf(
            Triple("chat", "/v1/chat/completions", """{"choices":[{"finish_reason":"stop","message":{"content":"尚未付款"}}]}"""),
            Triple("responses", "/v1/responses", """{"status":"completed","output":[{"content":[{"type":"output_text","text":"尚未付款"}]}]}"""),
            Triple("anthropic", "/v1/messages", """{"stop_reason":"end_turn","content":[{"type":"text","text":"尚未付款"}]}"""),
            Triple("gemini", "/v1/models/test:generateContent", """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"尚未付款"}]}}]}"""),
        )) {
            ServerSocket(0).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                val incoming = executor.submit<Pair<String, String>> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        fun line(): String {
                            val bytes = java.io.ByteArrayOutputStream()
                            while (true) { val value = input.read(); if (value == -1 || value == 10) break; if (value != 13) bytes.write(value) }
                            return bytes.toString("UTF-8")
                        }
                        val first = line()
                        var length = 0
                        while (true) {
                            val header = line(); if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        val bytes = ByteArray(length)
                        java.io.DataInputStream(input).readFully(bytes)
                        val content = response.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n".toByteArray() + content)
                        first to bytes.toString(Charsets.UTF_8)
                    }
                }
                try {
                    val config = PhoneUiModelConfig("test", "test", "http://127.0.0.1:${server.localPort}/v1", "fixture", "test", PhoneUiModelProtocol.GENERIC_JSON, false)
                    assertEquals("尚未付款", PhoneUiAgentModelClient.summarizeContext(config, "云南鲜花饼；只比较价格，未付款", protocol))
                    val (requestLine, body) = incoming.get(5, TimeUnit.SECONDS)
                    assertEquals("POST $path HTTP/1.1", requestLine)
                    assertTrue(body.contains("云南鲜花饼"))
                    assertFalse(body.contains("image_url"))
                } finally { executor.shutdownNow() }
            }
        }
    }
}
