package com.codex.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneUiConversationContextTest {
    private val config = PhoneUiModelConfig("test", "test", "http://localhost", "fixture", "autoglm-phone",
        PhoneUiModelProtocol.AUTOGLM_NATIVE, true)

    @Test fun firstRoundTaskAndProtocolHistoryAreUnchanged() {
        val context = PhoneUiConversationContext.fromState(JSONObject().put("task", "打开豆包"))
        assertEquals("打开豆包", context.taskWithMemory("打开豆包"))
        assertFalse(context.needsCompaction("打开豆包", config))
    }

    @Test fun restartAndFollowupPreserveOriginalTaskAndActionOutcome() {
        val state = JSONObject().put("task", "打开豆包")
        val context = PhoneUiConversationContext.fromState(state)
        context.history.add("user" to "打开豆包")
        context.history.add("assistant" to "do(action=\"Launch\", app=\"豆包\")")
        context.lastActionResult = "已启动豆包"
        context.save(state)
        state.put("task", "现在生成沙滩图片")
        val restored = PhoneUiConversationContext.fromState(JSONObject(state.toString()))
        assertEquals(context.history, restored.history)
        assertEquals("已启动豆包", restored.lastActionResult)
        assertTrue(restored.taskWithMemory(state.getString("task")).contains("打开豆包"))
        assertTrue(restored.taskWithMemory(state.getString("task")).contains("现在生成沙滩图片"))
    }

    @Test fun compactBeforeLegacyWindowWouldDropHistory() {
        val context = PhoneUiConversationContext(mutableListOf(), "", "不要删除聊天", "已输入问候")
        repeat(12) { context.history.add("user" to "观察$it"); context.history.add("assistant" to "动作$it") }
        assertTrue(context.needsCompaction("发送问候", config))
        context.compact(config, "发送问候") { _, source ->
            assertTrue(source.contains("不要删除聊天"))
            assertTrue(source.contains("已输入问候"))
            assertTrue(source.contains("观察0"))
            "已打开豆包并输入问候，尚未发送。保留聊天。"
        }
        assertEquals(4, context.history.size)
        assertTrue(context.history.first().second.contains("尚未发送"))
        assertEquals("动作11", context.history.last().second)
        val saved = JSONObject()
        context.save(saved)
        assertEquals(context.memory, PhoneUiConversationContext.fromState(saved).memory)
    }

    @Test fun failedOrEmptySummaryNeverErasesContext() {
        val context = PhoneUiConversationContext(mutableListOf("user" to "原始指令", "assistant" to "实际结果"), "旧摘要", "初始任务", "已点击")
        val before = context.history.toList()
        assertThrows(IllegalArgumentException::class.java) { context.compact(config, "补充") { _, _ -> "" } }
        assertEquals(before, context.history)
        assertEquals("旧摘要", context.memory)
        assertThrows(IllegalStateException::class.java) { context.compact(config, "补充") { _, _ -> error("network") } }
        assertEquals(before, context.history)
    }

    @Test fun longTextTriggersBudgetEvenBeforeTwelveActions() {
        val context = PhoneUiConversationContext(mutableListOf("user" to "字".repeat(7000)), "", "测试", "")
        assertTrue(context.needsCompaction("测试", config))
        assertEquals(20000, PhoneUiConversationContext.contextWindow(config))
        assertTrue(PhoneUiConversationContext.textBudget(config) < 20000 - 3000)
    }
}
