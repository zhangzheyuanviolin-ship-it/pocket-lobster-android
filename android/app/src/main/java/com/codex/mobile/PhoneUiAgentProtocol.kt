package com.codex.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

data class PhoneUiAction(
    val name: String,
    val x: Int? = null,
    val y: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val text: String? = null,
    val app: String? = null,
    val seconds: Double? = null,
    val message: String? = null,
    val finished: Boolean = false,
)

data class PhoneUiModelDecision(
    val raw: String,
    val thinking: String,
    val action: PhoneUiAction,
)

object PhoneUiActionParser {
    fun parse(raw: String, protocol: PhoneUiModelProtocol): PhoneUiModelDecision {
        val decision = when (protocol) {
            PhoneUiModelProtocol.AUTOGLM_NATIVE -> parseAutoGlm(raw)
            PhoneUiModelProtocol.GENERIC_JSON -> parseGenericJson(raw)
        }
        validate(decision.action)
        return decision
    }

    private fun parseAutoGlm(raw: String): PhoneUiModelDecision {
        val normalized = raw.replace('\u201c', '"').replace('\u201d', '"')
            .replace('\u2018', '\'').replace('\u2019', '\'')
        val answer = tag(normalized, "answer").ifBlank { normalized.trim() }
        val finishBody = callBody(answer, "finish")
        if (finishBody != null) {
            return PhoneUiModelDecision(
                raw,
                extractThinking(normalized, answer, "finish"),
                PhoneUiAction(
                    "finish",
                    message = quoted(finishBody, "message") ?: finishBody.trim().ifBlank { "任务已完成" },
                    finished = true,
                ),
            )
        }
        val doBody = callBody(answer, "do")
            ?: throw IllegalArgumentException("模型没有返回可解析的do或finish动作")
        val actionName = quoted(doBody, "action")
            ?: bare(doBody, "action")
            ?: throw IllegalArgumentException("动作缺少action字段")
        val point = point(doBody, "element")
        val start = point(doBody, "start")
        val end = point(doBody, "end")
        return PhoneUiModelDecision(
            raw,
            extractThinking(normalized, answer, "do"),
            PhoneUiAction(
                name = actionName,
                x = point?.first ?: start?.first,
                y = point?.second ?: start?.second,
                endX = end?.first,
                endY = end?.second,
                text = quoted(doBody, "text"),
                app = quoted(doBody, "app"),
                seconds = (quoted(doBody, "duration") ?: bare(doBody, "duration"))
                    ?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull(),
                message = quoted(doBody, "message") ?: quoted(doBody, "instruction"),
            ),
        )
    }

    private fun parseGenericJson(raw: String): PhoneUiModelDecision {
        val candidate = tag(raw, "answer").ifBlank { raw }
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        val json = runCatching { JSONObject(candidate) }.getOrElse {
            val begin = candidate.indexOf('{')
            val end = candidate.lastIndexOf('}')
            if (begin < 0 || end <= begin) throw IllegalArgumentException("模型没有返回JSON动作")
            JSONObject(candidate.substring(begin, end + 1))
        }
        val name = json.optString("action").ifBlank { json.optString("name") }
        if (name.isBlank()) throw IllegalArgumentException("JSON动作缺少action字段")
        val element = json.optJSONArray("element")
        val start = json.optJSONArray("start")
        val end = json.optJSONArray("end")
        val finished = name.equals("finish", true)
        return PhoneUiModelDecision(
            raw,
            json.optString("thinking"),
            PhoneUiAction(
                name = name,
                x = element?.optInt(0) ?: start?.optInt(0),
                y = element?.optInt(1) ?: start?.optInt(1),
                endX = end?.optInt(0),
                endY = end?.optInt(1),
                text = json.optString("text").ifBlank { null },
                app = json.optString("app").ifBlank { null },
                seconds = json.optDouble("seconds", Double.NaN).takeUnless { it.isNaN() },
                message = json.optString("message").ifBlank { null },
                finished = finished,
            ),
        )
    }

