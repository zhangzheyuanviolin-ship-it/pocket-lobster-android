package com.codex.mobile

object ClaudeProcessPresentation {
    // Presentation only: historical records stay intact on disk.
    fun forDisplay(text: String): String = text.lineSequence().filterNot { line ->
        val value = line.trim()
        value.startsWith("当前 Claude CLI 的扩展上下文尚未验证，") ||
            value.startsWith("输出上限超过可用上下文预算，") ||
            value == "Claude 会话已初始化" ||
            Regex("^事件: (stream_event|user)(/.*)?$").matches(value)
    }.joinToString("\n").trim()

    // Live status and the final per-run record can share a list. Append to each only once.
    fun prepareLine(target: MutableList<String>, live: MutableList<String>, text: String): String? {
        val normalized = text.trim()
        if (normalized.isBlank() || target.lastOrNull() == normalized) return null
        if (target !== live) target += normalized
        return normalized
    }
}
