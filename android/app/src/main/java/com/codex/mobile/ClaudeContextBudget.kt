package com.codex.mobile

/** Output ceilings cannot reserve almost the entire input window. No stored settings are rewritten. */
object ClaudeContextBudget {
    fun outputLimit(contextWindow: Int, requestedOutput: Int): Int {
        if (requestedOutput <= 0 || contextWindow <= 0) return requestedOutput.coerceAtLeast(0)
        return requestedOutput.coerceAtMost((contextWindow / 4).coerceAtLeast(1))
    }

    fun safeInputLimit(contextWindow: Int, requestedOutput: Int): Int {
        if (contextWindow <= 0) return 144_000
        val reserve = outputLimit(contextWindow, requestedOutput.takeIf { it > 0 } ?: 16_384)
        return ((contextWindow.toLong() - reserve) * 9L / 10L).coerceAtLeast(1L).toInt()
    }

    fun autoCompactLimit(contextWindow: Int, requestedOutput: Int, requestedWindow: Int): Int {
        val safe = safeInputLimit(contextWindow, requestedOutput)
        val proactive = (safe.toLong() * 9L / 10L).coerceAtLeast(1L).toInt()
        return if (requestedWindow > 0) requestedWindow.coerceAtMost(proactive) else proactive
    }
}
