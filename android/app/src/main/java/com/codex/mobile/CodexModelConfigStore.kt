package com.codex.mobile

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class CodexModelConfig(
    val id: String,
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val supportedReasoningEfforts: List<String>,
    val upstreamProtocol: String,
    val verificationStatus: String,
    val lastVerifiedAt: String,
    val verifiedModel: String,
    val verificationMessage: String,
    val isDefault: Boolean,
)

object CodexModelConfigStore {
    private const val PREFS_NAME = "codex_model_configs"
    private const val KEY_CONFIGS_JSON = "configs_json"
    private const val SECRET_PREFS_NAME = "codex_model_secrets"
    private const val SECRET_KEY_PREFIX = "api_key_"
    private const val STATE_FILE_NAME = "codex-model-providers.json"
    private const val HANDOFF_FILE_NAME = "codex-provider-secrets.handoff.json"
    private val allowedEfforts = setOf("none", "minimal", "low", "medium", "high", "xhigh")

    fun loadConfigs(context: Context): List<CodexModelConfig> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONFIGS_JSON, "[]")
            .orEmpty()
        val parsed = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val rows = mutableListOf<CodexModelConfig>()
        for (index in 0 until parsed.length()) {
            val row = parsed.optJSONObject(index) ?: continue
            val id = row.optString("id").trim()
            val providerId = row.optString("providerId").trim()
            val baseUrl = row.optString("baseUrl").trim().trimEnd('/')
            val modelId = row.optString("modelId").trim()
            if (id.isEmpty() || providerId.isEmpty() || baseUrl.isEmpty() || modelId.isEmpty()) continue
            val efforts = row.optJSONArray("supportedReasoningEfforts")
                ?.let(::readEfforts)
                .orEmpty()
                .ifEmpty { listOf("low", "medium", "high", "xhigh") }
            rows += CodexModelConfig(
                id = id,
                providerId = providerId,
                displayName = row.optString("displayName").trim().ifEmpty { modelId },
                baseUrl = baseUrl,
                modelId = modelId,
                supportedReasoningEfforts = efforts,
                upstreamProtocol = row.optString("upstreamProtocol", "responses").trim()
                    .ifEmpty { "responses" },
                verificationStatus = row.optString("verificationStatus", "unknown").trim()
                    .ifEmpty { "unknown" },
                lastVerifiedAt = row.optString("lastVerifiedAt").trim(),
                verifiedModel = row.optString("verifiedModel").trim(),
                verificationMessage = row.optString("verificationMessage").trim(),
                isDefault = row.optBoolean("isDefault", false),
            )
        }
        return rows.sortedWith(compareByDescending<CodexModelConfig> { it.isDefault }.thenBy { it.displayName.lowercase() })
    }

    fun loadCurrent(context: Context): CodexModelConfig? {
        return loadConfigs(context).firstOrNull { it.isDefault }
    }

    fun loadApiKey(context: Context, configId: String): String {
        return encryptedPreferences(context).getString(SECRET_KEY_PREFIX + configId, "").orEmpty()
    }

    fun saveConfig(context: Context, config: CodexModelConfig, apiKey: String?) {
        val rows = loadConfigs(context).toMutableList()
        val index = rows.indexOfFirst { it.id == config.id }
        if (config.isDefault) {
            for (rowIndex in rows.indices) {
                if (rows[rowIndex].id != config.id && rows[rowIndex].isDefault) {
                    rows[rowIndex] = rows[rowIndex].copy(isDefault = false)
                }
            }
        }
        if (index >= 0) rows[index] = config else rows += config
        persistMetadata(context, rows)
        if (apiKey != null && apiKey.isNotBlank()) {
            encryptedPreferences(context).edit()
                .putString(SECRET_KEY_PREFIX + config.id, apiKey.trim())
                .apply()
        }
        writePublicState(context, rows)
        writeSecretHandoff(context, rows)
    }

    fun setDefault(context: Context, configId: String) {
        val existing = loadConfigs(context)
        if (existing.none { it.id == configId }) return
        val rows = existing.map { it.copy(isDefault = it.id == configId) }
        persistMetadata(context, rows)
        writePublicState(context, rows)
        writeSecretHandoff(context, rows)
    }

    fun deleteConfig(context: Context, configId: String) {
        val existing = loadConfigs(context)
        val deletedWasDefault = existing.any { it.id == configId && it.isDefault }
        val rows = existing.filterNot { it.id == configId }.toMutableList()
        if (deletedWasDefault) {
            val fallbackIndex = rows.indexOfFirst { it.verificationStatus == "verified" }
            if (fallbackIndex >= 0) rows[fallbackIndex] = rows[fallbackIndex].copy(isDefault = true)
        }
        persistMetadata(context, rows)
        encryptedPreferences(context).edit().remove(SECRET_KEY_PREFIX + configId).apply()
        writePublicState(context, rows)
        writeSecretHandoff(context, rows)
    }

    fun writeSecretHandoff(context: Context) {
        writeSecretHandoff(context, loadConfigs(context))
    }

    fun createId(): String = "provider_${System.currentTimeMillis()}"

    fun environmentKey(configId: String): String {
        val normalized = configId.uppercase(Locale.US).replace(Regex("[^A-Z0-9_]"), "_")
        return "POCKET_LOBSTER_CODEX_${normalized}_API_KEY"
    }

    private fun encryptedPreferences(context: Context) = EncryptedSharedPreferences.create(
        context,
        SECRET_PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun persistMetadata(context: Context, rows: List<CodexModelConfig>) {
        val array = JSONArray()
        rows.forEach { array.put(toJson(it)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIGS_JSON, array.toString())
            .apply()
    }

    private fun writePublicState(context: Context, rows: List<CodexModelConfig>) {
        val root = JSONObject()
            .put("version", 1)
            .put("currentConfigId", rows.firstOrNull { it.isDefault }?.id.orEmpty())
        val configs = JSONArray()
        rows.forEach { configs.put(toJson(it)) }
        root.put("configs", configs)
        writePrivateFile(stateFile(context, STATE_FILE_NAME), root.toString(2))
    }

    private fun writeSecretHandoff(context: Context, rows: List<CodexModelConfig>) {
        val values = JSONObject()
        rows.forEach { row ->
            val apiKey = loadApiKey(context, row.id)
            if (apiKey.isNotBlank()) values.put(environmentKey(row.id), apiKey)
        }
        writePrivateFile(stateFile(context, HANDOFF_FILE_NAME), values.toString())
    }

    private fun writePrivateFile(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(text)
        temp.setReadable(false, false)
        temp.setWritable(false, false)
        temp.setReadable(true, true)
        temp.setWritable(true, true)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun stateFile(context: Context, name: String): File {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.homeDir, ".openclaw-android/state/$name")
    }

    private fun toJson(config: CodexModelConfig): JSONObject {
        return JSONObject()
            .put("id", config.id)
            .put("providerId", config.providerId)
            .put("displayName", config.displayName)
            .put("baseUrl", config.baseUrl)
            .put("modelId", config.modelId)
            .put("supportedReasoningEfforts", JSONArray(config.supportedReasoningEfforts))
            .put("upstreamProtocol", config.upstreamProtocol)
            .put("verificationStatus", config.verificationStatus)
            .put("lastVerifiedAt", config.lastVerifiedAt)
            .put("verifiedModel", config.verifiedModel)
            .put("verificationMessage", config.verificationMessage)
            .put("isDefault", config.isDefault)
    }

    private fun readEfforts(array: JSONArray): List<String> {
        val rows = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim().lowercase(Locale.US)
            if (value in allowedEfforts && value !in rows) rows += value
        }
        return rows
    }

}
