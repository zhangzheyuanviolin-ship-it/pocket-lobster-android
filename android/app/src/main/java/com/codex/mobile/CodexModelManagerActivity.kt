package com.codex.mobile

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

class CodexModelManagerActivity : AppCompatActivity() {
    private data class ProviderProbe(
        val protocol: String,
        val reportedModel: String,
        val message: String,
    )

    private class ProviderHttpException(val statusCode: Int, val responseBody: String) :
        IllegalStateException("HTTP $statusCode：${responseBody.take(240)}")

    private lateinit var btnRefresh: Button
    private lateinit var btnCreate: Button
    private lateinit var btnTest: Button
    private lateinit var btnFetch: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var listView: ListView
    private var rows: List<CodexModelConfig> = emptyList()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_codex_model_manager)
        btnRefresh = findViewById(R.id.btnCodexModelRefresh)
        btnCreate = findViewById(R.id.btnCodexModelCreate)
        btnTest = findViewById(R.id.btnCodexModelTest)
        btnFetch = findViewById(R.id.btnCodexModelFetch)
        progress = findViewById(R.id.progressCodexModel)
        status = findViewById(R.id.tvCodexModelStatus)
        listView = findViewById(R.id.listCodexModels)

        btnRefresh.setOnClickListener { refresh() }
        btnCreate.setOnClickListener { showEditDialog(null) }
        btnTest.setOnClickListener { CodexModelConfigStore.loadCurrent(this)?.let(::testConnection) }
        btnFetch.setOnClickListener { CodexModelConfigStore.loadCurrent(this)?.let(::fetchModels) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        rows = CodexModelConfigStore.loadConfigs(this)
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(this@CodexModelManagerActivity)
                    .inflate(R.layout.item_agent_model_row, parent, false)
                val row = rows[position]
                view.findViewById<TextView>(R.id.tvAgentModelRowTitle).text =
                    (if (row.isDefault) "当前：" else "") + row.displayName
                val verification = when (row.verificationStatus) {
                    "verified" -> "已验证"
                    "route_failed" -> "上游可用，但Codex路由失败"
                    "failed" -> "连接失败"
                    else -> "尚未验证"
                }
                val protocol = "原生Responses"
                view.findViewById<TextView>(R.id.tvAgentModelRowMeta).text =
                    "${row.modelId} | $protocol\n${row.baseUrl}\n状态：$verification" +
                        (if (row.verifiedModel.isNotEmpty()) "，上游返回${row.verifiedModel}" else "") +
                        (if (row.verificationMessage.isNotEmpty()) "\n${row.verificationMessage}" else "")
                view.findViewById<Button>(R.id.btnAgentModelRowSelect).setOnClickListener { select(row) }
                view.findViewById<Button>(R.id.btnAgentModelRowEdit).setOnClickListener { showEditDialog(row) }
                view.findViewById<Button>(R.id.btnAgentModelRowDelete).setOnClickListener { confirmDelete(row) }
                view.setOnClickListener { showEditDialog(row) }
                return view
            }
        }
        val current = rows.firstOrNull { it.isDefault }
        status.text = when {
            rows.isEmpty() -> "暂无Codex第三方模型配置"
            current == null -> "共${rows.size}条配置，尚未选中当前模型"
            else -> "当前：${current.displayName}，${current.modelId}，${verificationLabel(current)}"
        }
        btnTest.isEnabled = !busy && current != null
        btnFetch.isEnabled = !busy && current != null
    }

    private fun showEditDialog(existing: CodexModelConfig?) {
        val nameInput = EditText(this).apply {
            hint = "显示名称"
            setText(existing?.displayName.orEmpty())
        }
        val baseUrlInput = EditText(this).apply {
            hint = "Base URL，例如 https://api.deepseek.com/v1"
            setText(existing?.baseUrl.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val modelInput = EditText(this).apply {
            hint = "模型ID"
            setText(existing?.modelId.orEmpty())
        }
        val keyInput = EditText(this).apply {
            hint = if (existing == null) "API密钥" else "API密钥，留空表示保持不变"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput)
            addView(baseUrlInput)
            addView(modelInput)
            addView(keyInput)
            addView(TextView(this@CodexModelManagerActivity).apply {
                text = "推理强度请在聊天输入框下方实时选择；保存时会执行上游原生Responses生成和Codex真实路由验证。"
            })
        }
        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新增Codex模型" else "编辑Codex模型")
            .setView(scroll)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.prompt_save_text), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
                val modelId = modelInput.text.toString().trim()
                val enteredApiKey = keyInput.text.toString().trim()
                if (!isAllowedBaseUrl(baseUrl)) {
                    baseUrlInput.error = "仅允许HTTPS地址，或本机HTTP地址"
                    return@setOnClickListener
                }
                if (modelId.isEmpty()) {
                    modelInput.error = "模型ID必填"
                    return@setOnClickListener
                }
                if (existing == null && enteredApiKey.isEmpty()) {
                    keyInput.error = "API密钥必填"
                    return@setOnClickListener
                }
                val id = existing?.id ?: CodexModelConfigStore.createId()
                val draft = CodexModelConfig(
                    id = id,
                    providerId = existing?.providerId ?: "pocket_$id",
                    displayName = nameInput.text.toString().trim().ifEmpty { modelId },
                    baseUrl = baseUrl,
                    modelId = modelId,
                    supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh"),
                    upstreamProtocol = existing?.upstreamProtocol ?: "responses",
                    verificationStatus = "unknown",
                    lastVerifiedAt = "",
                    verifiedModel = "",
                    verificationMessage = "等待保存验证",
                    isDefault = existing?.isDefault ?: (rows.none { it.isDefault }),
                )
                runBusy("正在验证上游并配置Codex路由…", {
                    val apiKey = enteredApiKey.ifBlank {
                        CodexModelConfigStore.loadApiKey(this, draft.id)
                    }
                    val verified = verifyAndSave(draft, apiKey, enteredApiKey.ifBlank { null })
                    "已保存：${verified.displayName}，${protocolLabel(verified.upstreamProtocol)}，Codex路由验证通过"
                }) {
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun select(config: CodexModelConfig) {
        runBusy("正在验证并切换Codex模型…", {
            val previousCurrent = CodexModelConfigStore.loadCurrent(this)
            val apiKey = CodexModelConfigStore.loadApiKey(this, config.id)
            val probe = probeProvider(config, apiKey)
            val verified = config.withProbe(probe).copy(isDefault = false)
            prepareAndVerifyRoute(verified)
            try {
                CodexModelConfigStore.saveConfig(this, verified, null)
                CodexModelConfigStore.setDefault(this, verified.id)
                writeProviderConfig(verified, true)
                verifyCodexConfigRoute(verified)
            } catch (error: Exception) {
                if (previousCurrent != null) {
                    CodexModelConfigStore.setDefault(this, previousCurrent.id)
                    writeProviderConfig(previousCurrent, true)
                    reloadProviderSecrets()
                } else {
                    CodexModelConfigStore.saveConfig(this, verified.copy(isDefault = false), null)
                }
                throw error
            }
            "已选中并验证实际路由：${verified.displayName}，${probe.reportedModel}"
        })
    }

    private fun testConnection(config: CodexModelConfig) {
        runBusy("正在执行上游与Codex端到端测试…", {
            val apiKey = CodexModelConfigStore.loadApiKey(this, config.id)
            val probe = probeProvider(config, apiKey)
            val verified = config.withProbe(probe)
            CodexModelConfigStore.saveConfig(this, verified, null)
            try {
                prepareAndVerifyRoute(verified)
                if (verified.isDefault) {
                    writeProviderConfig(verified, true)
                    verifyCodexConfigRoute(verified)
                }
            } catch (error: Exception) {
                CodexModelConfigStore.saveConfig(
                    this,
                    verified.copy(
                        verificationStatus = "route_failed",
                        verificationMessage = "上游Responses成功，但Codex路由失败：${error.message.orEmpty().take(240)}",
                    ),
                    null,
                )
                throw error
            }
            "连接与真实Codex生成均成功：${probe.reportedModel}，${protocolLabel(probe.protocol)}"
        })
    }

    private fun verifyAndSave(
        draft: CodexModelConfig,
        apiKey: String,
        apiKeyToPersist: String?,
    ): CodexModelConfig {
        val probe = probeProvider(draft, apiKey)
        val verified = draft.withProbe(probe)
        val previous = rows.firstOrNull { it.id == draft.id }
        val previousApiKey = previous?.let { CodexModelConfigStore.loadApiKey(this, it.id) }.orEmpty()
        val staged = verified.copy(isDefault = false)
        CodexModelConfigStore.saveConfig(this, staged, apiKeyToPersist)
        try {
            prepareAndVerifyRoute(staged)
            if (verified.isDefault) {
                CodexModelConfigStore.setDefault(this, verified.id)
                writeProviderConfig(verified, true)
                verifyCodexConfigRoute(verified)
            }
            return verified
        } catch (error: Exception) {
            if (previous != null) {
                CodexModelConfigStore.saveConfig(this, previous, previousApiKey.ifBlank { null })
                writeProviderConfig(previous, previous.isDefault)
            } else {
                CodexModelConfigStore.deleteConfig(this, staged.id)
                clearProviderConfig(staged)
            }
            reloadProviderSecrets()
            throw error
        }
    }

    private fun CodexModelConfig.withProbe(probe: ProviderProbe): CodexModelConfig {
        return copy(
            upstreamProtocol = probe.protocol,
            verificationStatus = "verified",
            lastVerifiedAt = isoNow(),
            verifiedModel = probe.reportedModel,
            verificationMessage = probe.message,
            supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh"),
        )
    }

    private fun prepareAndVerifyRoute(config: CodexModelConfig) {
        writeProviderConfig(config, false)
        reloadProviderSecrets()
        verifyCodexGenerationRoute(config)
    }

    private fun probeProvider(config: CodexModelConfig, apiKey: String): ProviderProbe {
        if (apiKey.isBlank()) throw IllegalStateException("API密钥不存在，请编辑后重新保存")
        val responsesBody = JSONObject()
            .put("model", config.modelId)
            .put("input", "Reply with exactly POCKET_LOBSTER_OK.")
            .put("reasoning", JSONObject().put("effort", "none"))
            .put("max_output_tokens", 64)
            .put("stream", false)
        try {
            val result = requestProvider(config, "responses", "POST", responsesBody, apiKey)
            if (result.optString("status") != "completed") throw IllegalStateException("Responses生成未正常完成")
            val output = readResponsesOutputText(result)
            if (!output.contains("POCKET_LOBSTER_OK")) throw IllegalStateException("Responses端点未返回验证文本")
            val reported = result.optString("model").trim().ifEmpty { config.modelId }
            if (reported != config.modelId) throw IllegalStateException("上游返回模型不一致：$reported")
            return ProviderProbe("responses", reported, "Responses真实生成验证成功")
        } catch (responsesError: Exception) {
            if (responsesError is ProviderHttpException && responsesError.statusCode in setOf(401, 403)) {
                throw IllegalStateException("API密钥认证失败：${responsesError.message}")
            }
            throw IllegalStateException("原生Responses连接失败：${responsesError.message.orEmpty()}", responsesError)
        }
    }

    private fun readResponsesOutputText(result: JSONObject): String {
        val texts = mutableListOf<String>()
        val output = result.optJSONArray("output") ?: JSONArray()
        for (itemIndex in 0 until output.length()) {
            val item = output.optJSONObject(itemIndex) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (partIndex in 0 until content.length()) {
                val text = content.optJSONObject(partIndex)?.optString("text").orEmpty()
                if (text.isNotBlank()) texts += text
            }
        }
        return texts.joinToString("\n")
    }

    private fun confirmDelete(config: CodexModelConfig) {
        AlertDialog.Builder(this)
            .setTitle("删除Codex模型")
            .setMessage("确定删除${config.displayName}吗？")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.conversation_action_delete)) { _, _ ->
                runBusy("正在删除Codex模型…", {
                    clearProviderConfig(config)
                    CodexModelConfigStore.deleteConfig(this, config.id)
                    CodexModelConfigStore.loadCurrent(this)?.let { writeProviderConfig(it, true) }
                    reloadProviderSecrets()
                    "已删除${config.displayName}"
                })
            }
            .show()
    }

    private fun writeProviderConfig(config: CodexModelConfig, select: Boolean) {
        val encodedId = URLEncoder.encode(config.id, "UTF-8")
        val adapterBaseUrl =
            "http://127.0.0.1:${CodexServerManager.SERVER_PORT}/codex-provider-adapter/$encodedId/v1"
        val provider = JSONObject()
            .put("name", config.displayName)
            .put("base_url", adapterBaseUrl)
            .put("wire_api", "responses")
            .put("env_key", CodexModelConfigStore.environmentKey(config.id))
        val edits = JSONArray().put(configEdit("model_providers.${config.providerId}", provider, "upsert"))
        if (select) {
            edits.put(configEdit("model_provider", config.providerId, "replace"))
            edits.put(configEdit("model", config.modelId, "replace"))
        }
        LocalBridgeClients.callCodexRpc("config/batchWrite", JSONObject().put("edits", edits))
    }

    private fun clearProviderConfig(config: CodexModelConfig) {
        val edits = JSONArray().put(configEdit("model_providers.${config.providerId}", JSONObject.NULL, "replace"))
        if (config.isDefault) {
            edits.put(configEdit("model_provider", JSONObject.NULL, "replace"))
            edits.put(configEdit("model", JSONObject.NULL, "replace"))
        }
        LocalBridgeClients.callCodexRpc("config/batchWrite", JSONObject().put("edits", edits))
    }

    private fun configEdit(keyPath: String, value: Any, strategy: String): JSONObject {
        return JSONObject().put("keyPath", keyPath).put("value", value).put("mergeStrategy", strategy)
    }

    private fun reloadProviderSecrets() {
        CodexModelConfigStore.writeSecretHandoff(this)
        val result = callLocalApi("/codex-api/model-providers/reload", JSONObject())
        if (!result.optBoolean("ok", false)) throw IllegalStateException("Codex密钥重载失败")
    }

    private fun verifyCodexConfigRoute(config: CodexModelConfig) {
        val result = LocalBridgeClients.callCodexRpc("config/read")
        val active = result.optJSONObject("config") ?: throw IllegalStateException("Codex未返回配置状态")
        val provider = active.optString("model_provider")
        val model = active.optString("model")
        if (provider != config.providerId || model != config.modelId) {
            throw IllegalStateException("Codex实际路由不一致：$provider / $model")
        }
    }

    private fun verifyCodexGenerationRoute(config: CodexModelConfig) {
        val body = JSONObject().put("providerId", config.providerId).put("model", config.modelId)
        val result = callLocalApi("/codex-api/model-providers/end-to-end-test", body, 135_000)
        if (!result.optBoolean("ok", false)) throw IllegalStateException("Codex端到端生成验证失败")
        val runtime = result.optJSONObject("runtime")
        if (runtime != null && !runtime.optBoolean("success", false)) {
            throw IllegalStateException("上游实际请求没有成功")
        }
        val reportedModel = runtime?.optString("reportedModel").orEmpty()
        if (reportedModel.isNotEmpty() && reportedModel != config.modelId) {
            throw IllegalStateException("上游实际返回模型不一致：$reportedModel")
        }
    }

    private fun fetchModels(config: CodexModelConfig) {
        runBusy("正在读取提供商模型列表…", {
            val apiKey = CodexModelConfigStore.loadApiKey(this, config.id)
            val result = requestProvider(config, "models", "GET", null, apiKey)
            val data = result.optJSONArray("data") ?: throw IllegalStateException("提供商未返回data模型列表")
            val ids = mutableListOf<String>()
            for (index in 0 until data.length()) {
                val id = data.optJSONObject(index)?.optString("id")?.trim().orEmpty()
                if (id.isNotEmpty() && id !in ids) ids += id
            }
            if (ids.isEmpty()) throw IllegalStateException("提供商模型列表为空")
            runOnUiThread { showFetchedModels(config, ids) }
            "已读取${ids.size}个模型；请选择后将重新执行真实验证"
        })
    }

    private fun showFetchedModels(config: CodexModelConfig, ids: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("选择模型")
            .setItems(ids.toTypedArray()) { _, index ->
                val updated = config.copy(
                    modelId = ids[index],
                    displayName = "${config.displayName.substringBefore(" / ")} / ${ids[index]}",
                    verificationStatus = "unknown",
                    verifiedModel = "",
                    verificationMessage = "模型ID已变更，等待验证",
                )
                runBusy("正在验证并保存模型…", {
                    val apiKey = CodexModelConfigStore.loadApiKey(this, updated.id)
                    val verified = verifyAndSave(updated, apiKey, null)
                    "已选择并验证${verified.modelId}"
                })
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun requestProvider(
        config: CodexModelConfig,
        path: String,
        method: String,
        body: JSONObject?,
        apiKey: String,
    ): JSONObject {
        if (apiKey.isBlank()) throw IllegalStateException("API密钥不存在，请编辑后重新保存")
        val connection = (URL("${config.baseUrl.trimEnd('/')}/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = body != null
        }
        if (body != null) {
            connection.outputStream.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        val code = connection.responseCode
        val raw = try {
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
        if (code !in 200..299) throw ProviderHttpException(code, raw)
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw IllegalStateException("提供商返回的不是有效JSON")
        }
    }

    private fun callLocalApi(path: String, body: JSONObject, readTimeoutMs: Int = 20_000): JSONObject {
        val url = URL("http://127.0.0.1:${CodexServerManager.SERVER_PORT}$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        connection.outputStream.use { OutputStreamWriter(it, Charsets.UTF_8).use { writer -> writer.write(body.toString()) } }
        val code = connection.responseCode
        val raw = try {
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
        if (code !in 200..299) throw IllegalStateException("本地Codex服务HTTP $code：${raw.take(500)}")
        return JSONObject(raw)
    }

    private fun runBusy(message: String, block: () -> String, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        progress.visibility = View.VISIBLE
        status.text = message
        listOf(btnCreate, btnRefresh, btnTest, btnFetch).forEach { it.isEnabled = false }
        Thread {
            val result = runCatching(block)
            runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                btnCreate.isEnabled = true
                btnRefresh.isEnabled = true
                refresh()
                result.onSuccess { successMessage ->
                    status.text = successMessage
                    Toast.makeText(this, successMessage, Toast.LENGTH_LONG).show()
                    onSuccess()
                }.onFailure { error ->
                    status.text = "操作未完成：${error.message.orEmpty()}"
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun isAllowedBaseUrl(raw: String): Boolean {
        return runCatching {
            val uri = URI(raw)
            val host = uri.host.orEmpty().lowercase()
            uri.scheme == "https" || (uri.scheme == "http" && host in setOf("127.0.0.1", "localhost", "::1"))
        }.getOrDefault(false)
    }

    private fun verificationLabel(config: CodexModelConfig): String {
        return when (config.verificationStatus) {
            "verified" -> "已验证，${protocolLabel(config.upstreamProtocol)}"
            "route_failed" -> "上游已连接，但Codex路由失败"
            "failed" -> "连接失败"
            else -> "尚未验证"
        }
    }

    private fun protocolLabel(protocol: String): String {
        return "原生Responses"
    }

    private fun isoNow(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
