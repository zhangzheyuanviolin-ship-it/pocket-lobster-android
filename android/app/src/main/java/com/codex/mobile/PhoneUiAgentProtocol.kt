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
import java.util.concurrent.ConcurrentHashMap
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
    val successful: Boolean = true,
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
        val answer = tag(normalized, "answer").ifBlank {
            val withoutThinking = normalized.replace(
                Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "",
            ).trim()
            require(!withoutThinking.contains("<think>", ignoreCase = true)) {
                "模型的思考标签未闭合且缺少完整answer动作"
            }
            withoutThinking
        }
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
        val root = runCatching { JSONObject(candidate) }.getOrElse {
            firstJsonObject(candidate) ?: throw IllegalArgumentException("模型没有返回JSON动作")
        }
        val json = jsonObject(root.opt("arguments"))
            ?: jsonObject(root.opt("parameters"))
            ?: root.optJSONObject("action")
            ?: root
        val rawName = json.optString("action").ifBlank {
            json.optString("name").ifBlank { root.optString("name") }
        }
        if (rawName.isBlank()) throw IllegalArgumentException("JSON动作缺少action字段")
        val name = canonicalActionName(rawName)
        val thinking = firstText(json, "thinking", "reason", "thought", "analysis")
            ?: firstText(root, "thinking", "reason", "thought", "analysis")
            ?: ""
        val element = firstCoordinate(json, "element", "coordinate", "coordinates", "position", "point")
        val start = firstCoordinate(json, "start", "from", "start_coordinate")
        val end = firstCoordinate(json, "end", "to", "end_coordinate", "coordinate2")
        val text = firstText(json, "text", "value", "content", "input_text", "inputText")
        val app = firstText(json, "app", "app_name", "appName", "package", "package_name", "packageName")
            ?: text.takeIf { name == "Launch" }
        val seconds = firstNumber(json, "seconds", "duration", "time")
        val action = when (name) {
            "Swipe" -> swipeAction(json, "$rawName $thinking", element, start, end)
            "Tap", "Double Tap", "Long Press" -> PhoneUiAction(name, element?.first ?: start?.first, element?.second ?: start?.second)
            "Type", "Type_Name" -> PhoneUiAction(name, text = text)
            "Launch" -> PhoneUiAction(name, app = app)
            "Wait" -> PhoneUiAction(name, seconds = seconds ?: 1.0)
            "Back", "Home" -> PhoneUiAction(name)
            "Take_over", "Interact", "Note", "Call_api" -> PhoneUiAction(
                name,
                message = firstText(json, "message", "instruction", "reason", "text"),
            )
            "finish" -> PhoneUiAction(
                name,
                message = firstText(json, "message", "result", "answer", "text") ?: "任务已完成",
                finished = true,
            )
            else -> throw IllegalArgumentException("模型返回了不支持的JSON动作：$rawName")
        }
        return PhoneUiModelDecision(
            raw,
            thinking,
            action,
        )
    }

    private fun parseGuiPlus(raw: String): PhoneUiModelDecision {
        val toolMarker = raw.indexOf("<tool_call>", ignoreCase = true)
        val json = firstJsonObject(if (toolMarker >= 0) raw.substring(toolMarker + 11) else raw)
            ?: throw IllegalArgumentException("GUI Plus响应缺少可解析的tool_call JSON")
        val arguments = jsonObject(json.opt("arguments")) ?: json
        val actionName = arguments.optString("action").trim().lowercase().replace('-', '_')
        if (actionName.isBlank()) throw IllegalArgumentException("GUI Plus动作缺少action字段")
        val thinking = raw.substring(0, if (toolMarker >= 0) toolMarker else 0)
            .replace(Regex("^Action:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        val first = firstCoordinate(arguments, "coordinate", "element", "position", "point")
        val second = firstCoordinate(arguments, "coordinate2", "end", "to")
        val action = when (actionName) {
            "click", "tap", "click_at" -> PhoneUiAction("Tap", first?.first, first?.second)
            "long_press", "long_click" -> PhoneUiAction("Long Press", first?.first, first?.second)
            "swipe", "scroll" -> swipeAction(arguments, "$actionName $thinking", first, null, second)
            "type", "type_text", "input_text" -> PhoneUiAction(
                "Type",
                text = firstText(arguments, "text", "value", "content"),
            )
            "open", "launch", "open_app" -> PhoneUiAction(
                "Launch",
                app = firstText(arguments, "text", "app", "app_name", "package_name"),
            )
            "wait" -> PhoneUiAction("Wait", seconds = firstNumber(arguments, "time", "seconds", "duration") ?: 2.0)
            "answer", "finish" -> PhoneUiAction(
                "finish",
                message = firstText(arguments, "text", "message", "result") ?: "任务已完成",
                finished = true,
            )
            "terminate" -> PhoneUiAction(
                "finish",
                message = firstText(arguments, "text", "message", "result")
                    ?: "模型因无法继续操作而终止任务",
                finished = true,
                successful = false,
            )
            "done", "completed" -> PhoneUiAction(
                "finish",
                message = firstText(arguments, "text", "message", "result") ?: "任务已完成",
                finished = true,
            )
            "interact", "take_over" -> PhoneUiAction(
                "Take_over",
                message = firstText(arguments, "text", "message", "reason"),
            )
            "system_button" -> when (arguments.optString("button").lowercase()) {
                "back" -> PhoneUiAction("Back")
                "home" -> PhoneUiAction("Home")
                else -> throw IllegalArgumentException("GUI Plus返回了不支持的系统按钮：${arguments.optString("button")}")
            }
            "back", "press_back" -> PhoneUiAction("Back")
            "home", "press_home" -> PhoneUiAction("Home")
            else -> throw IllegalArgumentException("GUI Plus返回了不支持的动作：$actionName")
        }
        return PhoneUiModelDecision(raw, thinking, action)
    }

    private fun canonicalActionName(value: String): String {
        val compact = value.trim().lowercase().replace(Regex("[\\s_-]+"), "")
        return when (compact) {
            "tap", "click", "press", "clickat" -> "Tap"
            "doubletap", "doubleclick" -> "Double Tap"
            "longpress", "longclick" -> "Long Press"
            "type", "input", "fill", "entertext", "typetext", "inputtext" -> "Type"
            "typename" -> "Type_Name"
            "swipe", "scroll", "scrollup", "scrolldown", "scrollleft", "scrollright" -> "Swipe"
            "launch", "open", "openapp", "launchapp", "startapp" -> "Launch"
            "back", "goback" -> "Back"
            "home", "gohome" -> "Home"
            "wait", "sleep" -> "Wait"
            "takeover" -> "Take_over"
            "interact" -> "Interact"
            "note" -> "Note"
            "callapi" -> "Call_api"
            "finish", "finished", "done", "complete", "completed", "answer", "terminate" -> "finish"
            else -> value.trim()
        }
    }

    private fun swipeAction(
        json: JSONObject,
        context: String,
        element: Pair<Int, Int>?,
        start: Pair<Int, Int>?,
        end: Pair<Int, Int>?,
    ): PhoneUiAction {
        val from = start ?: element
        if (from != null && end != null) {
            return PhoneUiAction("Swipe", from.first, from.second, end.first, end.second)
        }
        val explicitDirection = firstText(json, "direction", "swipe_direction", "scroll_direction")
        val direction = normalizeSwipeDirection(explicitDirection, context)
        val distance = (firstNumber(json, "distance", "pixels", "amount") ?: 450.0)
            .toInt()
            .coerceIn(80, 700)
        val anchor = from ?: when (direction) {
            "down" -> 500 to 200
            "left" -> 800 to 500
            "right" -> 200 to 500
            else -> 500 to 800
        }
        val target = when (direction) {
            "down" -> anchor.first to (anchor.second + distance).coerceAtMost(999)
            "left" -> (anchor.first - distance).coerceAtLeast(0) to anchor.second
            "right" -> (anchor.first + distance).coerceAtMost(999) to anchor.second
            else -> anchor.first to (anchor.second - distance).coerceAtLeast(0)
        }
        require(target != anchor) { "Swipe动作的方向或距离无效" }
        return PhoneUiAction("Swipe", anchor.first, anchor.second, target.first, target.second)
    }

    private fun normalizeSwipeDirection(explicit: String?, context: String): String {
        val value = explicit.orEmpty().trim().lowercase()
        if (value in setOf("up", "down", "left", "right")) return value
        val normalized = context.lowercase().replace(Regex("[\\s_-]+"), "")
        return when {
            listOf("scrollleft", "swipeleft", "向左滑", "左滑").any(normalized::contains) -> "left"
            listOf("scrollright", "swiperight", "向右滑", "右滑").any(normalized::contains) -> "right"
            listOf("scrollup", "swipedown", "向上滚动", "回到顶部", "查看更上方").any(normalized::contains) -> "down"
            listOf("scrolldown", "swipeup", "向下滚动", "向下滑动页面", "查看更多", "查看更下方").any(normalized::contains) -> "up"
            else -> "up"
        }
    }

    private fun firstCoordinate(json: JSONObject, vararg keys: String): Pair<Int, Int>? {
        for (key in keys) {
            val value = json.opt(key)
            if (value is JSONArray && value.length() >= 2) {
                return value.optInt(0) to value.optInt(1)
            }
            if (value is JSONObject && value.has("x") && value.has("y")) {
                return value.optInt("x") to value.optInt("y")
            }
        }
        return null
    }

    private fun firstText(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = json.optString(key).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return null
    }

    private fun firstNumber(json: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            val value = json.optDouble(key, Double.NaN)
            if (!value.isNaN()) return value
            val parsed = json.optString(key).replace(Regex("[^0-9.]"), "").toDoubleOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun jsonObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

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
        require(action.name.trim().lowercase() in setOf(
            "launch", "tap", "type", "type_name", "swipe", "double tap", "long press",
            "back", "home", "wait", "take_over", "interact", "note", "call_api", "finish",
        )) { "不支持的动作：${action.name}；请使用已声明的动作名称" }
        require(listOfNotNull(action.x, action.y, action.endX, action.endY).all { it in 0..999 }) {
            "动作坐标超出相对屏幕范围0..999；请勿使用实际像素坐标"
        }
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
                For every step output one short Action line followed by exactly one <tool_call> block containing {"name":"mobile_use","arguments":{...}}. Use open with text set to the app name, click with coordinate, swipe with coordinate and coordinate2, type with text, system_button for Back or Home, wait with time, answer with text only when the task is complete, terminate with status failure only when the task cannot be completed, and interact only when the newest visible controls make automated progress technically impossible.
                Always inspect the new screenshot after an action. Treat only the latest screenshot as evidence of the current page. Do not apply category-based restrictions to an app, screen, or ordinary user-requested action such as typing, sending, confirming, deleting, installing, or granting permission. Never infer an account state, protected page, black screen, or other obstacle from the app name, prior reasoning, or an earlier screenshot. A black or content-free frame is a capture failure and must not trigger interact. Describe the exact visible obstacle and why automation cannot continue before requesting help.
                To reveal content lower on a vertical page, swipe from lower center toward upper center; to reveal earlier content, reverse it. Use horizontal swipes only for visible horizontal controls. Do not repeat an unchanged action without new visual evidence. If a swipe fails, adjust its location and distance once, then change direction, region, or strategy. After typing, the host attempts to hide the keyboard; inspect the new screenshot and locate the send control again. If the same text is already present, do not type it again. Home is not a recovery action for loading, blank, or unchanged pages; use Home only when the user's task explicitly requires the launcher. Never consume the remaining steps by repeating click, swipe, type, wait, open, Back, or Home. Finish with an accurate result instead.
            """.trimIndent()
        }
        if (protocol == PhoneUiModelProtocol.GENERIC_JSON) {
            return """
                You control an Android phone from screenshots and must carry out the user's requested actions. Return one JSON object only. Supported actions are Launch, Tap, Type, Swipe, Back, Home, Wait, Double Tap, Long Press, Take_over and finish. Coordinates use 0..999 relative to the screenshot. Tap requires element:[x,y]. Type always requires the exact text field. Swipe requires start:[x1,y1] and end:[x2,y2]; element:[x,y] plus direction and distance is also accepted. Launch requires app. Examples: {"action":"Tap","element":[500,500],"thinking":"short reason"}; {"action":"Swipe","start":[500,800],"end":[500,300],"thinking":"scroll down"}; {"action":"finish","message":"result"}. After Type, the host attempts to dismiss the software keyboard. Always inspect the next screenshot and locate the send or confirm control again; never reuse coordinates from before typing. If the requested text is already visible in the input field, do not Type it again; locate the send control instead. Distinguish vertical page scrolling from horizontal carousel navigation: to reveal content lower on a vertical page, move the finger upward from the lower center toward the upper center; to reveal earlier content, move it downward. Use left or right swipes only for a clearly horizontal control. After every Swipe, inspect newly visible labels and content before choosing another action. Repeat the same-direction Swipe only when the target is still absent and the screenshot proves that the page moved. If a Swipe does not move the page, change its start point and distance once; if it still fails, try the opposite direction, another scrollable region, search or filters, or finish with an accurate not-found result. Never consume the remaining steps by mechanically repeating Tap, Type, Swipe, Wait, Launch, Back, Double Tap, or Long Press. Home is not a recovery action for loading, blank, or unchanged pages; use Home only when the user's task explicitly requires the launcher. Wait at most three times for one unchanged page. If one tap does not change the page, wait once and then choose a different visible control or strategy instead of repeatedly tapping the same coordinates. The finish message must contain two to five concise sentences stating what was done, the result actually observed on screen, and any remaining issue; do not return only a generic success phrase. Treat the latest image as the only evidence of the current page. Previous thinking is not a current observation. Do not invent dialogs, required steps, or page text. Do not apply category-based restrictions to an app, screen, or ordinary user-requested action such as typing, sending, confirming, deleting, installing, or granting permission. Never infer an account state, protected page, black screen, or other obstacle from the app name or history. A black or content-free frame is a capture failure and must not trigger Take_over. Use Take_over only when the newest visible controls make automated progress technically impossible. When requesting user help, cite the exact visible obstacle, missing information, and why automation cannot continue. If the Pocket Lobster workspace is visible, launch the requested app.
            """.trimIndent()
        }
        return """
            今天的日期是${LocalDate.now()}。你是安卓手机UI自动化智能体，根据当前截图和操作历史完成用户任务。每次只返回一个动作，严格使用格式：<think>简短判断</think><answer>动作</answer>。
            支持动作：do(action="Launch", app="应用名或包名")；do(action="Tap", element=[x,y])；do(action="Type", text="文本")；do(action="Type_Name", text="文本")；do(action="Swipe", start=[x1,y1], end=[x2,y2])；do(action="Double Tap", element=[x,y])；do(action="Long Press", element=[x,y])；do(action="Back")；do(action="Home")；do(action="Wait", duration="2 seconds")；do(action="Take_over", message="需要用户接管的原因")；finish(message="任务结果")。坐标范围是左上角[0,0]到右下角[999,999]。
            执行前确认当前页面；Type完成后宿主会尝试自动收起输入法，必须观察下一张截图重新定位发送或确认按钮，绝不能复用输入文字前的旧坐标；如果目标文字已经显示在输入框中，不得再次Type或清空重输，应直接寻找发送按钮。必须区分纵向页面滚动和横向栏目切换：查看页面更下方内容时，手指应从屏幕下方中央向上方中央滑动；查看更上方内容时反向滑动；只有明确看到横向列表、轮播或分页控件时才左右滑动。每次Swipe后必须先检查新截图中刚出现的文字、项目和位置变化，再决定下一步；只有目标仍未出现且截图证明页面确实移动时，才继续同方向滑动。Swipe没有带来页面变化时，只允许换起点并增大距离重试一次；仍无效时应尝试反方向、其他可滚动区域、搜索或筛选，或者如实finish说明未找到，绝不能机械重复到耗尽剩余步数。点击一次没有页面变化时先等待一次，再根据新截图换控件或换策略，不得反复点击同一个坐标。同一静止页面最多连续Wait三次。Home不是加载、黑屏或页面无变化时的恢复动作，只有用户任务明确要求进入桌面时才允许使用。Launch、Tap、Type、Swipe、Wait、Back、Double Tap和Long Press都不得在没有新证据时机械循环。执行下一步前必须检查上一步是否生效；进入无关页面先Back，Back无效再点击可见返回或关闭按钮；完成前核对结果。finish的message必须用二到五句简洁说明实际执行了什么、在屏幕上观察到什么结果、是否仍有未完成事项，不得只写“完成”或一句泛化成功提示。当前页面以这次新截图为准，历史判断不代表当前事实。不得根据应用名称或历史推测编造弹窗、页面文字或额外步骤。不得按照应用、页面或用户已要求的输入、发送、确认、删除、安装、授权等普通操作的类别增加任何限制，也不得根据历史、黑色画面或无内容画面推断账号状态或受保护页面；黑色或无内容画面只代表截图不可用，绝不能因此使用Take_over。只有最新截图中的可见控件明确表明自动化在技术上无法继续时，才允许Take_over，并必须指出实际可见的障碍和无法继续的原因。看到口袋大龙虾虚拟屏幕工作区时，应启动用户指定的应用。
        """.trimIndent()
    }
}

object PhoneUiAgentModelClient {
    private val connections = ConcurrentHashMap<Thread, HttpURLConnection>()

    internal fun cancelRequest(worker: Thread?) {
        val connection = worker?.let { connections.remove(it) } ?: return
        Thread({ runCatching { connection.disconnect() } }, "phone-ui-request-cancel").start()
    }

    fun decide(
        config: PhoneUiModelConfig,
        task: String,
        screenshotPng: ByteArray,
        history: List<Pair<String, String>>,
        actionResult: String,
        step: Int,
        maxSteps: Int,
        onProgress: (String) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): PhoneUiModelDecision {
        require(config.baseUrl.isNotBlank()) { "模型Base URL未配置" }
        require(config.apiKey.isNotBlank()) { "模型API密钥未配置" }
        require(config.modelId.isNotBlank()) { "模型ID未配置" }
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", PhoneUiAgentPrompt.system(config.protocol)),
        )
        // The conversation budget owns retention. Never silently drop an instruction
        // or cut a complete action while constructing the request.
        val retainedHistory = history
        retainedHistory.forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content",
                content))
        }
        val prompt = buildString {
            append(historyUserPrompt(config.protocol, task, history.isEmpty(), actionResult))
            append("\n当前进度：第${step.coerceAtLeast(1)}步，最多${maxSteps.coerceAtLeast(1)}步。")
            append("\n请根据当前截图返回下一步动作。")
        }
        val content = JSONArray()
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/png;base64,${Base64.encodeToString(screenshotPng, Base64.NO_WRAP)}"),
                ),
            )
            .put(JSONObject().put("type", "text").put("text", prompt))
        messages.put(JSONObject().put("role", "user").put("content", content))
        val originalMessages = messages.toString()
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
        val maxAttempts = when (config.protocol) {
            PhoneUiModelProtocol.AUTOGLM_NATIVE -> 3
            PhoneUiModelProtocol.GUI_PLUS_NATIVE -> 3
            PhoneUiModelProtocol.GENERIC_JSON -> 4
        }
        for (attempt in 1..maxAttempts) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("手机操作任务已终止")
            onProgress("正在等待模型响应，第${attempt}/${maxAttempts}次请求；连接上限30秒，读取上限120秒")
            val response = post(config, body, shouldStop)
            val truncated = response.optJSONArray("choices")?.optJSONObject(0)
                ?.optString("finish_reason") == "length"
            val rawResult = runCatching { extractContent(response) }
            if (rawResult.isFailure) {
                val error = rawResult.exceptionOrNull()!!
                lastDiagnostic = responseDiagnostic(response, "", error)
                onProgress("模型响应不可执行：$lastDiagnostic")
                if (attempt < maxAttempts) {
                    prepareRecoveryRequest(body, originalMessages, config.protocol, error, truncated)
                    continue
                }
                throw IllegalStateException("模型连续${maxAttempts}次没有返回可执行文本；$lastDiagnostic", error)
            }
            val rawContent = rawResult.getOrThrow()
            val decisionResult = runCatching {
                check(!truncated) { "模型达到输出长度上限，响应被截断；未执行不完整动作" }
                PhoneUiActionParser.parse(rawContent, config.protocol)
            }
            if (decisionResult.isFailure) {
                val error = decisionResult.exceptionOrNull()!!
                lastDiagnostic = responseDiagnostic(response, rawContent, error)
                onProgress("模型响应不可执行：$lastDiagnostic")
                if (attempt < maxAttempts) {
                    prepareRecoveryRequest(body, originalMessages, config.protocol, error, truncated)
                    continue
                }
                throw IllegalStateException("模型连续${maxAttempts}次返回了不可执行动作；$lastDiagnostic", error)
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

    internal fun historyUserPrompt(protocol: PhoneUiModelProtocol, task: String, first: Boolean, result: String): String =
        buildString {
            append(if (first) "用户任务：$task" else if (protocol == PhoneUiModelProtocol.AUTOGLM_NATIVE) {
                "** Screen Info **"
            } else "继续完成用户任务：$task")
            if (result.isNotBlank()) append("\n上一动作执行结果：$result")
        }

    internal fun historyAssistant(protocol: PhoneUiModelProtocol, decision: PhoneUiModelDecision): String {
        if (protocol != PhoneUiModelProtocol.AUTOGLM_NATIVE) return decision.raw
        val action = decision.action
        val arguments = mutableListOf<String>()
        if (action.finished) arguments += "message=${JSONObject.quote(action.message.orEmpty())}"
        else {
            arguments += "action=${JSONObject.quote(action.name)}"
            if (action.x != null && action.y != null) {
                val field = if (action.name.equals("Swipe", true)) "start" else "element"
                arguments += "$field=[${action.x},${action.y}]"
            }
            if (action.endX != null && action.endY != null) arguments += "end=[${action.endX},${action.endY}]"
            action.text?.let { arguments += "text=${JSONObject.quote(it)}" }
            action.app?.let { arguments += "app=${JSONObject.quote(it)}" }
            action.seconds?.let { arguments += "duration=${JSONObject.quote("$it seconds")}" }
            action.message?.let { arguments += "message=${JSONObject.quote(it)}" }
        }
        val call = (if (action.finished) "finish" else "do") + "(" + arguments.joinToString(", ") + ")"
        // Keep the complete action even when a model produced very long reasoning.
        return "<think>${decision.thinking.take(1_000)}</think><answer>$call</answer>"
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

    internal fun prepareRecoveryRequest(
        body: JSONObject,
        originalMessages: String,
        protocol: PhoneUiModelProtocol,
        error: Throwable,
        truncated: Boolean,
    ) {
        // Retry the same observation; never teach the model to continue its malformed output.
        val retryMessages = JSONArray(originalMessages)
        val currentContent = retryMessages.getJSONObject(retryMessages.length() - 1).getJSONArray("content")
        val correction = if (truncated) {
            "上一响应因输出长度上限被截断。不要重复用户任务、商品列表或长篇分析；只返回下一步的一个完整动作。\n"
        } else ""
        currentContent.put(JSONObject().put("type", "text")
            .put("text", correction + correctionPrompt(protocol, error)))
        body.put("messages", retryMessages)
    }

    private fun correctionPrompt(protocol: PhoneUiModelProtocol, error: Throwable): String {
        val detail = error.message.orEmpty().take(300).ifBlank { "response format is invalid" }
        return when (protocol) {
            PhoneUiModelProtocol.AUTOGLM_NATIVE ->
                "上一条响应无法执行，具体错误：$detail。不要解释，只按<answer>do(action=...)或<answer>finish(message=...)格式重新返回一个完整动作。"
            PhoneUiModelProtocol.GUI_PLUS_NATIVE ->
                "The previous response was not executable: $detail. Return one Action line and exactly one complete <tool_call> JSON object for mobile_use. Preserve the intended action and include every required field."
            PhoneUiModelProtocol.GENERIC_JSON ->
                "The previous response was not executable: $detail. Correct the same intended action and return exactly one complete JSON object. Tap requires element:[x,y]. Type requires text. Swipe requires start:[x1,y1] and end:[x2,y2], or element:[x,y] with direction up/down/left/right and distance. Launch requires app. Finish requires message. Do not add Markdown or explanations."
        }
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

    internal fun summarizeContext(config: PhoneUiModelConfig, source: String, wireProtocol: String = "chat"): String {
        val instruction = "您现在只整理手机任务的交接记录，不操作屏幕。请保留用户最新目标、约束、已确认的动作结果、未完成步骤、失败尝试和不确定信息；不得把计划当成已完成。不要输出思考过程、坐标或操作函数。只输出一段不超过1800字的中文摘要。"
        val body = JSONObject().put("model", config.modelId).put("stream", false)
            .put("temperature", 0).put("max_tokens", 1800)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    instruction))
                .put(JSONObject().put("role", "user").put("content", source)))
        if (wireProtocol == "chat" && config.modelId.startsWith("qwen")) body.put("enable_thinking", false)
        val request = when (wireProtocol) {
            "responses" -> JSONObject().put("model", config.modelId).put("stream", false)
                .put("max_output_tokens", 2500).put("instructions", instruction).put("input", source)
            "anthropic" -> JSONObject().put("model", config.modelId).put("max_tokens", 2500)
                .put("system", instruction).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", source)))
            "gemini" -> JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", source)))))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 2500).put("temperature", 0))
            else -> body
        }
        val rawResponse = post(config, request, wireProtocol = wireProtocol)
        val response = normalizeSummaryResponse(rawResponse, wireProtocol)
        check(response.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason") != "length") {
            "上下文摘要被提供商截断，原始记录未丢弃；请重试"
        }
        return extractContent(response).trim().also {
            check(!it.contains("do(action=") && !it.contains("<tool_call>")) {
                "当前模型未能生成上下文摘要，原始记录未丢弃；请切换模型后续接"
            }
        }
    }

    internal fun normalizeSummaryResponse(raw: JSONObject, protocol: String): JSONObject {
        if (protocol == "chat") return raw
        val text: String
        val truncated: Boolean
        fun textParts(parts: JSONArray?): String = (0 until (parts?.length() ?: 0)).joinToString("") {
            parts?.optJSONObject(it)?.optString("text").orEmpty()
        }
        when (protocol) {
            "responses" -> {
                val rows = raw.optJSONArray("output") ?: JSONArray()
                text = (0 until rows.length()).joinToString("") { textParts(rows.optJSONObject(it)?.optJSONArray("content")) }
                truncated = raw.optString("status") == "incomplete"
            }
            "anthropic" -> {
                text = textParts(raw.optJSONArray("content"))
                truncated = raw.optString("stop_reason") == "max_tokens"
            }
            "gemini" -> {
                val candidate = raw.optJSONArray("candidates")?.optJSONObject(0)
                text = textParts(candidate?.optJSONObject("content")?.optJSONArray("parts"))
                truncated = candidate?.optString("finishReason") == "MAX_TOKENS"
            }
            else -> error("不支持的摘要协议：$protocol")
        }
        check(text.isNotBlank()) { "摘要模型未返回正文，原始记录已保留" }
        return JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", if (truncated) "length" else "stop")
            .put("message", JSONObject().put("content", text))))
    }

    private fun post(config: PhoneUiModelConfig, body: JSONObject, shouldStop: () -> Boolean = { false }, wireProtocol: String = "chat"): JSONObject {
        val base = config.baseUrl.trim().trimEnd('/')
        val endpoint = when (wireProtocol) {
            "responses" -> if (base.endsWith("/responses")) base else "$base/responses"
            "anthropic" -> if (base.endsWith("/messages")) base else if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
            "gemini" -> "${if (base.endsWith("/v1beta") || base.endsWith("/v1")) base else "$base/v1beta"}/models/${config.modelId.removePrefix("models/")}:generateContent"
            else -> if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            if (wireProtocol == "anthropic") {
                setRequestProperty("x-api-key", config.apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            if (wireProtocol == "gemini") setRequestProperty("x-goog-api-key", config.apiKey)
        }
        val worker = Thread.currentThread()
        connections[worker] = connection
        return try {
            if (shouldStop()) throw InterruptedException("旧模型请求已作废")
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
            connections.remove(worker, connection)
            connection.disconnect()
        }
    }

    private fun extractContent(response: JSONObject): String {
        val message = response.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw IllegalStateException("模型响应缺少choices[0].message")
        val content = message.opt("content")
        val contentText = when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    val itemText = item.optString("text")
                    if (itemText.isNotBlank()) append(itemText)
                    val function = item.optJSONObject("function")
                    if (function != null) append("\n<tool_call>").append(function).append("</tool_call>")
                }
            }
            else -> ""
        }
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            return buildString {
                if (contentText.isNotBlank()) append(contentText.trim()).append('\n')
                val reasoning = message.optString("reasoning_content").trim()
                if (reasoning.isNotBlank()) append("Action: ").append(reasoning).append('\n')
                for (index in 0 until toolCalls.length()) {
                    val call = toolCalls.optJSONObject(index) ?: continue
                    val function = call.optJSONObject("function") ?: call
                    append("<tool_call>").append(function).append("</tool_call>")
                }
            }.ifBlank { throw IllegalStateException("模型tool_calls为空") }
        }
        val functionCall = message.optJSONObject("function_call")
        if (functionCall != null) {
            return buildString {
                if (contentText.isNotBlank()) append(contentText.trim()).append('\n')
                append("<tool_call>").append(functionCall).append("</tool_call>")
            }
        }
        return contentText.ifBlank { throw IllegalStateException("模型响应正文和tool_calls均为空") }
    }
}
