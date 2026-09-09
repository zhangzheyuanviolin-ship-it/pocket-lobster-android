package com.codex.mobile

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal data class PhoneUiSummaryModel(val config: PhoneUiModelConfig, val wireProtocol: String = "chat")

/** Only an explicit user selection authorizes a paid summarization request. */
internal object PhoneUiSummaryModelStore {
    private fun preferences(context: Context) = EncryptedSharedPreferences.create(
        context, "phone_ui_summary_model",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun selectionId(context: Context): String = preferences(context).getString("selected", "").orEmpty()

    fun select(context: Context, id: String) { preferences(context).edit().putString("selected", id).apply() }

    fun custom(context: Context): PhoneUiSummaryModel? {
        val raw = preferences(context).getString("custom", null) ?: return null
        val row = JSONObject(raw)
        return model("summary-custom", row.getString("name"), row.getString("base"), row.getString("key"),
            row.getString("model"), row.getString("protocol"))
    }

    fun saveCustom(context: Context, value: PhoneUiSummaryModel) {
        val config = value.config
        preferences(context).edit().putString("custom", JSONObject()
            .put("name", config.displayName).put("base", config.baseUrl).put("key", config.apiKey)
            .put("model", config.modelId).put("protocol", value.wireProtocol).toString())
            .putString("selected", "summary-custom").apply()
    }

    fun selected(context: Context): PhoneUiSummaryModel? {
        val id = selectionId(context)
        if (id.isEmpty()) return null
        return candidates(context).firstOrNull { it.config.id == id }
    }

    // Called on a worker, never during UI drawing. Existing credentials are referenced,
    // not copied into public task logs or exported phone conversation state.
    fun candidates(context: Context): List<PhoneUiSummaryModel> {
        val output = mutableListOf<PhoneUiSummaryModel>()
        custom(context)?.let(output::add)
        PhoneUiAgentModelStore.loadConfigs(context).filter { it.protocol == PhoneUiModelProtocol.GENERIC_JSON }
            .forEach { output += PhoneUiSummaryModel(it.copy(id = "phone:${it.id}", displayName = "手机模型：${it.displayName}")) }
        CodexModelConfigStore.loadConfigs(context).forEach { provider ->
            val key = CodexModelConfigStore.loadApiKey(context, provider.id)
            provider.availableModelIds.ifEmpty { listOf(provider.modelId) }.forEach { id ->
                output += model("codex:${provider.id}:$id", "Codex：${provider.displayName} / $id", provider.baseUrl, key, id,
                    if (provider.upstreamProtocol == "responses") "responses" else "chat")
            }
        }
        AgentModelConfigStore.loadConfigs(context, ExternalAgentId.CLAUDE_CODE).forEach { provider ->
            output += model("claude:${provider.id}", "Claude：${provider.displayName}", provider.baseUrl, provider.apiKey,
                provider.modelId, if (provider.protocol == ProviderProtocol.ANTHROPIC) "anthropic" else "chat")
        }
        runCatching {
            val repository = ProviderRepository(context.applicationContext)
            runBlocking { withTimeout(10_000) { repository.awaitConfigLoaded() } }
            val config = repository.config.value
            config.instances.filter { it.isEnabled && it.credentialType.name == "apiKey" && !it.azureMode }.forEach { instance ->
                val protocol = when (instance.providerType.name) {
                    "anthropic" -> "anthropic"
                    "gemini" -> "gemini"
                    else -> if (instance.useResponsesAPI) "responses" else "chat"
                }
                val base = instance.effectiveBaseURL ?: when (instance.providerType.name) {
                    "anthropic" -> "https://api.anthropic.com/v1"
                    "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
                    "openRouter" -> "https://openrouter.ai/api/v1"
                    "xAI" -> "https://api.x.ai/v1"
                    "kimiCode" -> "https://api.kimi.com/coding/v1"
                    else -> "https://api.openai.com/v1"
                }
                val key = repository.loadApiKey(instance.id).orEmpty()
                config.modelEntries.filter { it.providerInstanceId == instance.id && !it.isHidden }.forEach { entry ->
                    output += model("minis:${instance.id}:${entry.id}", "Minis：${instance.label} / ${entry.model.id}",
                        base, key, entry.model.id, protocol)
                }
            }
        }.onFailure { android.util.Log.w("PhoneSummaryModels", "Minis model catalog unavailable", it) }
        return output.filter { it.config.apiKey.isNotBlank() && it.config.baseUrl.isNotBlank() && it.config.modelId.isNotBlank() }
    }

    fun model(id: String, name: String, base: String, key: String, modelId: String, protocol: String) =
        PhoneUiSummaryModel(PhoneUiModelConfig(id, name, base, key, modelId, PhoneUiModelProtocol.GENERIC_JSON, false), protocol)
}
