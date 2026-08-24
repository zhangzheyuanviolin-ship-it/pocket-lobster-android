package com.codex.mobile

import android.content.Context
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
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
    fun listRuns(): JSONObject = request("GET", "/collaboration-api/runs")

    fun start(leader: String, prompt: String): JSONObject = request(
        method = "POST",
        path = "/collaboration-api/start",
        body = JSONObject().put("leader", leader).put("prompt", prompt.trim()),
        readTimeoutMs = 45_000,
    )

    fun abort(runId: String): JSONObject = request(
        method = "POST",
        path = "/collaboration-api/abort",
        body = JSONObject().put("runId", runId.trim()),
        readTimeoutMs = 45_000,
    )

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        readTimeoutMs: Int = 20_000,
    ): JSONObject {
        val connection = URL("http://127.0.0.1:${CodexServerManager.SERVER_PORT}$path")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
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
            JSONObject(raw.ifBlank { "{}" })
        } finally {
            connection.disconnect()
        }
    }
}
