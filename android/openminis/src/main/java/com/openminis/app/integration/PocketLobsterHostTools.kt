package com.openminis.app.integration

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object PocketLobsterHostTools {
    const val LOCAL_TOOL = "local_terminal_execute"
    const val UBUNTU_TOOL = "ubuntu_execute"
    private const val HOST_BRIDGE_URL = "http://127.0.0.1:18926/shared/exec"

    fun localTerminalDefinition() = AgentToolDefinition(
        name = LOCAL_TOOL,
        description = "Execute a command in Pocket Lobster's app-local Android terminal. Returns merged output, exit_code, timeout state, and explicit bridge errors.",
        parameters = commandParameters("local Android terminal"),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout"),
    )

    fun ubuntuDefinition() = AgentToolDefinition(
        name = UBUNTU_TOOL,
        description = "Execute a command through Pocket Lobster's bundled Ubuntu Linux bridge. Returns merged output, exit_code, timeout state, and explicit bridge errors.",
        parameters = commandParameters("Ubuntu Linux runtime"),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout"),
    )

    private fun commandParameters(runtime: String) = mapOf(
        "tool_title" to AgentToolParam(
            "string",
            "A concise summary of the command, shown to the user.",
        ),
        "command" to AgentToolParam("string", "Command to execute in the $runtime."),
        "timeout" to AgentToolParam("integer", "Timeout in seconds, from 1 to 900."),
    )

    suspend fun execute(name: String, argsJson: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrElse {
                return@withContext ToolExecutionResult("Invalid tool arguments", false)
            }
            val command = args.optString("command", "")
            val title = args.optString("tool_title", name)
            if (command.isBlank()) {
                return@withContext ToolExecutionResult(
                    output = "Error: command is required",
                    success = false,
                    toolTitle = title,
                )
            }
            val runtime = if (name == UBUNTU_TOOL) "ubuntu" else "local"
            val payload = JSONObject()
                .put("runtime", runtime)
                .put("command", command)
                .put("timeout", args.optLong("timeout", 900L).coerceIn(1L, 900L))
            val response = post(context, payload)
            val exitCode = response.optInt("exitCode", 1)
            val output = response.optString("output", "").ifBlank { "(no output)" }
            ToolExecutionResult(
                output = "$output\nexit_code: $exitCode",
                success = response.optBoolean("ok", false) && exitCode == 0,
                toolTitle = title,
                timedOut = response.optBoolean("timedOut", false),
            )
        }

    private fun post(context: Context, payload: JSONObject): JSONObject {
        val token = SharedBridgeToken.read(context)
        if (token.isEmpty()) return JSONObject().put("ok", false).put("output", "bridge token unavailable")
        val connection = URL(HOST_BRIDGE_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 3_000
            connection.readTimeout = 910_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Pocket-Lobster-Token", token)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(raw) }.getOrElse {
                JSONObject().put("ok", false).put("output", "invalid host bridge response")
            }
        } catch (error: Exception) {
            JSONObject().put("ok", false).put("output", "host bridge unavailable: ${error.message}")
        } finally {
            connection.disconnect()
        }
    }
}
