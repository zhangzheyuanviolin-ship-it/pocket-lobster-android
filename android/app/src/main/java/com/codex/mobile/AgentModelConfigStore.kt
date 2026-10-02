package com.codex.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ExternalAgentId(val value: String) {
    CLAUDE_CODE("claude-code"),
}

enum class ProviderProtocol(val value: String) {
    ANTHROPIC("anthropic"),
    OPENAI_COMPATIBLE("openai-compatible"),
}

data class ProviderPreset(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val note: String = "",
)

data class AgentModelConfig(
    val id: String,
    val agentId: ExternalAgentId,
    val displayName: String,
    val providerId: String,
    val providerName: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val isDefault: Boolean,
    val contextWindowTokens: Int = 0,
    val autoCompactWindowTokens: Int = 0,
    val maxOutputTokens: Int = 0,
    val effortLevel: String = "default",
    val maxThinkingTokens: Int = 0,
)

object AgentModelConfigStore {
    private const val PREFS_NAME = "agent_model_configs"
    private const val KEY_CONFIGS_JSON = "configs_json"
    private const val PUBLIC_STATE_FILE = "claude-model-configs.json"
    private const val RUNTIME_OVERRIDE_FILE = "claude-runtime-overrides.json"

    fun presetsFor(agentId: ExternalAgentId): List<ProviderPreset> {
        return when (agentId) {
            ExternalAgentId.CLAUDE_CODE -> listOf(
                ProviderPreset(
                    id = "anthropic_official",
                    name = "Anthropic 官方",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "https://api.anthropic.com",
                    note = "Claude Code 官方 Anthropic 接口",
                ),
                ProviderPreset(
                    id = "aliyun_coding_plan_cn",
                    name = "阿里云 Coding Plan（中国）",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "https://coding.dashscope.aliyuncs.com/apps/anthropic",
                    note = "官方文档给出的 Claude Code Coding Plan 地址",
                ),
                ProviderPreset(
                    id = "aliyun_coding_plan_sg",
                    name = "阿里云 Coding Plan（新加坡）",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "https://coding-intl.dashscope.aliyuncs.com/apps/anthropic",
                    note = "根据阿里云地域规则与域名连通性验证推断",
                ),
                ProviderPreset(
                    id = "custom_anthropic",
                    name = "自定义 Anthropic 兼容",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "",
                    note = "可接入任意 Anthropic 兼容网关",
                ),
            )
        }
    }

    fun loadConfigs(context: Context, agentId: ExternalAgentId): List<AgentModelConfig> {
        val all = readAllConfigs(context)
        val filtered = all.filter { it.agentId == agentId }
            .sortedWith(
                compareByDescending<AgentModelConfig> { it.isDefault }
                    .thenBy { it.displayName.lowercase() },
            )
        if (agentId == ExternalAgentId.CLAUDE_CODE) writePublicState(context, all)
        return filtered
    }

    fun loadCurrentConfig(context: Context, agentId: ExternalAgentId): AgentModelConfig? {
        val list = loadConfigs(context, agentId)
        val stored = list.firstOrNull { it.isDefault } ?: list.firstOrNull() ?: return null
        if (agentId != ExternalAgentId.CLAUDE_CODE) return stored
        val override = readRuntimeOverride(context)
        val selectedId = override.optString("selectedConfigId", "").trim()
        val selected = list.firstOrNull { it.id == selectedId } ?: stored
        return selected.copy(
            contextWindowTokens = override.optInt("contextWindowTokens", selected.contextWindowTokens).coerceAtLeast(0),
            autoCompactWindowTokens = override.optInt("autoCompactWindowTokens", selected.autoCompactWindowTokens).coerceAtLeast(0),
            maxOutputTokens = override.optInt("maxOutputTokens", selected.maxOutputTokens).coerceAtLeast(0),
            effortLevel = override.optString("effortLevel", selected.effortLevel).trim().ifEmpty { selected.effortLevel },
            maxThinkingTokens = override.optInt("maxThinkingTokens", selected.maxThinkingTokens).coerceAtLeast(0),
        )
    }

    fun saveConfig(context: Context, config: AgentModelConfig) {
        val all = readAllConfigs(context).toMutableList()
        val replaced = all.indexOfFirst { it.id == config.id }

        if (config.isDefault) {
            for (index in all.indices) {
                val row = all[index]
                if (row.agentId == config.agentId && row.id != config.id && row.isDefault) {
                    all[index] = row.copy(isDefault = false)
                }
            }
        }

        if (replaced >= 0) {
            all[replaced] = config
        } else {
            all += config
        }

        val hasDefault = all.any { it.agentId == config.agentId && it.isDefault }
        if (!hasDefault) {
            val first = all.indexOfFirst { it.agentId == config.agentId }
            if (first >= 0) {
                all[first] = all[first].copy(isDefault = true)
            }
        }

        writeAllConfigs(context, all)
        writePublicState(context, all)
    }

    fun setDefault(context: Context, agentId: ExternalAgentId, configId: String) {
        val all = readAllConfigs(context).map { row ->
            if (row.agentId != agentId) return@map row
            row.copy(isDefault = row.id == configId)
        }
        writeAllConfigs(context, all)
        writePublicState(context, all)
    }

