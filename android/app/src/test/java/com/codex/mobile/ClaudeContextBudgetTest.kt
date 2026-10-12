package com.codex.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeContextBudgetTest {
    @Test fun oversizedOutputDoesNotCollapseTheInputBudget() {
        val cap = ClaudeModelCapabilities.resolve("unknown", "https://gateway.example/anthropic", 524288, 512000, 514000)
        assertEquals(50000, cap.maxOutputTokens)
        assertEquals(135000, cap.safeInputTokens)
        assertEquals(121500, cap.autoCompactTokens)
        assertTrue(cap.safeInputTokens > 14000)
    }
    @Test fun ordinaryRequestedOutputRemainsUnchanged() {
        assertEquals(32768, ClaudeContextBudget.outputLimit(1_000_000, 32768))
        assertEquals(783457, ClaudeContextBudget.autoCompactLimit(1_000_000, 32768, 900000))
    }
    @Test fun stdinHistoryHasNoArtificial140KBArgumentLimit() {
        assertEquals(144000, ClaudeContextBudget.safeInputLimit(0, 0))
        assertEquals(0, ClaudeContextBudget.outputLimit(0, 0))
    }
    @Test fun numericExtremesDoNotOverflow() {
        assertTrue(ClaudeContextBudget.safeInputLimit(Int.MAX_VALUE, Int.MAX_VALUE) > 0)
        assertTrue(ClaudeContextBudget.autoCompactLimit(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE) > 0)
        assertEquals(1, ClaudeContextBudget.safeInputLimit(1, 999999))
    }
}
