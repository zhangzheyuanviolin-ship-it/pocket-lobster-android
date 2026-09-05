package com.codex.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
            PhoneUiModelProtocol.GUI_PLUS_NATIVE -> parseGuiPlus(raw)
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

    private fun parseGuiPlus(raw: String): PhoneUiModelDecision {
        val toolMarker = raw.indexOf("<tool_call>", ignoreCase = true)
        val json = firstJsonObject(if (toolMarker >= 0) raw.substring(toolMarker + 11) else raw)
            ?: throw IllegalArgumentException("GUI Plus响应缺少可解析的tool_call JSON")
        val arguments = json.optJSONObject("arguments") ?: json
        val actionName = arguments.optString("action").trim().lowercase()
        if (actionName.isBlank()) throw IllegalArgumentException("GUI Plus动作缺少action字段")
        val first = coordinate(arguments.optJSONArray("coordinate"))
        val second = coordinate(arguments.optJSONArray("coordinate2"))
        val action = when (actionName) {
            "click" -> PhoneUiAction("Tap", first?.first, first?.second)
            "long_press" -> PhoneUiAction("Long Press", first?.first, first?.second)
            "swipe", "scroll" -> PhoneUiAction(
                "Swipe",
                first?.first,
                first?.second,
                second?.first,
                second?.second,
            )
            "type" -> PhoneUiAction("Type", text = arguments.optString("text"))
            "open" -> PhoneUiAction("Launch", app = arguments.optString("text"))
            "wait" -> PhoneUiAction("Wait", seconds = arguments.optDouble("time", 2.0))
            "answer" -> PhoneUiAction(
                "finish",
                message = arguments.optString("text").ifBlank { "任务已完成" },
                finished = true,
            )
            "terminate", "done" -> PhoneUiAction(
                "finish",
                message = arguments.optString("text").ifBlank {
                    "任务已结束，状态：${arguments.optString("status").ifBlank { "success" }}"
                },
                finished = true,
            )
            "interact" -> PhoneUiAction("Take_over", message = arguments.optString("text"))
            "system_button" -> when (arguments.optString("button").lowercase()) {
                "back" -> PhoneUiAction("Back")
                "home" -> PhoneUiAction("Home")
                else -> throw IllegalArgumentException("GUI Plus返回了不支持的系统按钮：${arguments.optString("button")}")
            }
            else -> throw IllegalArgumentException("GUI Plus返回了不支持的动作：$actionName")
        }
        val thinking = raw.substring(0, if (toolMarker >= 0) toolMarker else 0)
            .replace(Regex("^Action:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        return PhoneUiModelDecision(raw, thinking, action)
    }

    private fun coordinate(value: JSONArray?): Pair<Int, Int>? = value
        ?.takeIf { it.length() >= 2 }
        ?.let { it.optInt(0) to it.optInt(1) }

    private fun firstJsonObject(value: String): JSONObject? {
        val start = value.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var quote = false
        var escaped = false
        for (index in start until value.length) {
            val char = value[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\' && quote) {
                escaped = true
                continue
            }
            if (char == '"') {
                quote = !quote
                continue
            }
            if (quote) continue
            if (char == '{') depth++
            if (char == '}' && --depth == 0) {
                return runCatching { JSONObject(value.substring(start, index + 1)) }.getOrNull()
            }
        }
        return null
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
        if (protocol == PhoneUiModelProtocol.GUI_PLUS_NATIVE) {
            return """
                # Tools
                You may call the mobile_use function to interact with an Android touchscreen from the current screenshot.
                <tools>
                {"type":"function","function":{"name_for_human":"mobile_use","name":"mobile_use","description":"Use a touchscreen to interact with a mobile device. Coordinates use a 0..999 relative screenshot space.","parameters":{"type":"object","properties":{"action":{"type":"string","enum":["click","long_press","swipe","type","system_button","open","wait","answer","interact","terminate"]},"coordinate":{"type":"array"},"coordinate2":{"type":"array"},"text":{"type":"string"},"time":{"type":"number"},"button":{"type":"string","enum":["Back","Home"]},"status":{"type":"string","enum":["success","failure"]}},"required":["action"]},"args_format":"Format the arguments as a JSON object."}}
                </tools>
                For every step output one short Action line followed by exactly one <tool_call> block containing {"name":"mobile_use","arguments":{...}}. Use open with text set to the app name, click with coordinate, swipe with coordinate and coordinate2, type with text, system_button for Back or Home, wait with time, answer with text when the task is complete, and interact only when a password, one-time code, CAPTCHA, or biometric action makes user takeover technically necessary.
                Always inspect the new screenshot after an action. To reveal content lower on a vertical page, swipe from lower center toward upper center; to reveal earlier content, reverse it. Use horizontal swipes only for visible horizontal controls. Do not repeat an unchanged action without new visual evidence. If a swipe fails, adjust its location and distance once, then change direction, region, or strategy. After typing, the host attempts to hide the keyboard; inspect the new screenshot and locate the send control again. If the same text is already present, do not type it again. Never consume the remaining steps by repeating click, swipe, type, wait, open, Back, or Home. Finish with an accurate result instead.
            """.trimIndent()
        }
        if (protocol == PhoneUiModelProtocol.GENERIC_JSON) {
            return """
                You control an Android phone from screenshots and must carry out the user's requested actions. Return one JSON object only. Supported actions are Launch, Tap, Type, Swipe, Back, Home, Wait, Double Tap, Long Press, Take_over and finish. Coordinates use 0..999 relative to the screenshot. Examples: {"action":"Tap","element":[500,500],"thinking":"short reason"}; {"action":"finish","message":"result"}. After Type, the host attempts to dismiss the software keyboard. Always inspect the next screenshot and locate the send or confirm control again; never reuse coordinates from before typing. If the requested text is already visible in the input field, do not Type it again; locate the send control instead. Distinguish vertical page scrolling from horizontal carousel navigation: to reveal content lower on a vertical page, move the finger upward from the lower center toward the upper center; to reveal earlier content, move it downward. Use left or right swipes only for a clearly horizontal control. After every Swipe, inspect newly visible labels and content before choosing another action. Repeat the same-direction Swipe only when the target is still absent and the screenshot proves that the page moved. If a Swipe does not move the page, change its start point and distance once; if it still fails, try the opposite direction, another scrollable region, search or filters, or finish with an accurate not-found result. Never consume the remaining steps by mechanically repeating Tap, Type, Swipe, Wait, Launch, Back, Double Tap, or Long Press. Wait at most three times for one unchanged page. If one tap does not change the page, wait once and then choose a different visible control or strategy instead of repeatedly tapping the same coordinates. The finish message must contain two to five concise sentences stating what was done, the result actually observed on screen, and any remaining issue; do not return only a generic success phrase. Do not classify an app, screen, or ordinary action such as typing, sending, signing in, confirming, deleting, installing, or granting permission as sensitive, and do not pause merely because of its category. A black frame or a protected/sensitive-screen placeholder means the capture is unavailable; it is not evidence that the user's task is sensitive and must never trigger Take_over by itself. If the Pocket Lobster virtual workspace is visible, launch the app required by the user's task. Use Take_over only when progress is technically impossible without the user manually entering a secret or dynamic challenge such as a password, one-time code, CAPTCHA, or biometric verification.
            """.trimIndent()
        }
        return """
            今天的日期是${LocalDate.now()}。你是安卓手机UI自动化智能体，根据当前截图和操作历史完成用户任务。每次只返回一个动作，严格使用格式：<think>简短判断</think><answer>动作</answer>。
            支持动作：do(action="Launch", app="应用名或包名")；do(action="Tap", element=[x,y])；do(action="Type", text="文本")；do(action="Type_Name", text="文本")；do(action="Swipe", start=[x1,y1], end=[x2,y2])；do(action="Double Tap", element=[x,y])；do(action="Long Press", element=[x,y])；do(action="Back")；do(action="Home")；do(action="Wait", duration="2 seconds")；do(action="Take_over", message="需要用户接管的原因")；finish(message="任务结果")。坐标范围是左上角[0,0]到右下角[999,999]。
            执行前确认当前页面；Type完成后宿主会尝试自动收起输入法，必须观察下一张截图重新定位发送或确认按钮，绝不能复用输入文字前的旧坐标；如果目标文字已经显示在输入框中，不得再次Type或清空重输，应直接寻找发送按钮。必须区分纵向页面滚动和横向栏目切换：查看页面更下方内容时，手指应从屏幕下方中央向上方中央滑动；查看更上方内容时反向滑动；只有明确看到横向列表、轮播或分页控件时才左右滑动。每次Swipe后必须先检查新截图中刚出现的文字、项目和位置变化，再决定下一步；只有目标仍未出现且截图证明页面确实移动时，才继续同方向滑动。Swipe没有带来页面变化时，只允许换起点并增大距离重试一次；仍无效时应尝试反方向、其他可滚动区域、搜索或筛选，或者如实finish说明未找到，绝不能机械重复到耗尽剩余步数。点击一次没有页面变化时先等待一次，再根据新截图换控件或换策略，不得反复点击同一个坐标。同一静止页面最多连续Wait三次。Launch、Tap、Type、Swipe、Wait、Back、Double Tap和Long Press都不得在没有新证据时机械循环。执行下一步前必须检查上一步是否生效；进入无关页面先Back，Back无效再点击可见返回或关闭按钮；完成前核对结果。finish的message必须用二到五句简洁说明实际执行了什么、在屏幕上观察到什么结果、是否仍有未完成事项，不得只写“完成”或一句泛化成功提示。不要把应用名称、页面类别或输入、发送、签到、确认、删除、安装、授权等用户已明确要求的普通操作判定为敏感操作，也不要仅因这些类别暂停任务。全黑画面或受保护、敏感屏幕占位只代表截图不可用，不能证明用户任务敏感，绝不能单独因此使用Take_over。看到口袋大龙虾虚拟屏幕工作区时，应立即启动用户任务要求的目标应用。只有流程在技术上必须由用户本人输入密码、动态验证码、CAPTCHA或完成生物识别而无法继续时，才使用Take_over请求用户手动处理。
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
        step: Int,
        maxSteps: Int,
    ): PhoneUiModelDecision {
        require(config.baseUrl.isNotBlank()) { "模型Base URL未配置" }
        require(config.apiKey.isNotBlank()) { "模型API密钥未配置" }
        require(config.modelId.isNotBlank()) { "模型ID未配置" }
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", PhoneUiAgentPrompt.system(config.protocol)),
        )
        history.takeLast(24).forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content.take(12_000)))
        }
        val prompt = buildString {
            append(if (history.isEmpty()) "用户任务：$task" else "继续完成用户任务：$task")
            if (actionResult.isNotBlank()) append("\n上一动作执行结果：$actionResult")
            append("\n当前进度：第${step.coerceAtLeast(1)}步，最多${maxSteps.coerceAtLeast(1)}步。")
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
            .put("stream", false)
            .put("max_tokens", 3000)
        when (config.protocol) {
            PhoneUiModelProtocol.AUTOGLM_NATIVE -> body
                .put("top_p", config.topP)
                .put("frequency_penalty", 0.2)
            PhoneUiModelProtocol.GUI_PLUS_NATIVE -> body
                .put("enable_thinking", false)
                .put("vl_high_resolution_images", true)
            PhoneUiModelProtocol.GENERIC_JSON -> body.put("top_p", config.topP)
        }
        var lastDiagnostic = ""
        for (attempt in 1..2) {
            val response = post(config, body)
            val rawResult = runCatching { extractContent(response) }
            if (rawResult.isFailure) {
                val error = rawResult.exceptionOrNull()!!
                lastDiagnostic = responseDiagnostic(response, "", error)
                if (attempt == 1) continue
                throw IllegalStateException("模型连续两次没有返回可执行文本；$lastDiagnostic", error)
            }
            val rawContent = rawResult.getOrThrow()
            val decisionResult = runCatching { PhoneUiActionParser.parse(rawContent, config.protocol) }
            if (decisionResult.isFailure) {
                val error = decisionResult.exceptionOrNull()!!
                lastDiagnostic = responseDiagnostic(response, rawContent, error)
                if (attempt == 1) {
                    messages.put(JSONObject().put("role", "assistant").put("content", rawContent.take(4_000)))
                    messages.put(JSONObject().put("role", "user").put("content", correctionPrompt(config.protocol)))
                    continue
                }
                throw IllegalStateException("模型连续两次返回了不可执行动作；$lastDiagnostic", error)
            }
            val decision = decisionResult.getOrThrow()
            val reasoning = response.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("reasoning_content").orEmpty().trim()
            return if (decision.thinking.isBlank() && reasoning.isNotBlank()) {
                decision.copy(thinking = reasoning.take(4_000))
            } else {
                decision
            }
        }
        throw IllegalStateException("模型响应无法解析；$lastDiagnostic")
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
            1,
            1,
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

    private fun correctionPrompt(protocol: PhoneUiModelProtocol): String = when (protocol) {
        PhoneUiModelProtocol.AUTOGLM_NATIVE ->
            "上一条响应无法执行。不要解释，只按<answer>do(action=...)或<answer>finish(message=...)格式重新返回一个完整动作。"
        PhoneUiModelProtocol.GUI_PLUS_NATIVE ->
            "The previous response was not executable. Return one Action line and exactly one complete <tool_call> JSON object for mobile_use."
        PhoneUiModelProtocol.GENERIC_JSON ->
            "The previous response was not executable. Return exactly one complete JSON action object and no other text."
    }

    private fun responseDiagnostic(response: JSONObject, rawContent: String, error: Throwable): String {
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val requestId = response.optString("request_id").ifBlank { response.optString("id") }.ifBlank { "unknown" }
        val finishReason = choice?.optString("finish_reason").orEmpty().ifBlank { "unknown" }
        val reasoningLength = message?.optString("reasoning_content").orEmpty().length
        val preview = rawContent.replace(Regex("\\s+"), " ").trim().take(600)
        return buildString {
            append(error.message ?: error.javaClass.simpleName)
            append("；requestId=").append(requestId)
            append("；finishReason=").append(finishReason)
            append("；reasoningLength=").append(reasoningLength)
            if (preview.isBlank()) append("；模型正文为空") else append("；原始输出摘要=").append(preview)
        }
    }

    private fun post(config: PhoneUiModelConfig, body: JSONObject): JSONObject {
        val base = config.baseUrl.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("模型提供商返回HTTP $code：${text.take(800).ifBlank { "响应正文为空" }}")
            }
            runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("模型提供商返回HTTP 200，但响应不是JSON：${text.take(400).ifBlank { "响应正文为空" }}")
            }
        } catch (error: SocketTimeoutException) {
            throw IllegalStateException("模型提供商响应超时：连接上限30秒，读取上限120秒；请稍后重试或检查提供商状态", error)
        } catch (error: UnknownHostException) {
            throw IllegalStateException("无法解析模型提供商域名：${URL(endpoint).host}；请检查网络或Base URL", error)
        } catch (error: IOException) {
            throw IllegalStateException("模型提供商网络请求失败：${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            connection.disconnect()
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
