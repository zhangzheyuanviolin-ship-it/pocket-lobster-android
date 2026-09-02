package com.codex.mobile

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class PhoneUiModelProtocol(val value: String) {
    AUTOGLM_NATIVE("autoglm-native"),
    GENERIC_JSON("generic-json"),
}

data class PhoneUiModelConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val protocol: PhoneUiModelProtocol,
    val isDefault: Boolean,
    val temperature: Double = 0.0,
    val topP: Double = 0.85,
)

object PhoneUiAgentModelStore {
    private const val PREFS_NAME = "phone_ui_agent_models_encrypted"
    private const val KEY_CONFIGS = "configs"

    fun presets(): List<PhoneUiModelConfig> = listOf(
        PhoneUiModelConfig(
            id = "autoglm-phone-default",
            displayName = "智谱 AutoGLM Phone",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            apiKey = "",
            modelId = "autoglm-phone",
            protocol = PhoneUiModelProtocol.AUTOGLM_NATIVE,
            isDefault = true,
        ),
        PhoneUiModelConfig(
            id = "custom-vlm-template",
            displayName = "自定义视觉语言模型",
            baseUrl = "",
            apiKey = "",
            modelId = "",
            protocol = PhoneUiModelProtocol.GENERIC_JSON,
            isDefault = false,
        ),
    )

    fun loadConfigs(context: Context): List<PhoneUiModelConfig> {
        val raw = preferences(context).getString(KEY_CONFIGS, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val output = mutableListOf<PhoneUiModelConfig>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            val protocol = PhoneUiModelProtocol.entries.firstOrNull {
                it.value == item.optString("protocol")
            } ?: PhoneUiModelProtocol.AUTOGLM_NATIVE
            output += PhoneUiModelConfig(
                id = id,
                displayName = item.optString("displayName").trim().ifEmpty { id },
                baseUrl = item.optString("baseUrl").trim().trimEnd('/'),
                apiKey = item.optString("apiKey").trim(),
                modelId = item.optString("modelId").trim(),
                protocol = protocol,
                isDefault = item.optBoolean("isDefault", false),
                temperature = item.optDouble("temperature", 0.0),
                topP = item.optDouble("topP", 0.85),
            )
        }
        return output.sortedWith(
            compareByDescending<PhoneUiModelConfig> { it.isDefault }
                .thenBy { it.displayName.lowercase() },
        )
    }

    fun loadCurrent(context: Context): PhoneUiModelConfig? {
        val rows = loadConfigs(context)
        return rows.firstOrNull { it.isDefault } ?: rows.firstOrNull()
    }

    fun save(context: Context, config: PhoneUiModelConfig) {
        val rows = loadConfigs(context).toMutableList()
        if (config.isDefault) {
            for (index in rows.indices) rows[index] = rows[index].copy(isDefault = false)
        }
        val target = rows.indexOfFirst { it.id == config.id }
        if (target >= 0) rows[target] = config else rows += config
        if (rows.none { it.isDefault } && rows.isNotEmpty()) rows[0] = rows[0].copy(isDefault = true)
        persist(context, rows)
    }

    fun createId(): String = "phone-ui-${UUID.randomUUID()}"

    fun setDefault(context: Context, id: String) {
        persist(context, loadConfigs(context).map { it.copy(isDefault = it.id == id) })
    }

    fun delete(context: Context, id: String) {
        val rows = loadConfigs(context).filterNot { it.id == id }.toMutableList()
        if (rows.none { it.isDefault } && rows.isNotEmpty()) rows[0] = rows[0].copy(isDefault = true)
        persist(context, rows)
    }

    private fun persist(context: Context, rows: List<PhoneUiModelConfig>) {
        val array = JSONArray()
        rows.forEach { row ->
            array.put(
                JSONObject()
                    .put("id", row.id)
                    .put("displayName", row.displayName)
                    .put("baseUrl", row.baseUrl)
                    .put("apiKey", row.apiKey)
                    .put("modelId", row.modelId)
                    .put("protocol", row.protocol.value)
                    .put("isDefault", row.isDefault)
                    .put("temperature", row.temperature)
                    .put("topP", row.topP),
            )
        }
        preferences(context).edit().putString(KEY_CONFIGS, array.toString()).apply()
    }

    private fun preferences(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
