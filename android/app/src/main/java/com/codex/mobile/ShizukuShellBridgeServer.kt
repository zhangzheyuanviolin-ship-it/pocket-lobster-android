package com.codex.mobile

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.InputStreamReader
import java.time.Instant
import java.util.concurrent.TimeUnit
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
                session.method == Method.POST && session.uri == "/local-shell/call" -> handleLocalShellCall(session)
                session.method == Method.POST && session.uri == "/web/call" -> handleWebCall(session)
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

    private fun handleLocalShellCall(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: ""
        val payload = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        val command = payload.optString("command", "").trim()
        val cwd = payload.optString("cwd", "").trim()
        val timeoutMs = payload.optLong("timeoutMs", 30_000L).coerceIn(1_000L, 180_000L)
        if (command.isEmpty()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("ok", false).put("error", "missing_command"),
            )
        }

        val result = executeLocalShellCommand(command, cwd, timeoutMs)
        val body = JSONObject()
            .put("ok", result.success)
            .put("success", result.success)
            .put("exitCode", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("cwd", result.cwd)
            .put("error_code", result.errorCode ?: JSONObject.NULL)
        if (result.error != null) {
            body.put("error", result.error)
        }
        return jsonResponse(Response.Status.OK, body)
    }

    private data class LocalShellResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val cwd: String,
        val errorCode: String? = null,
        val error: String? = null,
    )

    private fun executeLocalShellCommand(command: String, cwd: String, timeoutMs: Long): LocalShellResult {
        val paths = BootstrapInstaller.getPaths(context)
        val safeCwd = cwd.ifBlank { paths.homeDir }
        val shellCommand = "cd ${shellQuote(safeCwd)} 2>/dev/null || exit 1\n$command"
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-lc", shellCommand)
                .directory(File(paths.homeDir))
                .redirectErrorStream(false)
                .apply {
                    environment().clear()
                    environment().putAll(buildLocalShellEnvironment(paths))
                }
                .start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutThread = Thread {
                InputStreamReader(process.inputStream).use { reader ->
                    stdout.append(reader.readText())
                }
            }
            val stderrThread = Thread {
                InputStreamReader(process.errorStream).use { reader ->
                    stderr.append(reader.readText())
                }
            }
            stdoutThread.start()
            stderrThread.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutThread.join(500)
                stderrThread.join(500)
                return LocalShellResult(
                    success = false,
                    stdout = stdout.toString(),
                    stderr = stderr.toString(),
                    exitCode = -1,
                    cwd = safeCwd,
                    errorCode = "timeout",
                    error = "local_shell_timeout",
                )
            }
            stdoutThread.join(1000)
            stderrThread.join(1000)
            val exitCode = process.exitValue()
            LocalShellResult(
                success = exitCode == 0,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                exitCode = exitCode,
                cwd = safeCwd,
            )
        } catch (error: Exception) {
            LocalShellResult(
                success = false,
                stdout = "",
                stderr = "",
                exitCode = -1,
                cwd = safeCwd,
                errorCode = "local_shell_error",
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun buildLocalShellEnvironment(paths: BootstrapInstaller.Paths): Map<String, String> {
        val bionicCompat = "${paths.homeDir}/.openclaw-android/patches/bionic-compat.js"
        val runtimeBinDir = "${paths.homeDir}/.openclaw-android/linux-runtime/bin"
        val bionicCompatOpt = if (File(bionicCompat).exists()) " -r $bionicCompat" else ""
        val env = mutableMapOf(
            "PREFIX" to paths.prefixDir,
            "HOME" to paths.homeDir,
            "PATH" to "$runtimeBinDir:${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
            "LD_LIBRARY_PATH" to "${paths.prefixDir}/lib",
            "LD_PRELOAD" to "${paths.prefixDir}/lib/libtermux-exec.so",
            "TERMUX_PREFIX" to paths.prefixDir,
            "TERMUX__PREFIX" to paths.prefixDir,
            "LANG" to "en_US.UTF-8",
            "TMPDIR" to paths.tmpDir,
            "TMP" to paths.tmpDir,
            "TEMP" to paths.tmpDir,
            "PROOT_TMP_DIR" to paths.tmpDir,
            "TERM" to "xterm-256color",
            "ANYCLAW_UBUNTU_BIN" to "$runtimeBinDir/ubuntu-shell.sh",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "ANDROID_STORAGE" to "/sdcard",
            "EXTERNAL_STORAGE" to "/sdcard",
            "APT_CONFIG" to "${paths.prefixDir}/etc/apt/apt.conf",
            "DPKG_ADMINDIR" to "${paths.prefixDir}/var/lib/dpkg",
            "SSL_CERT_FILE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "SSL_CERT_DIR" to "/system/etc/security/cacerts",
            "CURL_CA_BUNDLE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_SSL_CAINFO" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_EXEC_PATH" to "${paths.prefixDir}/libexec/git-core",
            "GIT_TEMPLATE_DIR" to "${paths.prefixDir}/share/git-core/templates",
            "OPENSSL_CONF" to "${paths.prefixDir}/etc/tls/openssl.cnf",
            "NODE_OPTIONS" to "--openssl-config=${paths.prefixDir}/etc/tls/openssl.cnf --unhandled-rejections=none$bionicCompatOpt",
            "NAPI_RS_NATIVE_LIBRARY_PATH" to "${paths.homeDir}/.openclaw-android/native/davey/davey.android-arm64.node",
            "CONTAINER" to "1",
        )
        val toolchainEnvFile = File(paths.homeDir, ".openclaw-android/state/toolchain.env")
        if (toolchainEnvFile.exists()) {
            toolchainEnvFile.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@forEachLine
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    env[key] = value
                }
            }
        }
        return env
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

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
