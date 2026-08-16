package com.codex.mobile

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class CodexModelManagerActivity : AppCompatActivity() {
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
                    (if (row.isDefault) "✓ " else "") + row.displayName
                view.findViewById<TextView>(R.id.tvAgentModelRowMeta).text =
                    "${row.modelId} | Responses\n${row.baseUrl}\n推理强度：${row.supportedReasoningEfforts.joinToString("、")}"
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
            current != null -> "当前：${current.displayName}，共${rows.size}条配置"
            else -> "共${rows.size}条配置"
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
            hint = "Base URL，例如 https://example.com/v1"
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
        val low = effortCheckBox("低", "low", existing)
        val medium = effortCheckBox("中", "medium", existing)
        val high = effortCheckBox("高", "high", existing)
        val xhigh = effortCheckBox("超高", "xhigh", existing)
        val setDefault = CheckBox(this).apply {
            text = "设为当前Codex模型"
            isChecked = existing?.isDefault ?: true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput)
            addView(baseUrlInput)
            addView(modelInput)
            addView(keyInput)
            addView(TextView(this@CodexModelManagerActivity).apply { text = "模型支持的推理强度" })
            addView(low)
            addView(medium)
            addView(high)
            addView(xhigh)
            addView(setDefault)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新增Codex模型" else "编辑Codex模型")
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.prompt_save_text), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
                val modelId = modelInput.text.toString().trim()
                val apiKey = keyInput.text.toString().trim()
                if (!isAllowedBaseUrl(baseUrl)) {
                    baseUrlInput.error = "仅允许HTTPS地址，或本机HTTP地址"
                    return@setOnClickListener
                }
                if (modelId.isEmpty()) {
                    modelInput.error = "模型ID必填"
                    return@setOnClickListener
                }
                if (existing == null && apiKey.isEmpty()) {
                    keyInput.error = "API密钥必填"
                    return@setOnClickListener
                }
                val efforts = listOf(low, medium, high, xhigh)
                    .filter { it.isChecked }
                    .map { it.tag.toString() }
                if (efforts.isEmpty()) {
                    Toast.makeText(this, "请至少选择一个推理强度", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val id = existing?.id ?: CodexModelConfigStore.createId()
                val config = CodexModelConfig(
                    id = id,
                    providerId = existing?.providerId ?: "pocket_$id",
                    displayName = nameInput.text.toString().trim().ifEmpty { modelId },
                    baseUrl = baseUrl,
                    modelId = modelId,
                    supportedReasoningEfforts = efforts,
                    isDefault = setDefault.isChecked || rows.isEmpty(),
                )
                runBusy("正在保存Codex模型配置…") {
                    CodexModelConfigStore.saveConfig(this, config, apiKey.ifBlank { null })
                    reloadProviderSecrets()
                    writeProviderConfig(config, config.isDefault)
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun effortCheckBox(label: String, value: String, existing: CodexModelConfig?): CheckBox {
        return CheckBox(this).apply {
            text = label
            tag = value
            isChecked = existing?.supportedReasoningEfforts?.contains(value) ?: true
        }
    }

    private fun select(config: CodexModelConfig) {
        runBusy("正在切换Codex模型…") {
            CodexModelConfigStore.setDefault(this, config.id)
            reloadProviderSecrets()
            writeProviderConfig(config, true)
        }
    }

    private fun confirmDelete(config: CodexModelConfig) {
        AlertDialog.Builder(this)
            .setTitle("删除Codex模型")
            .setMessage("确定删除${config.displayName}吗？")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.conversation_action_delete)) { _, _ ->
                runBusy("正在删除Codex模型…") {
                    clearProviderConfig(config)
                    CodexModelConfigStore.deleteConfig(this, config.id)
                    reloadProviderSecrets()
                    CodexModelConfigStore.loadCurrent(this)?.let { writeProviderConfig(it, true) }
                }
            }
            .show()
    }

    private fun writeProviderConfig(config: CodexModelConfig, select: Boolean) {
        val provider = JSONObject()
            .put("name", config.displayName)
            .put("base_url", config.baseUrl)
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
        val url = URL("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/codex-api/model-providers/reload")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { OutputStreamWriter(it).use { writer -> writer.write("{}") } }
        val code = connection.responseCode
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("Codex密钥重载失败：HTTP $code")
    }

    private fun testConnection(config: CodexModelConfig) {
        runBusy("正在测试Responses连接…") {
            val apiKey = CodexModelConfigStore.loadApiKey(this, config.id)
            val body = JSONObject()
                .put("model", config.modelId)
                .put("input", "Reply with OK.")
                .put("max_output_tokens", 32)
                .put("stream", false)
            val result = requestProvider(config, "responses", "POST", body, apiKey)
            if (!result.has("id") && !result.has("output") && !result.has("output_text")) {
                throw IllegalStateException("提供商未返回有效Responses结果")
            }
        }
    }

    private fun fetchModels(config: CodexModelConfig) {
        runBusy("正在读取提供商模型列表…") {
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
        }
    }

    private fun showFetchedModels(config: CodexModelConfig, ids: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("选择模型")
            .setItems(ids.toTypedArray()) { _, index ->
                val updated = config.copy(modelId = ids[index], displayName = "${config.displayName.substringBefore(" / ")} / ${ids[index]}")
                runBusy("正在保存模型…") {
                    CodexModelConfigStore.saveConfig(this, updated, null)
                    writeProviderConfig(updated, updated.isDefault)
                }
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
        if (code !in 200..299) throw IllegalStateException("提供商返回HTTP $code：${raw.take(240)}")
        return JSONObject(raw)
    }

    private fun runBusy(message: String, block: () -> Unit) {
        if (busy) return
        busy = true
        progress.visibility = View.VISIBLE
        status.text = message
        btnCreate.isEnabled = false
        btnRefresh.isEnabled = false
        Thread {
            val error = runCatching(block).exceptionOrNull()
            runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                btnCreate.isEnabled = true
                btnRefresh.isEnabled = true
                refresh()
                if (error == null) {
                    Toast.makeText(this, "操作成功", Toast.LENGTH_SHORT).show()
                } else {
                    status.text = "操作失败：${error.message.orEmpty()}"
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
}