    fun deleteConfig(context: Context, configId: String) {
        val before = readAllConfigs(context)
        val target = before.firstOrNull { it.id == configId } ?: return
        val after = before.filterNot { it.id == configId }.toMutableList()

        val hasDefault = after.any { it.agentId == target.agentId && it.isDefault }
        if (!hasDefault) {
            val first = after.indexOfFirst { it.agentId == target.agentId }
            if (first >= 0) {
                after[first] = after[first].copy(isDefault = true)
            }
        }

        writeAllConfigs(context, after)
        writePublicState(context, after)
    }

    private fun readAllConfigs(context: Context): List<AgentModelConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIGS_JSON, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val output = mutableListOf<AgentModelConfig>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val agentRaw = item.optString("agentId", "").trim()
            val agentId = ExternalAgentId.entries.firstOrNull { it.value == agentRaw } ?: continue
            val protocolRaw = item.optString("protocol", "").trim()
            val protocol = ProviderProtocol.entries.firstOrNull { it.value == protocolRaw }
                ?: ProviderProtocol.OPENAI_COMPATIBLE
            val id = item.optString("id", "").trim()
            if (id.isEmpty()) continue
            output += AgentModelConfig(
                id = id,
                agentId = agentId,
                displayName = item.optString("displayName", "").trim().ifEmpty { id },
                providerId = item.optString("providerId", "").trim(),
                providerName = item.optString("providerName", "").trim(),
                protocol = protocol,
                baseUrl = item.optString("baseUrl", "").trim(),
                apiKey = item.optString("apiKey", "").trim(),
                modelId = item.optString("modelId", "").trim(),
                isDefault = item.optBoolean("isDefault", false),
                contextWindowTokens = item.optInt("contextWindowTokens", 0).coerceAtLeast(0),
                autoCompactWindowTokens = item.optInt("autoCompactWindowTokens", 0).coerceAtLeast(0),
                maxOutputTokens = item.optInt("maxOutputTokens", 0).coerceAtLeast(0),
                effortLevel = item.optString("effortLevel", "default").trim().ifEmpty { "default" },
                maxThinkingTokens = item.optInt("maxThinkingTokens", 0).coerceAtLeast(0),
            )
        }
        return output
    }

    private fun writeAllConfigs(context: Context, configs: List<AgentModelConfig>) {
        val arr = JSONArray()
        configs.forEach { cfg ->
            arr.put(
                JSONObject()
                    .put("id", cfg.id)
                    .put("agentId", cfg.agentId.value)
                    .put("displayName", cfg.displayName)
                    .put("providerId", cfg.providerId)
                    .put("providerName", cfg.providerName)
                    .put("protocol", cfg.protocol.value)
                    .put("baseUrl", cfg.baseUrl)
                    .put("apiKey", cfg.apiKey)
                    .put("modelId", cfg.modelId)
                    .put("isDefault", cfg.isDefault)
                    .put("contextWindowTokens", cfg.contextWindowTokens)
                    .put("autoCompactWindowTokens", cfg.autoCompactWindowTokens)
                    .put("maxOutputTokens", cfg.maxOutputTokens)
                    .put("effortLevel", cfg.effortLevel)
                    .put("maxThinkingTokens", cfg.maxThinkingTokens),
            )
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIGS_JSON, arr.toString())
            .apply()
    }

    private fun stateFile(context: Context, name: String): java.io.File {
        val paths = BootstrapInstaller.getPaths(context)
        return java.io.File(paths.homeDir, ".openclaw-android/state/$name")
    }

    private fun readRuntimeOverride(context: Context): JSONObject {
        val file = stateFile(context, RUNTIME_OVERRIDE_FILE)
        return if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() } else JSONObject()
    }

    private fun writePublicState(context: Context, configs: List<AgentModelConfig>) {
        val publicConfigs = JSONArray()
        configs.filter { it.agentId == ExternalAgentId.CLAUDE_CODE }.forEach { cfg ->
            publicConfigs.put(
                JSONObject()
                    .put("id", cfg.id)
                    .put("displayName", cfg.displayName)
                    .put("providerName", cfg.providerName)
                    .put("modelId", cfg.modelId)
                    .put("isDefault", cfg.isDefault)
                    .put("contextWindowTokens", cfg.contextWindowTokens)
                    .put("autoCompactWindowTokens", cfg.autoCompactWindowTokens)
                    .put("maxOutputTokens", cfg.maxOutputTokens)
                    .put("effortLevel", cfg.effortLevel)
                    .put("maxThinkingTokens", cfg.maxThinkingTokens),
            )
        }
        val root = JSONObject().put("version", 1).put("configs", publicConfigs)
        val file = stateFile(context, PUBLIC_STATE_FILE)
        file.parentFile?.mkdirs()
        val temp = java.io.File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(root.toString(2))
        if (!temp.renameTo(file)) {
            file.writeText(root.toString(2))
            temp.delete()
        }
    }
}
