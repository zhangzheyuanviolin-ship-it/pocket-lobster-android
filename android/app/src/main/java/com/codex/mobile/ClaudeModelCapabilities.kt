package com.codex.mobile

import java.net.URI

data class ClaudeRuntimeCapability(
    val contextWindowTokens: Int,
    val cliModelId: String,
    val maxOutputTokens: Int,
    val safeInputTokens: Int,
    val autoCompactTokens: Int,
    val millionContextSupported: Boolean,
    val legacyBudgetRecovered: Boolean,
) {
    fun userExplanation(): String {
        val window = if (millionContextSupported) "当前 MiniMax M3 官方接口可启用百万上下文，不需要升级 Claude；当前窗口 $contextWindowTokens。" else
            "此模型或网关尚未验证扩展窗口，当前按 $contextWindowTokens 上下文运行；二十万是未知路线的默认预算，不是所有模型的硬上限。"
        val output = if (maxOutputTokens > 0) "当前单次输出预算 $maxOutputTokens。" else "单次输出采用 CLI 默认值。"
        return window + output + "当前 CLI 通用输出上限按 128000 处理，且输出预算不超过上下文四分之一；上下文包含输入与输出，自动压缩阈值 $autoCompactTokens。参数填写数字不代表提供商支持，不能将保存成功说成实际扩容。"
    }
}

object ClaudeModelCapabilities {
    fun resolve(modelId: String, baseUrl: String, context: Int, output: Int, compact: Int): ClaudeRuntimeCapability {
        val baseModel = modelId.trim().replace(Regex("\\[1m]$", RegexOption.IGNORE_CASE), "")
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val official = uri?.scheme.equals("https", ignoreCase = true) &&
            uri?.host?.lowercase() == "api.minimaxi.com" &&
            uri?.path?.trimEnd('/') == "/anthropic" && uri?.port in setOf(-1, 443) &&
            uri?.userInfo == null && uri?.query == null
        val million = official && baseModel.equals("MiniMax-M3", ignoreCase = true)
        val legacy = million && context == 524288 && output == 512000 && compact == 514000
        val ceiling = if (million) 1_000_000 else 200_000
        val window = if (context <= 0 || legacy) ceiling else context.coerceAtMost(ceiling).coerceAtLeast(1)
        val model = if (million) baseModel + if (window > 200_000) "[1m]" else "" else modelId.trim()
        return ClaudeRuntimeCapability(window, model, ClaudeContextBudget.outputLimit(window, output),
            ClaudeContextBudget.safeInputLimit(window, output),
            ClaudeContextBudget.autoCompactLimit(window, output, if (legacy) 0 else compact), million, legacy)
    }
}