    private fun tag(text: String, name: String): String =
        Regex("<$name>(.*?)</$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private fun quoted(body: String, key: String): String? {
        val match = Regex(
            """\b${Regex.escape(key)}\s*=\s*(?:\"((?:\\.|[^\"])*)\"|'((?:\\.|[^'])*)')""",
            RegexOption.IGNORE_CASE,
        ).find(body) ?: return null
        return unescape(match.groupValues[1].ifEmpty { match.groupValues[2] })
    }

    private fun bare(body: String, key: String): String? = Regex(
        """\b${Regex.escape(key)}\s*=\s*([^,\s)]+)""",
        RegexOption.IGNORE_CASE,
    ).find(body)?.groupValues?.getOrNull(1)?.trim()

    private fun callBody(text: String, name: String): String? {
        val match = Regex("""\b${Regex.escape(name)}\s*\(""", RegexOption.IGNORE_CASE).find(text)
            ?: return null
        val start = match.range.last + 1
        var depth = 1
        var quote: Char? = null
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\' && quote != null) {
                escaped = true
                continue
            }
            if (char == '"' || char == '\'') {
                if (quote == null) quote = char else if (quote == char) quote = null
                continue
            }
            if (quote != null) continue
            if (char == '(') depth++
            if (char == ')' && --depth == 0) return text.substring(start, index)
        }
        return null
    }

    private fun extractThinking(raw: String, answer: String, callName: String): String {
        tag(raw, "think").takeIf(String::isNotBlank)?.let { return it }
        val marker = Regex("""\b${Regex.escape(callName)}\s*\(""", RegexOption.IGNORE_CASE)
            .find(answer)?.range?.first ?: return ""
        return answer.substring(0, marker)
            .replace(Regex("</?answer>", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun validate(action: PhoneUiAction) {
        when (action.name.trim().lowercase()) {
            "tap", "double tap", "long press" -> require(action.x != null && action.y != null) {
                "${action.name}动作缺少element坐标"
            }
            "swipe" -> require(
                action.x != null && action.y != null && action.endX != null && action.endY != null,
            ) { "Swipe动作缺少start或end坐标" }
            "type", "type_name" -> require(!action.text.isNullOrBlank()) { "${action.name}动作缺少text字段" }
            "launch" -> require(!action.app.isNullOrBlank()) { "Launch动作缺少app字段" }
        }
    }

    private fun point(body: String, key: String): Pair<Int, Int>? {
        val match = Regex("""\b${Regex.escape(key)}\s*=\s*\[\s*(\d{1,4})\s*,\s*(\d{1,4})\s*]""", RegexOption.IGNORE_CASE)
            .find(body) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun unescape(value: String): String = runCatching {
        JSONArray("[\"$value\"]").getString(0)
    }.getOrDefault(value.replace("\\\"", "\"").replace("\\'", "'").replace("\\n", "\n"))
}

object PhoneUiAgentPrompt {
    fun system(protocol: PhoneUiModelProtocol): String {
        if (protocol == PhoneUiModelProtocol.GENERIC_JSON) {
            return """
                You control an Android phone from screenshots and must carry out the user's requested actions. Return one JSON object only. Supported actions are Launch, Tap, Type, Swipe, Back, Home, Wait, Double Tap, Long Press, Take_over and finish. Coordinates use 0..999 relative to the screenshot. Examples: {"action":"Tap","element":[500,500],"thinking":"short reason"}; {"action":"finish","message":"result"}. Do not classify an app, screen, or ordinary action such as typing, sending, signing in, confirming, deleting, installing, or granting permission as sensitive, and do not pause merely because of its category. Use Take_over only when progress is technically impossible without the user manually entering a secret or dynamic challenge such as a password, one-time code, CAPTCHA, or biometric verification.
            """.trimIndent()
        }
        return """
            今天的日期是${LocalDate.now()}。你是安卓手机UI自动化智能体，根据当前截图和操作历史完成用户任务。每次只返回一个动作，严格使用格式：<think>简短判断</think><answer>动作</answer>。
            支持动作：do(action="Launch", app="应用名或包名")；do(action="Tap", element=[x,y])；do(action="Type", text="文本")；do(action="Type_Name", text="文本")；do(action="Swipe", start=[x1,y1], end=[x2,y2])；do(action="Double Tap", element=[x,y])；do(action="Long Press", element=[x,y])；do(action="Back")；do(action="Home")；do(action="Wait", duration="2 seconds")；do(action="Take_over", message="需要用户接管的原因")；finish(message="任务结果")。坐标范围是左上角[0,0]到右下角[999,999]。
            执行前确认当前页面；上一步未生效时先等待再调整；连续三次无进展必须换策略；完成前核对结果。不要把应用名称、页面类别或输入、发送、签到、确认、删除、安装、授权等用户已明确要求的普通操作判定为敏感操作，也不要仅因这些类别暂停任务。只有流程在技术上必须由用户本人输入密码、动态验证码、CAPTCHA或完成生物识别而无法继续时，才使用Take_over请求用户手动处理。
        """.trimIndent()
    }
}

object PhoneUiAgentModelClient {
    fun decide(
        config: PhoneUiModelConfig,
        task: String,
        screenshotPng: ByteArray,
        history: List<Pair<String, String>>,
        actionResult: String,
    ): PhoneUiModelDecision {
        require(config.baseUrl.isNotBlank()) { "模型Base URL未配置" }
        require(config.apiKey.isNotBlank()) { "模型API密钥未配置" }
        require(config.modelId.isNotBlank()) { "模型ID未配置" }
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", PhoneUiAgentPrompt.system(config.protocol)),
        )
        history.takeLast(12).forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content.take(12_000)))
        }
        val prompt = buildString {
            append(if (history.isEmpty()) "用户任务：$task" else "继续完成用户任务：$task")
            if (actionResult.isNotBlank()) append("\n上一动作执行结果：$actionResult")
            append("\n请根据当前截图返回下一步动作。")
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/png;base64,${Base64.encodeToString(screenshotPng, Base64.NO_WRAP)}"),
                ),
            )
        messages.put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", config.modelId)
            .put("messages", messages)
            .put("temperature", config.temperature)
            .put("top_p", config.topP)
            .put("stream", false)
            .put("max_tokens", 3000)
        if (config.protocol == PhoneUiModelProtocol.AUTOGLM_NATIVE) {
            body.put("frequency_penalty", 0.2)
        }
        val response = post(config, body)
        val rawContent = extractContent(response)
        return PhoneUiActionParser.parse(rawContent, config.protocol)
    }

    fun probe(config: PhoneUiModelConfig): String {
        val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f }
        canvas.drawText("Pocket Lobster UI test", 45f, 90f, paint)
        paint.color = Color.rgb(30, 110, 220)
        canvas.drawRect(120f, 150f, 360f, 235f, paint)
        paint.color = Color.WHITE
        canvas.drawText("Continue", 175f, 205f, paint)
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val decision = decide(
            config,
            "这是连接测试。请识别截图中的Continue按钮并返回一个Tap动作，不要真正执行。",
            bytes,
            emptyList(),
            "",
        )
        val supported = setOf(
            "launch", "tap", "type", "type_name", "swipe", "back", "home", "wait",
            "double tap", "long press", "take_over", "interact", "note", "call_api", "finish",
        )
        require(decision.action.name.trim().lowercase() in supported) {
            "视觉模型已响应，但返回了不支持的动作：${decision.action.name}"
        }
        return "连接与真实视觉生成均成功：${decision.action.name}，协议${config.protocol.value}"
    }

    private fun post(config: PhoneUiModelConfig, body: JSONObject): JSONObject {
        val base = config.baseUrl.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("模型HTTP $code：${text.take(800)}")
        return runCatching { JSONObject(text) }.getOrElse {
            throw IllegalStateException("模型返回的不是JSON：${text.take(400)}")
        }
    }

    private fun extractContent(response: JSONObject): String {
        val content = response.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.opt("content")
            ?: throw IllegalStateException("模型响应缺少choices[0].message.content")
        if (content is String) return content
        if (content is JSONArray) {
            return buildString {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isNotBlank()) append(text)
                }
            }.ifBlank { throw IllegalStateException("模型响应文本为空") }
        }
        return content.toString()
    }
}
