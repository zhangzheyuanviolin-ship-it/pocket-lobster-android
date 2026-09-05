package com.codex.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun normalizesGenericSwipeFromElementDirectionAndDistance() {
        val result = PhoneUiActionParser.parse(
            """{"action":"Swipe","element":[500,800],"direction":"up","distance":500,"thinking":"继续查看下方商品"}""",
            PhoneUiModelProtocol.GENERIC_JSON,
        )

        assertEquals("Swipe", result.action.name)
        assertEquals(500, result.action.x)
        assertEquals(800, result.action.y)
        assertEquals(500, result.action.endX)
        assertEquals(300, result.action.endY)
    }

    @Test
    fun infersGenericScrollDirectionFromReasoning() {
        val result = PhoneUiActionParser.parse(
            """{"action":"Swipe","element":[499,800],"thinking":"需要向下滑动页面找到真正的商品"}""",
            PhoneUiModelProtocol.GENERIC_JSON,
        )

        assertEquals(499, result.action.endX)
        assertEquals(350, result.action.endY)
    }

    @Test
    fun rejectsGenericTypeWithoutTextForModelCorrection() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PhoneUiActionParser.parse(
                """{"action":"Type","element":[350,936],"thinking":"输入问候消息"}""",
                PhoneUiModelProtocol.GENERIC_JSON,
            )
        }

        assertTrue(error.message.orEmpty().contains("缺少text字段"))
    }

    @Test
    fun parsesGuiPlusStringArgumentsAndActionAlias() {
        val result = PhoneUiActionParser.parse(
            """Action: tap the button
                <tool_call>{"name":"mobile_use","arguments":"{\"action\":\"click-at\",\"coordinate\":[420,615]}"}</tool_call>
            """.trimIndent(),
            PhoneUiModelProtocol.GUI_PLUS_NATIVE,
        )

        assertEquals("Tap", result.action.name)
        assertEquals(420, result.action.x)
        assertEquals(615, result.action.y)
    }

}
