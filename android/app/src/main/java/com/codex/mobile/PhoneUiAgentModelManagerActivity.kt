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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class PhoneUiAgentModelManagerActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private var rows: List<PhoneUiModelConfig> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_model_manager)
        findViewById<TextView>(R.id.tvAgentModelManagerTitle).text = "手机操作智能体模型管理"
        findViewById<TextView>(R.id.tvAgentModelManagerSubtitle).text =
            "配置支持图片输入的模型。连接测试会发送内置截图并验证模型能返回真实可解析动作。"
        listView = findViewById(R.id.listAgentModels)
        statusView = findViewById(R.id.tvAgentModelStatus)
        findViewById<Button>(R.id.btnAgentModelRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnAgentModelCreate).setOnClickListener { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        rows = PhoneUiAgentModelStore.loadConfigs(this)
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(this@PhoneUiAgentModelManagerActivity)
                    .inflate(R.layout.item_agent_model_row, parent, false)
                val row = rows[position]
                view.findViewById<TextView>(R.id.tvAgentModelRowTitle).text =
                    if (row.isDefault) "已选中，${row.displayName}" else row.displayName
                view.findViewById<TextView>(R.id.tvAgentModelRowMeta).text =
                    "${row.modelId}；${row.protocol.value}\n${row.baseUrl}"
                view.findViewById<Button>(R.id.btnAgentModelRowSelect).apply {
                    text = if (row.isDefault) "已选中" else "选中"
                    isEnabled = !row.isDefault
                    setOnClickListener {
                        PhoneUiAgentModelStore.setDefault(this@PhoneUiAgentModelManagerActivity, row.id)
                        refresh()
                        Toast.makeText(this@PhoneUiAgentModelManagerActivity, "已切换手机操作模型", Toast.LENGTH_SHORT).show()
                    }
                }
                view.findViewById<Button>(R.id.btnAgentModelRowEdit).apply {
                    text = "编辑和测试"
                    contentDescription = "编辑和测试${row.displayName}"
                    setOnClickListener { showEditDialog(row) }
                }
                view.findViewById<Button>(R.id.btnAgentModelRowDelete).apply {
                    contentDescription = "删除${row.displayName}"
                    setOnClickListener { confirmDelete(row) }
                }
                return view
            }
        }
        statusView.text = if (rows.isEmpty()) "尚未配置手机操作智能体模型" else "共${rows.size}条模型配置"
    }

    private fun confirmDelete(config: PhoneUiModelConfig) {
        AlertDialog.Builder(this)
            .setTitle("删除手机操作模型")
            .setMessage("确定删除${config.displayName}吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                PhoneUiAgentModelStore.delete(this, config.id)
                refresh()
            }
            .show()
    }

    private fun showEditDialog(existing: PhoneUiModelConfig?) {
        val presets = PhoneUiAgentModelStore.presets()
        val presetLabels = listOf("智谱 AutoGLM Phone", "自定义视觉语言模型")
        val protocolLabels = PhoneUiModelProtocol.entries.map {
            when (it) {
                PhoneUiModelProtocol.AUTOGLM_NATIVE -> "AutoGLM 原生协议"
                PhoneUiModelProtocol.GENERIC_JSON -> "通用 JSON 动作协议"
            }
        }
        var selectedPreset = if (existing?.protocol == PhoneUiModelProtocol.GENERIC_JSON) 1 else 0
        var selectedProtocol = existing?.protocol ?: PhoneUiModelProtocol.AUTOGLM_NATIVE
        val presetButton = Button(this)
        val protocolButton = Button(this)
        fun updateChoiceLabels() {
            presetButton.text = "提供商预设：${presetLabels[selectedPreset]}"
            presetButton.contentDescription = "提供商预设，当前为${presetLabels[selectedPreset]}，点击更改"
            protocolButton.text = "动作协议：${protocolLabels[selectedProtocol.ordinal]}"
            protocolButton.contentDescription = "动作协议，当前为${protocolLabels[selectedProtocol.ordinal]}，点击更改"
        }
        val nameInput = textInput("显示名称", existing?.displayName.orEmpty())
        val baseUrlInput = textInput("Base URL", existing?.baseUrl.orEmpty())
        val modelIdInput = textInput("模型ID", existing?.modelId.orEmpty())
        val apiKeyInput = textInput("API密钥", existing?.apiKey.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val defaultCheck = CheckBox(this).apply {
            text = "设为当前模型"
            isChecked = existing?.isDefault ?: true
        }
        if (existing == null) {
            val preset = presets[selectedPreset]
            nameInput.setText(preset.displayName)
            baseUrlInput.setText(preset.baseUrl)
            modelIdInput.setText(preset.modelId)
        }
        presetButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("选择提供商预设")
                .setSingleChoiceItems(presetLabels.toTypedArray(), selectedPreset) { choiceDialog, which ->
                    selectedPreset = which
                    selectedProtocol = presets[which].protocol
                    val preset = presets[which]
                    nameInput.setText(preset.displayName)
                    baseUrlInput.setText(preset.baseUrl)
                    modelIdInput.setText(preset.modelId)
                    updateChoiceLabels()
                    choiceDialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        protocolButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("选择动作协议")
                .setSingleChoiceItems(protocolLabels.toTypedArray(), selectedProtocol.ordinal) { choiceDialog, which ->
                    selectedProtocol = PhoneUiModelProtocol.entries[which]
                    updateChoiceLabels()
                    choiceDialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        updateChoiceLabels()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (18 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(presetButton)
            addView(protocolButton)
            addView(nameInput)
            addView(baseUrlInput)
            addView(modelIdInput)
            addView(apiKeyInput)
            addView(defaultCheck)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建手机操作模型" else "编辑手机操作模型")
            .setView(container)
            .setNegativeButton("取消", null)
            .setNeutralButton("测试连接", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            fun draft(): PhoneUiModelConfig? {
                val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
                val modelId = modelIdInput.text.toString().trim()
                val apiKey = apiKeyInput.text.toString().trim()
                if (baseUrl.isEmpty()) { baseUrlInput.error = "Base URL必填"; return null }
                if (modelId.isEmpty()) { modelIdInput.error = "模型ID必填"; return null }
                if (apiKey.isEmpty()) { apiKeyInput.error = "API密钥必填"; return null }
                return PhoneUiModelConfig(
                    id = existing?.id ?: PhoneUiAgentModelStore.createId(),
                    displayName = nameInput.text.toString().trim().ifEmpty { modelId },
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    modelId = modelId,
                    protocol = selectedProtocol,
                    isDefault = defaultCheck.isChecked,
                )
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val config = draft() ?: return@setOnClickListener
                PhoneUiAgentModelStore.save(this, config)
                refresh()
                dialog.dismiss()
                Toast.makeText(this, "手机操作模型已保存", Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val config = draft() ?: return@setOnClickListener
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
                Thread {
                    val result = runCatching { PhoneUiAgentModelClient.probe(config) }
                    runOnUiThread {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
                        Toast.makeText(
                            this,
                            result.getOrElse { "连接测试失败：${it.message}" },
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun textInput(hintText: String, value: String) = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT
        contentDescription = hintText
    }
}
