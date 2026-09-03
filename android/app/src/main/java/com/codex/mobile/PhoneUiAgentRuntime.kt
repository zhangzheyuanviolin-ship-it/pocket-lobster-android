package com.codex.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.ai.assistance.showerclient.ShellCommandResult
import com.ai.assistance.showerclient.ShowerController
import com.ai.assistance.showerclient.ShowerEnvironment
import com.ai.assistance.showerclient.ShowerServerManager
import com.openminis.app.accessibility.MinisAccessibilityService
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

enum class PhoneUiScreenMode(val value: String) {
    MAIN("main"),
    VIRTUAL("virtual"),
}

object PhoneUiShowerRuntime {
    val controller = ShowerController()

    fun initialize(context: Context) {
        ShowerEnvironment.shellRunner = com.ai.assistance.showerclient.ShellRunner { command, _ ->
            val result = ShizukuController.executeShellCommand(command)
            ShellCommandResult(
                success = result.success,
                stdout = result.stdout,
                stderr = result.stderr.ifBlank { result.error.orEmpty() },
                exitCode = result.exitCode,
            )
        }
        ShowerServerManager.additionalTargetPackages = setOf(context.packageName)
    }
}

object PhoneUiAgentRuntime {
    private const val TAG = "PhoneUiAgentRuntime"
    private const val STATE_FILE = "phone-ui-agent/task-state.json"
    private const val HISTORY_FILE = "phone-ui-agent/task-history.json"
    private const val MAX_EVENTS = 240
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocketlobster-phone-ui-agent").apply { isDaemon = true }
    }
    private val keepAliveExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pocketlobster-phone-ui-keepalive").apply { isDaemon = true }
    }
    private val lock = Any()
    private var applicationContext: Context? = null
    private var state: JSONObject = emptyState()
    private var activeFuture: Future<*>? = null
    private var keepAliveFuture: ScheduledFuture<*>? = null
    @Volatile private var paused = false
    @Volatile private var cancelled = false

    fun initialize(context: Context) {
        synchronized(lock) {
            applicationContext = context.applicationContext
            PhoneUiShowerRuntime.initialize(context.applicationContext)
            state = readState(context.applicationContext)
            if (state.optString("status") in setOf("starting", "running", "paused")) {
                state.put("status", "interrupted")
                    .put("statusText", "应用进程曾中断，请重新发送任务")
                    .put("updatedAt", Instant.now().toString())
                appendEventLocked("error", "任务已中断", "宿主进程重启，原任务没有继续执行。")
                persistLocked()
            }
        }
    }

    fun startTask(context: Context, task: String, mode: PhoneUiScreenMode, maxSteps: Int): JSONObject {
        val cleanTask = task.trim()
        require(cleanTask.isNotEmpty()) { "任务内容不能为空" }
        require(PhoneUiAgentModelStore.loadCurrent(context) != null) { "请先配置手机操作智能体模型" }
        require(ShizukuController.isBridgeEnabled(context)) { "Shizuku系统Shell通道已关闭" }
        require(ShizukuController.isServiceRunning()) { "Shizuku服务未运行" }
        require(ShizukuController.hasPermission()) { "口袋大龙虾尚未获得Shizuku授权" }
        synchronized(lock) {
            if (activeFuture?.isDone == false) throw IllegalStateException("已有手机操作任务正在运行")
            archiveCurrentLocked()
            runCatching { PhoneUiShowerRuntime.controller.shutdown() }
            cancelled = false
            paused = false
            state = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("task", cleanTask)
                .put("mode", mode.value)
                .put("maxSteps", maxSteps.coerceIn(1, 100))
                .put("step", 0)
                .put("status", "starting")
                .put("statusText", "正在初始化手机操作环境")
                .put("createdAt", Instant.now().toString())
                .put("updatedAt", Instant.now().toString())
                .put("events", JSONArray())
            appendEventLocked("status", "任务已创建", "模式：${if (mode == PhoneUiScreenMode.MAIN) "主屏幕" else "虚拟屏幕"}；最大步数：${maxSteps.coerceIn(1, 100)}")
            persistLocked()
            CodexForegroundService.ensureStarted(context)
            val appContext = context.applicationContext
            activeFuture = executor.submit { runTask(appContext) }
            return snapshotLocked()
        }
    }

    fun snapshot(): JSONObject = synchronized(lock) { snapshotLocked() }

    fun history(): JSONArray = synchronized(lock) {
        val context = applicationContext ?: return@synchronized JSONArray()
        readHistory(context)
    }

    fun clearHistory() = synchronized(lock) {
        val context = applicationContext ?: return@synchronized
        historyFile(context).writeText("[]")
    }

    fun pause(): JSONObject = synchronized(lock) {
        if (state.optString("status") == "running") {
            paused = true
            state.put("status", "paused").put("statusText", "任务已暂停，用户可以接管屏幕")
            appendEventLocked("status", "任务已暂停", "点击继续后，智能体将从当前页面重新截图判断。")
            persistLocked()
        }
        snapshotLocked()
    }

    fun resume(): JSONObject = synchronized(lock) {
        if (state.optString("status") == "paused") {
            paused = false
            state.put("status", "running").put("statusText", "任务继续执行")
            appendEventLocked("status", "任务已继续", "智能体正在重新观察当前屏幕。")
            persistLocked()
        }
        snapshotLocked()
    }

    fun cancel(): JSONObject = synchronized(lock) {
        cancelled = true
        paused = false
        activeFuture?.cancel(true)
        stopKeepAliveLocked()
        if (state.optString("status") in setOf("starting", "running", "paused")) {
            state.put("status", "cancelled").put("statusText", "任务已由用户终止")
            appendEventLocked("status", "任务已终止", "后续模型请求和屏幕动作均已停止。")
            persistLocked()
        }
        snapshotLocked()
    }

    fun hasVirtualDisplay(): Boolean = PhoneUiShowerRuntime.controller.getDisplayId()?.let { it > 0 } == true

    private fun runTask(context: Context) = runBlocking {
        try {
            val config = PhoneUiAgentModelStore.loadCurrent(context)
                ?: throw IllegalStateException("当前没有手机操作智能体模型")
            val mode = PhoneUiScreenMode.entries.firstOrNull { it.value == synchronized(lock) { state.optString("mode") } }
                ?: PhoneUiScreenMode.MAIN
            updateStatus("starting", "正在启动Shizuku屏幕控制服务")
            val serverReady = ShowerServerManager.ensureServerStarted(context)
            if (!serverReady) {
                val target = if (mode == PhoneUiScreenMode.MAIN) "主屏幕控制服务" else "虚拟屏幕服务"
                val detail = ShowerServerManager.lastError.ifBlank { "未收到服务握手" }
                throw IllegalStateException("$target 未能启动：$detail")
            }
            val screenReady = if (mode == PhoneUiScreenMode.MAIN) {
                PhoneUiVirtualDisplayCapture.detach()
                PhoneUiShowerRuntime.controller.prepareMainDisplay(context)
            } else {
                val metrics = context.resources.displayMetrics
                PhoneUiShowerRuntime.controller.ensureDisplay(
                    context,
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.densityDpi,
                    3_000,
                )
            }
            if (!screenReady) throw IllegalStateException(if (mode == PhoneUiScreenMode.MAIN) "主屏幕控制链初始化失败" else "虚拟屏幕创建失败")
            val displayId = PhoneUiShowerRuntime.controller.getDisplayId()
                ?: throw IllegalStateException("屏幕服务没有返回displayId")
            if (mode == PhoneUiScreenMode.VIRTUAL) {
                PhoneUiVirtualDisplayCapture.attach(context)
            }
            appendEvent("status", "屏幕环境已就绪", "displayId=$displayId；模型=${config.displayName}")
            if (mode == PhoneUiScreenMode.MAIN) startKeepAlive()
            updateStatus("running", "正在观察屏幕并规划第一步")

            val task = synchronized(lock) { state.optString("task") }
            val maxSteps = synchronized(lock) { state.optInt("maxSteps", 25) }
            val history = mutableListOf<Pair<String, String>>()
            var actionResult = ""
            for (step in 1..maxSteps) {
                awaitRunnable()
                if (cancelled) return@runBlocking
                updateStep(step, "正在截取当前屏幕")
                val screenshot = captureScreenshot(context, mode)
                val dimensions = imageDimensions(screenshot)
                updateStep(step, "模型正在判断下一步操作")
                val decision = PhoneUiAgentModelClient.decide(config, task, screenshot, history, actionResult)
                awaitRunnable()
                if (cancelled) return@runBlocking
                if (decision.thinking.isNotBlank()) {
                    appendEvent("thinking", "第${step}步判断", decision.thinking)
                }
                val currentPrompt = if (step == 1) "用户任务：$task" else "上一动作结果：$actionResult；继续任务：$task"
                history += "user" to currentPrompt
                history += "assistant" to decision.raw
                if (decision.action.finished) {
                    val message = decision.action.message.orEmpty().ifBlank { "任务已完成" }
                    appendEvent("result", "任务完成", message)
                    updateStatus("completed", message)
                    return@runBlocking
                }
                if (requiresTakeover(decision.action)) {
                    val message = decision.action.message.orEmpty().ifBlank { "当前步骤需要用户手动完成" }
                    paused = true
                    appendEvent("takeover", "等待用户接管", message)
                    updateStatus("paused", message)
                    awaitRunnable()
                    if (cancelled) return@runBlocking
                    actionResult = "用户已经完成手动操作并选择继续，请重新观察当前页面后决定下一步。"
                    continue
                }
                actionResult = executeAction(context, decision.action, dimensions.first, dimensions.second)
                appendEvent("action", "第${step}步：${decision.action.name}", actionResult)
                updateStep(step, "动作已执行，正在等待页面稳定")
                delay(650)
            }
            appendEvent("error", "达到最大步数", "任务尚未明确完成，已停止继续操作。")
            updateStatus("step_limit", "已达到最大步数，任务停止")
        } catch (error: Throwable) {
            if (!cancelled) {
                Log.e(TAG, "Phone UI task failed", error)
                appendEvent("error", "任务异常", error.message ?: error.javaClass.simpleName)
                updateStatus("failed", error.message ?: "任务执行失败")
            }
        }
    }

    private suspend fun captureScreenshot(context: Context, mode: PhoneUiScreenMode): ByteArray {
        if (mode == PhoneUiScreenMode.VIRTUAL) {
            validScreenshot(PhoneUiVirtualDisplayCapture.capturePng(6_000), "virtual-video")?.let { return it }
            appendEvent("status", "正在恢复屏幕截图", "视频帧暂时不可用，正在重新连接虚拟屏幕画面。")
            PhoneUiVirtualDisplayCapture.attach(context)
            validScreenshot(PhoneUiVirtualDisplayCapture.capturePng(6_000), "virtual-video-reconnected")?.let { return it }
            captureViaAccessibility(PhoneUiShowerRuntime.controller.getDisplayId() ?: 0)
                ?.let { validScreenshot(it, "virtual-accessibility") }
                ?.let { return it }
            repeat(3) { attempt ->
                validScreenshot(
                    PhoneUiShowerRuntime.controller.requestScreenshot(4_000),
                    "virtual-shower-" + (attempt + 1),
                )?.let { return it }
                delay(350L * (attempt + 1))
            }
            throw IllegalStateException("虚拟屏幕截图链路持续无有效画面，请打开虚拟屏幕查看目标应用状态后重试")
        }

        repeat(3) { attempt ->
            captureViaShizuku()
                ?.let { validScreenshot(it, "main-shizuku-" + (attempt + 1)) }
                ?.let { return it }
            validScreenshot(
                PhoneUiShowerRuntime.controller.requestScreenshot(4_000),
                "main-shower-" + (attempt + 1),
            )?.let { return it }
            delay(350L * (attempt + 1))
        }
        captureViaAccessibility(0)
            ?.let { validScreenshot(it, "main-accessibility") }
            ?.let { return it }
        throw IllegalStateException("主屏幕截图链路持续无有效画面，请确认Shizuku服务运行并保持目标应用在前台后重试")
    }

    private fun captureViaShizuku(): ByteArray? {
        val result = ShizukuController.executeShellCommandBinary("screencap -p")
        if (!result.success) {
            Log.w(TAG, "Shizuku screencap failed: code=" + result.errorCode + " detail=" + result.error)
            return null
        }
        return result.stdout
    }

    private fun captureViaAccessibility(displayId: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val service = MinisAccessibilityService.getInstance() ?: return null
        val shot = service.captureScreenshot(displayId)
        val bitmap = shot.bitmap
        if (bitmap == null) {
            Log.w(TAG, "Accessibility screenshot failed: code=" + shot.errorCode + " detail=" + shot.errorMessage)
            return null
        }
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) null else output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun validScreenshot(bytes: ByteArray?, source: String): ByteArray? {
        if (bytes == null || bytes.size <= 1_024) {
            Log.w(TAG, "Screenshot source returned no usable data: source=" + source + " bytes=" + (bytes?.size ?: 0))
            return null
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth < 100 || options.outHeight < 100) {
            Log.w(TAG, "Screenshot source returned invalid dimensions: source=" + source + " width=" + options.outWidth + " height=" + options.outHeight)
            return null
        }
        Log.i(TAG, "Screenshot ready: source=" + source + " bytes=" + bytes.size + " width=" + options.outWidth + " height=" + options.outHeight)
        return bytes
    }

    private suspend fun executeAction(context: Context, action: PhoneUiAction, width: Int, height: Int): String {
        val controller = PhoneUiShowerRuntime.controller
        fun x(value: Int?): Int = ((value ?: 500).coerceIn(0, 999) / 999.0 * (width - 1)).toInt()
        fun y(value: Int?): Int = ((value ?: 500).coerceIn(0, 999) / 999.0 * (height - 1)).toInt()
        return when (action.name.trim().lowercase()) {
            "launch" -> {
                val target = resolvePackage(context, action.app.orEmpty())
                if (!controller.launchApp(target)) throw IllegalStateException("无法启动应用：${action.app}")
                "已启动${action.app}（$target）"
            }
            "tap" -> {
                if (!controller.tap(x(action.x), y(action.y))) throw IllegalStateException("点击动作失败")
                "已点击坐标${x(action.x)},${y(action.y)}"
            }
            "double tap" -> {
                val px = x(action.x); val py = y(action.y)
                if (!controller.tap(px, py)) throw IllegalStateException("第一次点击失败")
                delay(110)
                if (!controller.tap(px, py)) throw IllegalStateException("第二次点击失败")
                "已双击坐标$px,$py"
            }
            "long press" -> {
                val px = x(action.x); val py = y(action.y)
                if (!controller.touchDown(px, py)) throw IllegalStateException("长按按下失败")
                delay(900)
                if (!controller.touchUp(px, py)) throw IllegalStateException("长按抬起失败")
                "已长按坐标$px,$py"
            }
            "type", "type_name" -> {
                val value = action.text.orEmpty()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("phone-ui-agent", value))
                controller.keyWithMeta(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
                controller.key(KeyEvent.KEYCODE_DEL)
                if (!controller.key(KeyEvent.KEYCODE_PASTE)) throw IllegalStateException("剪贴板粘贴失败")
                "已向当前输入框粘贴${value.length}个字符"
            }
            "swipe" -> {
                if (!controller.swipe(x(action.x), y(action.y), x(action.endX), y(action.endY), 420)) {
                    throw IllegalStateException("滑动动作失败")
                }
                "已从${x(action.x)},${y(action.y)}滑动到${x(action.endX)},${y(action.endY)}"
            }
            "back" -> {
                if (!controller.key(KeyEvent.KEYCODE_BACK)) throw IllegalStateException("返回动作失败")
                "已返回上一页"
            }
            "home" -> {
                if (!controller.key(KeyEvent.KEYCODE_HOME)) throw IllegalStateException("回到桌面动作失败")
                "已返回系统桌面"
            }
            "wait" -> {
                val seconds = (action.seconds ?: 1.0).coerceIn(0.2, 10.0)
                delay((seconds * 1_000).toLong())
                "已等待${seconds}秒"
            }
            "take_over", "interact" -> throw UserTakeoverRequired(action.message ?: "需要用户接管")
            "note", "call_api" -> "已记录模型阶段信息：${action.message.orEmpty()}"
            else -> throw IllegalArgumentException("不支持的动作：${action.name}")
        }
    }

    private suspend fun awaitRunnable() {
        while (paused && !cancelled) delay(200)
    }

    private fun resolvePackage(context: Context, raw: String): String {
        val target = raw.trim()
        require(target.isNotEmpty()) { "Launch动作缺少应用名称" }
        if (target.contains('.')) {
            val exists = runCatching { context.packageManager.getApplicationInfo(target, 0) }.isSuccess
            if (exists) return target
        }
        val normalized = target.lowercase()
        @Suppress("DEPRECATION")
        val matches = context.packageManager.getInstalledApplications(0).mapNotNull { info: ApplicationInfo ->
            val label = context.packageManager.getApplicationLabel(info).toString()
            if (label.equals(target, true) || label.lowercase().contains(normalized)) info.packageName else null
        }
        return matches.firstOrNull() ?: throw IllegalArgumentException("未找到应用：$target，请让模型返回准确包名")
    }

    private fun imageDimensions(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IllegalStateException("截图尺寸无效")
        return options.outWidth to options.outHeight
    }

    private fun requiresTakeover(action: PhoneUiAction): Boolean =
        action.name.equals("Take_over", true) || action.name.equals("Interact", true)

    private fun updateStep(step: Int, text: String) = synchronized(lock) {
        state.put("step", step).put("status", if (paused) "paused" else "running").put("statusText", text).put("updatedAt", Instant.now().toString())
        persistLocked()
    }

    private fun updateStatus(status: String, text: String) = synchronized(lock) {
        state.put("status", status).put("statusText", text).put("updatedAt", Instant.now().toString())
        if (status !in setOf("starting", "running", "paused")) stopKeepAliveLocked()
        persistLocked()
    }

    private fun startKeepAlive() = synchronized(lock) {
        stopKeepAliveLocked()
        keepAliveFuture = keepAliveExecutor.scheduleAtFixedRate(
            {
                if (!cancelled) runCatching { runBlocking { PhoneUiShowerRuntime.controller.key(KeyEvent.KEYCODE_UNKNOWN) } }
            },
            5,
            5,
            TimeUnit.SECONDS,
        )
    }

    private fun stopKeepAliveLocked() {
        keepAliveFuture?.cancel(false)
        keepAliveFuture = null
    }

    private fun appendEvent(type: String, title: String, detail: String) = synchronized(lock) {
        appendEventLocked(type, title, detail)
        persistLocked()
    }

    private fun appendEventLocked(type: String, title: String, detail: String) {
        val events = state.optJSONArray("events") ?: JSONArray().also { state.put("events", it) }
        events.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", type)
                .put("title", title)
                .put("detail", detail)
                .put("timestamp", Instant.now().toString()),
        )
        while (events.length() > MAX_EVENTS) events.remove(0)
        state.put("updatedAt", Instant.now().toString())
    }

    private fun snapshotLocked(): JSONObject = JSONObject(state.toString())

    private fun persistLocked() {
        val context = applicationContext ?: return
        val target = File(context.filesDir, STATE_FILE)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(state.toString())
        if (!temp.renameTo(target)) target.writeText(state.toString())
    }

    private fun readState(context: Context): JSONObject {
        val file = File(context.filesDir, STATE_FILE)
        return runCatching { JSONObject(file.readText()) }.getOrElse { emptyState() }
    }

    private fun archiveCurrentLocked() {
        val context = applicationContext ?: return
        if (state.optString("id").isBlank()) return
        val history = readHistory(context)
        history.put(JSONObject(state.toString()))
        while (history.length() > 50) history.remove(0)
        historyFile(context).writeText(history.toString())
    }

    private fun readHistory(context: Context): JSONArray = runCatching {
        JSONArray(historyFile(context).readText())
    }.getOrElse { JSONArray() }

    private fun historyFile(context: Context): File = File(context.filesDir, HISTORY_FILE).apply {
        parentFile?.mkdirs()
        if (!exists()) writeText("[]")
    }

    private fun emptyState(): JSONObject = JSONObject()
        .put("id", "")
        .put("task", "")
        .put("mode", PhoneUiScreenMode.MAIN.value)
        .put("maxSteps", 25)
        .put("step", 0)
        .put("status", "idle")
        .put("statusText", "暂无手机操作任务")
        .put("events", JSONArray())
}

private class UserTakeoverRequired(message: String) : RuntimeException(message)
