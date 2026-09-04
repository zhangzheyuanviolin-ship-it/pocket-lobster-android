package com.codex.mobile

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

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
    fun bridgePreservesExactUtf8TaskAndReportsTerminalResult() {
        val task = "打开豆包APP，发送：帮我生成一张海边沙滩的图片。"
        val bytes = task.toByteArray(Charsets.UTF_8)
        val payload = JSONObject()
            .put("taskBase64", Base64.getEncoder().encodeToString(bytes))
            .put("taskSha256", sha256(bytes))

        assertEquals(task, PhoneUiAgentBridgeProtocol.decodeTask(payload))

        val state = JSONObject()
            .put("id", "phone-task-1")
            .put("task", task)
            .put("status", "completed")
            .put("statusText", "豆包已收到图片生成请求")
            .put("result", "豆包已收到图片生成请求")
        val envelope = PhoneUiAgentBridgeProtocol.envelope(state)
        assertTrue(envelope.getBoolean("terminal"))
        assertEquals("phone-task-1", envelope.getString("taskId"))
        assertEquals("豆包已收到图片生成请求", envelope.getString("result"))
        assertEquals(sha256(bytes), envelope.getString("taskSha256"))
    }

    @Test
    fun bridgeRejectsCorruptedTaskPayload() {
        val bytes = "打开豆包".toByteArray(Charsets.UTF_8)
        val payload = JSONObject()
            .put("taskBase64", Base64.getEncoder().encodeToString(bytes))
            .put("taskSha256", "0".repeat(64))

        val rejected = runCatching { PhoneUiAgentBridgeProtocol.decodeTask(payload) }.isFailure
        assertTrue(rejected)

        val running = PhoneUiAgentBridgeProtocol.envelope(
            JSONObject().put("id", "phone-task-2").put("task", "等待").put("status", "running"),
        )
        assertFalse(running.getBoolean("terminal"))
        assertFalse(running.has("result"))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

}
