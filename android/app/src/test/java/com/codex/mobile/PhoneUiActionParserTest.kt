package com.codex.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUiActionParserTest {
    @Test
    fun parsesOfficialAutoGlmActionWithNarration() {
        val result = PhoneUiActionParser.parse(
            "我需要点击链接。\ndo(action=\"Tap\", element=[306,402])",
            PhoneUiModelProtocol.AUTOGLM_NATIVE,
        )

        assertEquals("Tap", result.action.name)
        assertEquals(306, result.action.x)
        assertEquals(402, result.action.y)
        assertEquals("我需要点击链接。", result.thinking)
    }

    @Test
    fun parsesSingleQuotedActionAndParenthesesInText() {
        val result = PhoneUiActionParser.parse(
            "<answer>do(action='Type', text='测试文本（第二轮）')</answer>",
            PhoneUiModelProtocol.AUTOGLM_NATIVE,
        )

        assertEquals("Type", result.action.name)
        assertEquals("测试文本（第二轮）", result.action.text)
    }

    @Test
    fun parsesSmartQuotesAndBareActionName() {
        val result = PhoneUiActionParser.parse(
            "<think>继续</think><answer>do(action=“Tap”, element=[500, 600])</answer>",
            PhoneUiModelProtocol.AUTOGLM_NATIVE,
        )

        assertEquals("Tap", result.action.name)
        assertEquals("继续", result.thinking)
        assertEquals(500, result.action.x)
        assertEquals(600, result.action.y)
    }

    @Test
    fun parsesSingleQuotedFinish() {
        val result = PhoneUiActionParser.parse(
            "finish(message='任务完成')",
            PhoneUiModelProtocol.AUTOGLM_NATIVE,
        )

        assertTrue(result.action.finished)
        assertEquals("任务完成", result.action.message)
    }

    @Test
    fun promptsDoNotBlockOrdinaryUserRequestedActions() {
        val nativePrompt = PhoneUiAgentPrompt.system(PhoneUiModelProtocol.AUTOGLM_NATIVE)
        val genericPrompt = PhoneUiAgentPrompt.system(PhoneUiModelProtocol.GENERIC_JSON)

        assertTrue(nativePrompt.contains("不要把应用名称、页面类别"))
        assertTrue(nativePrompt.contains("输入、发送、签到、确认、删除、安装、授权"))
        assertTrue(genericPrompt.contains("do not pause merely because of its category"))
        assertTrue(genericPrompt.contains("technically impossible"))
    }

    @Test
    fun delegatedRecoveryNeverChangesManualTasks() {
        val malformed = IllegalArgumentException("模型没有返回可解析的do或finish动作")

        assertEquals("", PhoneUiDelegatedPolicy.guidance(PhoneUiTaskOrigin.MANUAL))
        assertTrue(PhoneUiDelegatedPolicy.guidance(PhoneUiTaskOrigin.DELEGATED).contains("不得只输出说明文字"))
        assertTrue(!PhoneUiDelegatedPolicy.shouldRetryMalformedDecision(PhoneUiTaskOrigin.MANUAL, malformed, 0))
        assertTrue(PhoneUiDelegatedPolicy.shouldRetryMalformedDecision(PhoneUiTaskOrigin.DELEGATED, malformed, 0))
        assertTrue(PhoneUiDelegatedPolicy.shouldRetryMalformedDecision(PhoneUiTaskOrigin.DELEGATED, malformed, 1))
        assertTrue(!PhoneUiDelegatedPolicy.shouldRetryMalformedDecision(PhoneUiTaskOrigin.DELEGATED, malformed, 2))
        assertTrue(!PhoneUiDelegatedPolicy.shouldBlockRepeatedType(PhoneUiTaskOrigin.MANUAL, 99))
        assertTrue(!PhoneUiDelegatedPolicy.shouldBlockRepeatedType(PhoneUiTaskOrigin.DELEGATED, 2))
        assertTrue(PhoneUiDelegatedPolicy.shouldBlockRepeatedType(PhoneUiTaskOrigin.DELEGATED, 3))
    }

}
