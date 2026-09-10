package com.codex.mobile

import org.json.JSONArray
import org.json.JSONObject

/** Persisted text memory only; screenshots are always acquired by the existing renderer. */
internal class PhoneUiConversationContext(
    val history: MutableList<Pair<String, String>>,
    var memory: String,
    val initialTask: String,
    var lastActionResult: String,
) {
    fun taskWithMemory(task: String): String = if (memory.isBlank() && task == initialTask) task else
        "会话最初指令：$initialTask\n历史进展摘要（不代表当前屏幕）：$memory\n用户本轮最新指令：$task"

    fun estimatedTextTokens(task: String, config: PhoneUiModelConfig): Int =
        (if (config.protocol == PhoneUiModelProtocol.AUTOGLM_NATIVE && history.isNotEmpty()) 0 else estimate(taskWithMemory(task))) +
        history.sumOf { estimate(it.second) + 8 } + estimate(lastActionResult)

    fun needsCompaction(task: String, config: PhoneUiModelConfig): Boolean =
        history.isNotEmpty() && estimatedTextTokens(task, config) >= textBudget(config)

    fun compact(config: PhoneUiModelConfig, task: String,
        summarize: (PhoneUiModelConfig, String) -> String = { model, source -> PhoneUiAgentModelClient.summarizeContext(model, source) }) {
        val source = buildString {
            append("最初指令：").append(initialTask)
            append("\n最新指令：").append(task)
            append("\n已有摘要：").append(memory)
            history.forEach { (role, text) -> append("\n").append(role).append(": ").append(text) }
            append("\n最后动作的实际返回：").append(lastActionResult)
        }
        val summary = summarize(config, source).trim()
        require(summary.isNotEmpty() && summary.length <= 4_000) {
            "上下文摘要未通过校验，原始上下文已保留，请重新发送补充指令重试"
        }
        // Commit only after successful validation. No missing/truncated summary may erase history.
        memory = summary
        val recent = history.takeLast(4)
        history.clear()
        history.addAll(recent)
        if (history.isNotEmpty()) {
            history[0] = "user" to (taskWithMemory(task) + "\n保留的历史观察记录：\n" + history[0].second)
        }
    }

    fun save(state: JSONObject) {
        state.put("conversationContext", JSONObject()
            .put("initialTask", initialTask).put("memory", memory).put("lastActionResult", lastActionResult)
            .put("history", JSONArray().also { rows ->
                history.forEach { (role, content) -> rows.put(JSONObject().put("role", role).put("content", content)) }
            }))
    }

    companion object {
        fun fromState(state: JSONObject): PhoneUiConversationContext {
            val saved = state.optJSONObject("conversationContext") ?: JSONObject()
            val rows = saved.optJSONArray("history") ?: JSONArray()
            val legacyEvents = state.optJSONArray("events") ?: JSONArray()
            val legacyMemory = if (state.has("conversationContext")) "" else
                (0 until legacyEvents.length()).mapNotNull { legacyEvents.optJSONObject(it) }
                    .filter { it.optString("type") in setOf("action", "result") }.takeLast(4)
                    .joinToString("\n") { it.optString("title") + "：" + it.optString("detail") }
            return PhoneUiConversationContext(
                (0 until rows.length()).mapNotNull { index ->
                    val row = rows.optJSONObject(index) ?: return@mapNotNull null
                    val role = row.optString("role")
                    if (role !in setOf("user", "assistant")) null else role to row.optString("content")
                }.toMutableList(), saved.optString("memory").ifBlank { legacyMemory },
                saved.optString("initialTask").ifBlank { state.optString("initialTask").ifBlank { state.optString("task") } }, saved.optString("lastActionResult"),
            )
        }

        // Conservative estimate, not a tokenizer measurement or a claim about quality thresholds.
        fun estimate(text: String): Int = text.fold(0) { count, char -> count + if (char.code < 128) 1 else 3 } / 3 + 1
        fun contextWindow(config: PhoneUiModelConfig): Int = if (config.contextWindowTokens > 0) config.contextWindowTokens.coerceAtLeast(8_000) else when (config.modelId) {
            // The official AutoGLM-Phone-9B model config declares 65,536 positions.
            "autoglm-phone" -> 65_536
            "gui-plus", "gui-plus-2026-02-26" -> 256_000
            "qwen3.5-plus", "qwen3.5-flash", "qwen3.6-flash", "qwen3.7-plus",
            "qwen3.8-max", "qwen3.8-flash" -> 1_000_000
            else -> 16_000 // Unknown or changed snapshots use a conservative budget, not a guessed advertised limit.
        }
        fun textBudget(config: PhoneUiModelConfig): Int =
            ((contextWindow(config) * 0.85).toInt() - 6_000).coerceAtLeast(2_000)
    }
}
