package com.openminis.app.integration

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object PocketLobsterHostTools {
    const val LOCAL_TOOL = "local_terminal_execute"
    const val UBUNTU_TOOL = "ubuntu_execute"
    const val PHONE_START_TOOL = "phone_ui_agent_start"
    const val PHONE_STATUS_TOOL = "phone_ui_agent_status"
    const val PHONE_PAUSE_TOOL = "phone_ui_agent_pause"
    const val PHONE_RESUME_TOOL = "phone_ui_agent_resume"
    const val PHONE_CANCEL_TOOL = "phone_ui_agent_cancel"
    val NAMES = setOf(
        LOCAL_TOOL,
        UBUNTU_TOOL,
        PHONE_START_TOOL,
        PHONE_STATUS_TOOL,
        PHONE_PAUSE_TOOL,
        PHONE_RESUME_TOOL,
        PHONE_CANCEL_TOOL,
    )
    private const val HOST_BRIDGE_URL = "http://127.0.0.1:18926"

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

    fun phoneAgentDefinitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = PHONE_START_TOOL,
            description = "Start Pocket Lobster's host-owned visual phone UI subagent with the exact user task. Use virtual mode unless the user explicitly asks to operate the physical main screen. Retain the returned taskId for every status or control call.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise user-visible description of the phone task."),
                "task" to AgentToolParam("string", "Complete natural-language task for the phone UI subagent."),
                "mode" to AgentToolParam("string", "Screen mode.", listOf("virtual", "main")),
                "maxSteps" to AgentToolParam("integer", "Maximum autonomous action steps, from 1 to 100."),
            ),
            required = listOf("tool_title", "task"),
            propertyOrdering = listOf("tool_title", "task", "mode", "maxSteps"),
        ),
        simplePhoneDefinition(PHONE_STATUS_TOOL, "Read authoritative status, progress events and terminal result or error for the taskId returned by start. While terminal is false, poll again after nextPollAfterMs."),
        simplePhoneDefinition(PHONE_PAUSE_TOOL, "Pause the phone UI task identified by taskId before user takeover."),
        simplePhoneDefinition(PHONE_RESUME_TOOL, "Resume the paused phone UI task identified by taskId."),
        simplePhoneDefinition(PHONE_CANCEL_TOOL, "Cancel the phone UI task identified by taskId and stop further screen actions."),
    )

    private fun simplePhoneDefinition(name: String, description: String) = AgentToolDefinition(
        name = name,
        description = description,
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise user-visible action description."),
            "taskId" to AgentToolParam("string", "The taskId returned by phone_ui_agent_start."),
        ),
        required = listOf("tool_title", "taskId"),
        propertyOrdering = listOf("tool_title", "taskId"),
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
            val title = args.optString("tool_title", name)
            if (name.startsWith("phone_ui_agent_")) {
                return@withContext executePhoneTool(name, args, title, context)
            }
            val command = args.optString("command", "")
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
            val response = post(context, "/shared/exec", payload, 910_000)
            val exitCode = response.optInt("exitCode", 1)
            val output = response.optString("output", "").ifBlank { "(no output)" }
            ToolExecutionResult(
                output = "$output\nexit_code: $exitCode",
                success = response.optBoolean("ok", false) && exitCode == 0,
                toolTitle = title,
                timedOut = response.optBoolean("timedOut", false),
            )
        }

    private fun executePhoneTool(
        name: String,
        args: JSONObject,
        title: String,
        context: Context,
    ): ToolExecutionResult {
        val route: String
        val payload = JSONObject()
        when (name) {
            PHONE_START_TOOL -> {
                route = "/phone-agent/start"
                val task = args.optString("task", "").trim()
                if (task.isEmpty()) return ToolExecutionResult("Error: task is required", false, toolTitle = title)
                val taskBytes = task.toByteArray(Charsets.UTF_8)
                payload.put("task", task)
                    .put("taskBase64", Base64.getEncoder().encodeToString(taskBytes))
                    .put("taskSha256", taskBytes.sha256())
                    .put("mode", args.optString("mode", "virtual").ifBlank { "virtual" })
                    .put("maxSteps", args.optInt("maxSteps", 25).coerceIn(1, 100))
            }
            PHONE_STATUS_TOOL -> route = "/phone-agent/status"
            PHONE_PAUSE_TOOL -> route = "/phone-agent/pause"
            PHONE_RESUME_TOOL -> route = "/phone-agent/resume"
            PHONE_CANCEL_TOOL -> route = "/phone-agent/cancel"
            else -> return ToolExecutionResult("Unknown host tool: $name", false, toolTitle = title)
        }
        args.optString("taskId").trim().takeIf(String::isNotEmpty)?.let { payload.put("taskId", it) }
        val response = post(context, route, payload, 35_000)
        if (name == PHONE_START_TOOL && response.optBoolean("ok")) {
            val expected = payload.optString("taskSha256")
            if (response.optString("taskSha256") != expected) {
                return ToolExecutionResult(
                    output = "Error: phone UI task integrity verification failed",
                    success = false,
                    toolTitle = title,
                )
            }
        }
        return ToolExecutionResult(
            output = response.toString(2),
            success = response.optBoolean("ok", false),
            toolTitle = title,
        )
    }

    private fun post(context: Context, route: String, payload: JSONObject, readTimeoutMs: Int): JSONObject {
        val token = SharedBridgeToken.read(context)
        if (token.isEmpty()) return JSONObject().put("ok", false).put("output", "bridge token unavailable")
        val connection = URL(HOST_BRIDGE_URL + route).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 3_000
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Pocket-Lobster-Token", token)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(raw) }.getOrElse {
                JSONObject().put("ok", false).put("output", "invalid host bridge response")
            }
        } catch (error: Exception) {
            JSONObject().put("ok", false).put("output", "host bridge unavailable: ${error.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
