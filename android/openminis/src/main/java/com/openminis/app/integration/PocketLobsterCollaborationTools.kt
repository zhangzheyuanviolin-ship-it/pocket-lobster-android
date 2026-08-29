package com.openminis.app.integration

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PocketLobsterCollaborationTools {
    const val DELEGATE = "collaboration_delegate"
    const val DELEGATE_MANY = "collaboration_delegate_many"
    const val STATUS = "collaboration_status"
    const val WAIT = "collaboration_wait"
    const val FOLLOWUP = "collaboration_followup"
    const val CANCEL = "collaboration_cancel"
    const val FINISH = "collaboration_finish"
    val NAMES = setOf(DELEGATE, DELEGATE_MANY, STATUS, WAIT, FOLLOWUP, CANCEL, FINISH)

    private const val HOST_URL = "http://127.0.0.1:18923/collaboration-api/tool"

    fun definitions(): List<AgentToolDefinition> = listOf(
        definition(
            DELEGATE,
            "Delegate one bounded task to a collaboration member and receive a durable taskId immediately.",
            commonParams() + mapOf(
                "agentId" to AgentToolParam("string", "Target collaboration member.", listOf("codex", "claude", "minis")),
                "objective" to AgentToolParam("string", "Boundary-clear task objective."),
                "expectedOutput" to AgentToolParam("string", "Expected evidence or result."),
                "requiresSharedWorkspace" to AgentToolParam("boolean", "Enable shared file artifacts only when truly required."),
                "parentTaskId" to AgentToolParam("string", "Optional parent task id."),
            ),
            listOf("runId", "agentId", "objective"),
        ),
        definition(
            DELEGATE_MANY,
            "Delegate one or two independent member tasks in parallel. assignments_json must be a JSON array of objects with agentId, objective, optional expectedOutput and requiresSharedWorkspace.",
            commonParams() + ("assignments_json" to AgentToolParam("string", "JSON array containing one or two independent assignments.")),
            listOf("runId", "assignments_json"),
        ),
        definition(
            STATUS,
            "Read authoritative task status, heartbeat, errors, completed output and user messages added during execution.",
            commonParams() + ("task_ids_json" to AgentToolParam("string", "Optional JSON array of task ids; omit to read all current-turn tasks.")),
            listOf("runId"),
        ),
        definition(
            WAIT,
            "Wait for specified tasks to become terminal and return their authoritative outputs as evidence.",
            commonParams() + mapOf(
                "task_ids_json" to AgentToolParam("string", "JSON array of task ids."),
                "waitMode" to AgentToolParam("string", "Wait for all or any task.", listOf("all", "any")),
                "timeoutSeconds" to AgentToolParam("integer", "Wait timeout from 1 to 120 seconds."),
            ),
            listOf("runId", "task_ids_json"),
        ),
        definition(
            FOLLOWUP,
            "Send a bounded follow-up instruction to the member that completed an earlier task.",
            commonParams() + mapOf(
                "parentTaskId" to AgentToolParam("string", "Completed parent task id."),
                "instruction" to AgentToolParam("string", "Specific follow-up or correction."),
                "expectedOutput" to AgentToolParam("string", "Expected evidence or result."),
            ),
            listOf("runId", "parentTaskId", "instruction"),
        ),
        definition(
            CANCEL,
            "Cancel one active collaboration member task.",
            commonParams() + ("taskId" to AgentToolParam("string", "Active task id to cancel.")),
            listOf("runId", "taskId"),
        ),
        definition(
            FINISH,
            "Submit the final user-facing response or clarification after reading every terminal task result.",
            commonParams() + mapOf(
                "action" to AgentToolParam("string", "Final action.", listOf("respond", "ask_user")),
                "message" to AgentToolParam("string", "Final user-facing message without internal ids or prompts."),
                "evidence_task_ids_json" to AgentToolParam("string", "JSON array listing every task id created in this turn; use an empty array when no member was called."),
            ),
            listOf("runId", "action", "message", "evidence_task_ids_json"),
        ),
    )

    suspend fun execute(name: String, argsJson: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (name !in NAMES) return@withContext ToolExecutionResult("Unknown collaboration tool: $name", false)
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return@withContext ToolExecutionResult("Invalid collaboration tool arguments", false)
        }
        val forwarded = JSONObject(args.toString())
        when (name) {
            DELEGATE_MANY -> moveJsonArray(forwarded, "assignments_json", "assignments")
            STATUS, WAIT -> moveJsonArray(forwarded, "task_ids_json", "taskIds", required = name == WAIT)
            FINISH -> moveJsonArray(forwarded, "evidence_task_ids_json", "evidenceTaskIds", required = true)
        }
        val payload = JSONObject()
            .put("callerAgentId", "minis")
            .put("tool", name)
            .put("arguments", forwarded)
        val response = post(payload)
        ToolExecutionResult(
            output = response.toString(2),
            success = response.optBoolean("ok", false),
            toolTitle = name,
        )
    }

    private fun commonParams() = mapOf(
        "runId" to AgentToolParam("string", "Collaboration run id supplied by the coordinator runtime prompt."),
        "turnNumber" to AgentToolParam("integer", "Current host turn number supplied by the coordinator runtime prompt."),
        "leaderLeaseId" to AgentToolParam("string", "Current leader-attempt lease supplied by the coordinator runtime prompt."),
    )

    private fun definition(
        name: String,
        description: String,
        parameters: Map<String, AgentToolParam>,
        required: List<String>,
    ) = AgentToolDefinition(
        name = name,
        description = description,
        parameters = parameters,
        required = (listOf("runId", "turnNumber", "leaderLeaseId") + required).distinct(),
        propertyOrdering = parameters.keys.toList(),
    )

    private fun moveJsonArray(target: JSONObject, sourceKey: String, destinationKey: String, required: Boolean = true) {
        val raw = target.opt(sourceKey)
        target.remove(sourceKey)
        val array = when (raw) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull()
            else -> null
        }
        if (array != null) {
            target.put(destinationKey, array)
        } else if (required) {
            target.put(destinationKey, JSONArray())
        }
    }

    private fun post(payload: JSONObject): JSONObject {
        val connection = URL(HOST_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 130_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrElse {
                JSONObject().put("ok", false).put("error", "invalid collaboration host response")
            }
        } catch (error: Exception) {
            JSONObject().put("ok", false).put("error", "collaboration host unavailable: ${error.message}")
        } finally {
            connection.disconnect()
        }
    }
}
