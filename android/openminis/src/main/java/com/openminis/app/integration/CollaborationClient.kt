package com.openminis.app.integration

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.OutputStreamWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object CollaborationClient {
    private const val PREFS = "pocket_lobster_collaboration"
    private const val KEY_ENABLED = "enabled_minis"
    private const val SERVER_PORT = 18923
    private const val HOST_SERVICE = "com.codex.mobile.CodexForegroundService"

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
                ensureServerReady(context)
                start(context, prompt)
            }
            Handler(Looper.getMainLooper()).post {
                result.onSuccess { openBoard(context) }
                    .onFailure { error ->
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
        if (isServerReady()) return
        ensureHostService(context)
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            if (isServerReady()) return
            Thread.sleep(500)
        }
        throw IOException("协作服务未能在60秒内启动")
    }

    private fun isServerReady(): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:$SERVER_PORT/collaboration-api/runs")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 1_200
            connection.readTimeout = 1_200
            connection.responseCode in 200..399
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

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
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
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
            JSONObject(raw.ifBlank { "{}" })
        } finally {
            connection.disconnect()
        }
    }
}
