package com.codex.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class PhoneUiActionParserTest {
    @Test
    fun recoveryKeepsObservationWithoutTeachingMalformedRepetition() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "system"))
            .put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,fixture")))
                .put(JSONObject().put("type", "text").put("text", "任务原文"))))
        val original = messages.toString()
        val body = JSONObject().put("messages", messages).put("max_tokens", 3000)
        repeat(2) {
            PhoneUiAgentModelClient.prepareRecoveryRequest(body, original, PhoneUiModelProtocol.AUTOGLM_NATIVE,
                IllegalArgumentException("缺少完整动作"), true)
        }
        val retried = body.getJSONArray("messages")
        assertEquals(2, retried.length())
        assertEquals(3, retried.getJSONObject(1).getJSONArray("content").length())
        assertEquals("data:image/png;base64,fixture", retried.getJSONObject(1).getJSONArray("content")
            .getJSONObject(0).getJSONObject("image_url").getString("url"))
        assertEquals(original, messages.toString())
        assertEquals(3000, body.getInt("max_tokens"))
    }

    @Test
    fun nativeHistoryPreservesActionAfterLongReasoning() {
        val decision = PhoneUiModelDecision("ignored", "分析".repeat(10000),
            PhoneUiAction("Type", text = "豆包：你好(第二轮)\n测试"))
        val canonical = PhoneUiAgentModelClient.historyAssistant(PhoneUiModelProtocol.AUTOGLM_NATIVE, decision)
        assertTrue(canonical.length < 1500)
        assertEquals(decision.action, PhoneUiActionParser.parse(canonical, PhoneUiModelProtocol.AUTOGLM_NATIVE).action)
        val prompt = PhoneUiAgentModelClient.historyUserPrompt(PhoneUiModelProtocol.AUTOGLM_NATIVE, "不要重复这个任务", false, "点击已执行")
        assertTrue(!prompt.contains("不要重复这个任务"))
        assertTrue(prompt.contains("点击已执行"))
    }

    @Test
    fun nativeRejectsUnsupportedActionsAndPixelCoordinatesBeforeExecution() {
        listOf("do(action=\"Teleport\")", "do(action=\"Tap\", element=[1080,2400])").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                PhoneUiActionParser.parse(raw, PhoneUiModelProtocol.AUTOGLM_NATIVE)
            }
        }
    }

    @Test
    fun neverExecutesAnExampleFromThinkingInsteadOfAnAnswer() {
        val result = PhoneUiActionParser.parse(
            "<think>不能执行finish(message=\"完成\")，尚需点击</think>do(action=\"Tap\", element=[500,600])",
            PhoneUiModelProtocol.AUTOGLM_NATIVE,
        )
        assertEquals("Tap", result.action.name)
        assertThrows(IllegalArgumentException::class.java) {
            PhoneUiActionParser.parse("<think>考虑do(action=\"Tap\", element=[500,600])", PhoneUiModelProtocol.AUTOGLM_NATIVE)
        }
    }

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

        assertTrue(nativePrompt.contains("当前页面以这次新截图为准"))
        assertTrue(nativePrompt.contains("不得根据应用名称或历史推测"))
        assertTrue(genericPrompt.contains("Treat the latest image as the only evidence"))
        assertTrue(genericPrompt.contains("Do not invent dialogs, required steps, or page text"))
        listOf(nativePrompt, genericPrompt).forEach { prompt ->
            assertTrue(!prompt.contains("敏感屏幕"))
            assertTrue(!prompt.contains("验证码"))
            assertTrue(!prompt.contains("CAPTCHA", ignoreCase = true))
        }
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
