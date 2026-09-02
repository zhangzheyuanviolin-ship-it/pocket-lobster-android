package com.codex.mobile

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.time.Instant
import org.json.JSONObject

class ShizukuShellBridgeServer(
    private val context: Context,
    port: Int = BRIDGE_PORT,
) : NanoHTTPD("127.0.0.1", port) {

    companion object {
        private const val TAG = "ShizukuBridgeServer"
        const val BRIDGE_PORT = 18926
    }

    @Volatile
    private var lastErrorCode: String? = null

    @Volatile
    private var lastErrorMessage: String? = null

    init {
        persistStatusSnapshot(currentStatusPayload())
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/status" -> handleStatus()
                session.method == Method.POST && session.uri == "/enable" -> handleEnable(true)
                session.method == Method.POST && session.uri == "/disable" -> handleEnable(false)
                session.method == Method.POST && session.uri == "/exec" -> handleExec(session)
                session.method == Method.POST && session.uri == "/web/call" -> handleWebCall(session)
                session.method == Method.GET && session.uri == "/shared/status" ->
                    handleSharedStatus(session)
                session.method == Method.POST && session.uri == "/shared/exec" ->
                    handleSharedExec(session)
                session.method == Method.POST && session.uri == "/phone-agent/start" ->
                    handlePhoneAgentStart(session)
                session.method == Method.POST && session.uri == "/phone-agent/status" ->
                    handlePhoneAgentControl(session, "status")
                session.method == Method.POST && session.uri == "/phone-agent/pause" ->
                    handlePhoneAgentControl(session, "pause")
                session.method == Method.POST && session.uri == "/phone-agent/resume" ->
                    handlePhoneAgentControl(session, "resume")
                session.method == Method.POST && session.uri == "/phone-agent/cancel" ->
                    handlePhoneAgentControl(session, "cancel")
                else -> jsonResponse(
                    Response.Status.NOT_FOUND,
                    JSONObject().put("ok", false).put("error", "Not found"),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve failed", e)
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("ok", false).put("error", e.message ?: "Internal error"),
            )
        }
    }

    private fun handleStatus(): Response {
        val body = currentStatusPayload()
        persistStatusSnapshot(body)
        return jsonResponse(Response.Status.OK, body)
    }

    private fun handleEnable(enabled: Boolean): Response {
        ShizukuController.setBridgeEnabled(context, enabled)
        if (!enabled) {
            setLastError("bridge_disabled", "Shizuku bridge is disabled in permission center")
        } else {
            clearLastError()
        }
        val body = currentStatusPayload().put("enabled", enabled)
        persistStatusSnapshot(body)
        return jsonResponse(Response.Status.OK, body)
    }

    private fun handleExec(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: ""
        val payload = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        val command = payload.optString("command", "").trim()
        if (command.isEmpty()) {
            setLastError("invalid_command", "Missing command")
            val body = currentStatusPayload()
                .put("ok", false)
                .put("error_code", "invalid_command")
                .put("error", "Missing command")
            persistStatusSnapshot(currentStatusPayload())
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                body,
            )
        }

        if (!ShizukuController.isBridgeEnabled(context)) {
            setLastError("bridge_disabled", "Shizuku bridge is disabled in permission center")
            val body = currentStatusPayload()
                .put("ok", false)
                .put("error_code", "bridge_disabled")
                .put("error", "Shizuku bridge is disabled in permission center")
            persistStatusSnapshot(currentStatusPayload())
            return jsonResponse(
                Response.Status.FORBIDDEN,
                body,
            )
        }

        val result = ShizukuController.executeShellCommand(command)
        if (result.success) {
            clearLastError()
        } else if (result.errorCode == "command_failed" || result.errorCode == "command_unavailable") {
            // The Shizuku executor successfully started and waited for the process.
            // A command-level non-zero exit must not poison capability health.
            clearLastError()
        } else {
            setLastError(
                result.errorCode ?: "executor_missing",
                result.error ?: "Command execution failed",
            )
        }

        val body = JSONObject()
            .put("ok", result.success)
            .put("success", result.success)
            .put("exitCode", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("error_code", result.errorCode ?: JSONObject.NULL)

        if (result.error != null) {
            body.put("error", result.error)
        }

        persistStatusSnapshot(currentStatusPayload())
        return jsonResponse(Response.Status.OK, body)
    }

    private fun handleWebCall(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: ""
        val payload = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        val method = payload.optString("method", "").trim()
        val params = payload.optJSONObject("params") ?: JSONObject()
        if (method.isEmpty()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject()
                    .put("ok", false)
                    .put("error", "missing_method"),
            )
        }

        val result = WebAutomationManager.handleCall(context, method, params)
        return jsonResponse(Response.Status.OK, result)
    }

    private fun handleSharedStatus(session: IHTTPSession): Response {
        if (!isSharedRequestAuthorized(session)) return sharedUnauthorized()
        return jsonResponse(Response.Status.OK, SharedHostRuntimeBridge.status(context))
    }

    private fun handleSharedExec(session: IHTTPSession): Response {
        if (!isSharedRequestAuthorized(session)) return sharedUnauthorized()
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: ""
        val payload = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        val runtime = payload.optString("runtime", "").trim()
        val command = payload.optString("command", "")
        val timeoutSeconds = payload.optLong("timeout", 900L)
        val result = SharedHostRuntimeBridge.execute(
            context = context,
            runtime = runtime,
            command = command,
            timeoutSeconds = timeoutSeconds,
        )
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("ok", result.exitCode == 0)
                .put("runtime", runtime)
                .put("exitCode", result.exitCode)
                .put("output", result.output)
                .put("timedOut", result.timedOut),
        )
    }

    private fun handlePhoneAgentStart(session: IHTTPSession): Response {
        if (!isSharedRequestAuthorized(session)) return sharedUnauthorized()
        val payload = requestJson(session)
        val task = payload.optString("task").trim()
        val mode = PhoneUiScreenMode.entries.firstOrNull { it.value == payload.optString("mode") }
            ?: PhoneUiScreenMode.VIRTUAL
        val maxSteps = payload.optInt("maxSteps", 25).coerceIn(1, 100)
        val state = PhoneUiAgentRuntime.startTask(context, task, mode, maxSteps)
        return jsonResponse(Response.Status.OK, JSONObject().put("ok", true).put("task", state))
    }

    private fun handlePhoneAgentControl(session: IHTTPSession, action: String): Response {
        if (!isSharedRequestAuthorized(session)) return sharedUnauthorized()
        requestJson(session)
        val state = when (action) {
            "pause" -> PhoneUiAgentRuntime.pause()
            "resume" -> PhoneUiAgentRuntime.resume()
            "cancel" -> PhoneUiAgentRuntime.cancel()
            else -> PhoneUiAgentRuntime.snapshot()
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("ok", true).put("task", state))
    }

    private fun requestJson(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"].orEmpty()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun isSharedRequestAuthorized(session: IHTTPSession): Boolean =
        SharedBridgeTokenStore.matches(context, session.headers["x-pocket-lobster-token"])

    private fun sharedUnauthorized(): Response = jsonResponse(
        Response.Status.UNAUTHORIZED,
        JSONObject().put("ok", false).put("error", "unauthorized"),
    )

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString())
    }

    private fun currentStatusPayload(): JSONObject {
        val installed = ShizukuController.isShizukuAppInstalled(context)
        val running = ShizukuController.isServiceRunning()
        val granted = ShizukuController.hasPermission()
        val enabled = ShizukuController.isBridgeEnabled(context)
        return JSONObject()
            .put("ok", true)
            .put("installed", installed)
            .put("running", running)
            .put("granted", granted)
            .put("enabled", enabled)
            .put("executor", "system-shell")
            .put("bridge_port", BRIDGE_PORT)
            .put("last_error_code", lastErrorCode ?: JSONObject.NULL)
            .put("last_error", lastErrorMessage ?: JSONObject.NULL)
            .put("checked_at", Instant.now().toString())
    }

    private fun persistStatusSnapshot(payload: JSONObject) {
        try {
            val paths = BootstrapInstaller.getPaths(context)
            val statusFile = File(paths.homeDir, ".openclaw-android/capabilities/shizuku.json")
            statusFile.parentFile?.mkdirs()
            statusFile.writeText(payload.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing Shizuku status snapshot: ${e.message}")
        }
    }

    private fun setLastError(code: String, message: String) {
        lastErrorCode = code
        lastErrorMessage = message
    }

    private fun clearLastError() {
        lastErrorCode = null
        lastErrorMessage = null
    }
}
