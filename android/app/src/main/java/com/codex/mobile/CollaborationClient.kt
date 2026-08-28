package com.codex.mobile

import android.content.Context
import java.io.OutputStreamWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

object CollaborationPreferences {
    private const val PREFS = "pocket_lobster_collaboration"
    private const val KEY_ENABLED_PREFIX = "enabled_"

    fun isEnabled(context: Context, leader: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED_PREFIX + leader.trim().lowercase(), false)

    fun setEnabled(context: Context, leader: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED_PREFIX + leader.trim().lowercase(), enabled)
            .apply()
    }
}

object CollaborationClient {
    fun listRuns(context: Context): JSONObject = request(context, "GET", "/collaboration-api/runs")

    fun exportRun(context: Context, runId: String): JSONObject = request(
        context,
        "GET",
        "/collaboration-api/export?runId=${URLEncoder.encode(runId.trim(), Charsets.UTF_8.name())}",
    )

    fun start(context: Context, leader: String, prompt: String): JSONObject = request(
        context = context,
        method = "POST",
        path = "/collaboration-api/start",
        body = JSONObject().put("leader", leader).put("prompt", prompt.trim()),
        readTimeoutMs = 45_000,
    )

    fun abort(context: Context, runId: String): JSONObject = request(
        context = context,
        method = "POST",
        path = "/collaboration-api/abort",
        body = JSONObject().put("runId", runId.trim()),
        readTimeoutMs = 45_000,
    )

    fun continueRun(context: Context, runId: String, prompt: String): JSONObject = request(
        context = context,
        method = "POST",
        path = "/collaboration-api/continue",
        body = JSONObject().put("runId", runId.trim()).put("prompt", prompt.trim()),
        readTimeoutMs = 45_000,
    )

    fun rename(context: Context, runId: String, title: String): JSONObject = request(
        context = context,
        method = "POST",
        path = "/collaboration-api/rename",
        body = JSONObject().put("runId", runId.trim()).put("title", title.trim()),
    )

    fun archive(context: Context, runId: String, archived: Boolean): JSONObject = request(
        context = context,
        method = "POST",
        path = "/collaboration-api/archive",
        body = JSONObject().put("runId", runId.trim()).put("archived", archived),
    )

    fun delete(context: Context, runId: String): JSONObject = request(
        context = context,
        method = "POST",
        path = "/collaboration-api/delete",
        body = JSONObject().put("runId", runId.trim()),
    )

    private fun request(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null,
        readTimeoutMs: Int = 20_000,
    ): JSONObject {
        ensureServerReady(context)
        var lastError: IOException? = null
        repeat(3) { attempt ->
            try {
                return requestOnce(method, path, body, readTimeoutMs)
            } catch (error: IOException) {
                lastError = error
                CodexForegroundService.ensureStarted(context)
                if (attempt < 2) Thread.sleep(700L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("协作服务连接失败")
    }

    private fun ensureServerReady(context: Context) {
        if (isServerReady(context)) return
        CollaborationHostDiagnostics.record(context, "native_client_wait_start")
        CodexForegroundService.ensureStarted(context)
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            if (isServerReady(context)) return
            Thread.sleep(500)
        }
        CollaborationHostDiagnostics.record(context, "native_client_wait_timeout")
        throw IOException("协作服务未能在60秒内启动")
    }

    private fun isServerReady(context: Context): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/host-api/health")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 1_200
            connection.readTimeout = 1_200
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return@runCatching false
            if (!connection.contentType.orEmpty().lowercase().contains("application/json")) {
                return@runCatching false
            }
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(raw).optString("bundleId") == expectedBundleId(context)
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun expectedBundleId(context: Context): String {
        val versionName = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        return versionName.removeSuffix("-beta")
    }

    private fun requestOnce(
        method: String,
        path: String,
        body: JSONObject?,
        readTimeoutMs: Int,
    ): JSONObject {
        val connection = URL("http://127.0.0.1:${CodexServerManager.SERVER_PORT}$path")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use { it.write(body.toString()) }
                }
            }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "协作服务HTTP $status" })
            }
            if (!connection.contentType.orEmpty().lowercase().contains("application/json")) {
                throw IOException("协作服务返回了页面内容，宿主版本尚未就绪")
            }
            runCatching { JSONObject(raw.ifBlank { "{}" }) }
                .getOrElse { throw IOException("协作服务返回了无效JSON", it) }
        } finally {
            connection.disconnect()
        }
    }
}
