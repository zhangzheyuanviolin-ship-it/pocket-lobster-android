package com.openminis.app.integration

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.OutputStreamWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

object CollaborationClient {
    private const val PREFS = "pocket_lobster_collaboration"
    private const val KEY_ENABLED = "enabled_minis"
    private const val SERVER_PORT = 18923
    private const val HOST_SERVICE = "com.codex.mobile.CodexForegroundService"
    private const val COLLABORATION_PROTOCOL_ID = "durable-agent-tools-v2"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun startIfEnabled(
        context: Context,
        prompt: String,
        restorePrompt: (String) -> Unit = {},
    ): Boolean {
        if (!isEnabled(context)) return false
        Thread {
            val result = runCatching {
                MinisCollaborationDiagnostics.record(context, "wait_start")
                ensureServerReady(context)
                start(context, prompt)
            }
            Handler(Looper.getMainLooper()).post {
                result.onSuccess {
                    MinisCollaborationDiagnostics.record(context, "start_accepted")
                    openBoard(context)
                }
                    .onFailure { error ->
                        MinisCollaborationDiagnostics.record(context, "start_failed", error.message.orEmpty())
                        restorePrompt(prompt)
                        Toast.makeText(
                            context,
                            "启动三智能体协作失败：${error.message ?: "unknown"}，请重新发送",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }.apply {
            name = "minis-collaboration-start"
            isDaemon = true
            start()
        }
        return true
    }

    fun openBoard(context: Context) {
        val intent = Intent().apply {
            setClassName(context.packageName, "com.codex.mobile.CollaborationActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun ensureHostService(context: Context) {
        val intent = Intent().setClassName(context.packageName, HOST_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun ensureServerReady(context: Context) {
        if (isServerReady(context)) return
        ensureHostService(context)
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            if (isServerReady(context)) return
            Thread.sleep(500)
        }
        throw IOException("协作服务未能在60秒内启动")
    }

    private fun isServerReady(context: Context): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:$SERVER_PORT/host-api/health")
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
            val payload = JSONObject(raw)
            payload.optString("bundleId") == expectedBundleId(context)
                && payload.optString("collaborationProtocol") == COLLABORATION_PROTOCOL_ID
                && payload.optBoolean("claudeCollaborationReady", false)
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun expectedBundleId(context: Context): String {
        val embedded = runCatching {
            context.assets.open("server-bundle/bundle-id")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText().trim() }
        }.getOrDefault("")
        if (embedded.isNotBlank()) return embedded
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                .removeSuffix("-beta")
        }.getOrDefault("")
    }

    private fun start(context: Context, prompt: String): JSONObject {
        var lastError: IOException? = null
        repeat(3) { attempt ->
            try {
                return startOnce(prompt)
            } catch (error: IOException) {
                lastError = error
                ensureHostService(context)
                if (attempt < 2) Thread.sleep(700L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("协作服务连接失败")
    }

    private fun startOnce(prompt: String): JSONObject {
        val body = JSONObject().put("leader", "minis").put("prompt", prompt.trim()).toString()
        val connection = URL("http://127.0.0.1:$SERVER_PORT/collaboration-api/start")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { it.write(body) }
            }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException(
                    runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                        .ifBlank { "协作服务HTTP $status" },
                )
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

private object MinisCollaborationDiagnostics {
    @Synchronized
    fun record(context: Context, event: String, detail: String = "") {
        if (!context.packageName.endsWith(".beta")) return
        runCatching {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "口袋大龙虾本地归档/诊断",
            ).apply { mkdirs() }
            val target = File(directory, "三智能体协作Minis客户端诊断.jsonl")
            val payload = JSONObject()
                .put("timestampMs", System.currentTimeMillis())
                .put("event", event)
                .put("detail", detail.take(500))
                .put("packageName", context.packageName)
            FileOutputStream(target, true).bufferedWriter().use { writer ->
                writer.append(payload.toString()).append('\n')
            }
        }
    }
}
