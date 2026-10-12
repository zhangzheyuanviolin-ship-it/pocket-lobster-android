package com.codex.mobile

import org.junit.Assert.*
import org.junit.Test

class ClaudeModelCapabilitiesTest {
    @Test fun officialM3AutoUsesMillionWithoutBinaryUpgrade() {
        val cap = ClaudeModelCapabilities.resolve("MiniMax-M3", "https://api.minimax.io/anthropic", 0, 32768, 0)
        assertTrue(cap.millionContextSupported)
        assertEquals(1000000, cap.contextWindowTokens)
        assertEquals("MiniMax-M3[1m]", cap.cliModelId)
        assertEquals(783457, cap.autoCompactTokens)
    }
    @Test fun theExactBrokenLegacyBudgetRecoversWithoutRewritingSavedValues() {
        val cap = ClaudeModelCapabilities.resolve("MiniMax-M3", "https://api.minimax.io/anthropic", 524288, 512000, 514000)
        assertTrue(cap.legacyBudgetRecovered)
        assertEquals(1000000, cap.contextWindowTokens)
        assertEquals(128000, cap.maxOutputTokens)
        assertEquals(706320, cap.autoCompactTokens)
    }
    @Test fun aDeliberatelySmallerWindowIsRespected() {
        val cap = ClaudeModelCapabilities.resolve("MiniMax-M3[1m]", "https://api.minimax.io/anthropic", 100000, 32768, 90000)
        assertEquals(100000, cap.contextWindowTokens)
        assertEquals("MiniMax-M3", cap.cliModelId)
        assertEquals(25000, cap.maxOutputTokens)
    }
    @Test fun unknownGatewaysAndModelsCannotBorrowM3Proof() {
        for ((model, url) in listOf("MiniMax-M3" to "https://gateway.example/anthropic", "MiniMax-M2.7" to "https://api.minimax.io/anthropic", "MiniMax-M3" to "https://api.minimax.io.evil.example/anthropic", "MiniMax-M3" to "http://api.minimax.io/anthropic")) {
            val cap = ClaudeModelCapabilities.resolve(model, url, 1000000, 32768, 900000)
            assertFalse(cap.millionContextSupported)
            assertEquals(200000, cap.contextWindowTokens)
        }
    }
    @Test fun chinaOfficialEndpointAndCaseInsensitiveMarkerAreSupported() {
        val cap = ClaudeModelCapabilities.resolve("MINIMAX-M3[1M]", "https://api.minimaxi.com:443/anthropic/", 1000000, 0, 0)
        assertTrue(cap.millionContextSupported)
        assertEquals("MINIMAX-M3[1m]", cap.cliModelId)
    }
    @Test fun aTypedTwoMillionWindowDoesNotBecomeReal() {
        assertEquals(1000000, ClaudeModelCapabilities.resolve("MiniMax-M3", "https://api.minimax.io/anthropic", Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE).contextWindowTokens)
    }
    @Test fun aliasedLiveListIsNotWrittenTwice() {
        val live = mutableListOf<String>()
        ClaudeProcessPresentation.prepareLine(live, live, "恢复中")?.let { live += it }
        assertEquals(listOf("恢复中"), live)
        assertNull(ClaudeProcessPresentation.prepareLine(live, live, "恢复中"))
    }
    @Test fun distinctFinalAndLiveRecordsEachReceiveOneLine() {
        val live = mutableListOf<String>()
        val final = mutableListOf<String>()
        ClaudeProcessPresentation.prepareLine(final, live, "工具执行中")?.let { live += it }
        assertEquals(listOf("工具执行中"), final)
        assertEquals(final, live)
        assertNull(ClaudeProcessPresentation.prepareLine(final, live, " "))
    }
    @Test fun oldTransportNoiseIsHiddenWithoutChangingHistoryOrActualToolText() {
        val old = "当前 Claude CLI 的扩展上下文尚未验证，本轮按200000 tokens保守预算\n事件: stream_event\n事件: user\nClaude 会话已初始化\n工具调用: Read\nClaude 错误: 网络断开"
        assertEquals("工具调用: Read\nClaude 错误: 网络断开", ClaudeProcessPresentation.forDisplay(old))
        assertTrue(old.contains("事件: stream_event"))
    }
}
