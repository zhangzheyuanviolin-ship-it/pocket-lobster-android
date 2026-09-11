package com.codex.mobile

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
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
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
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
    private val KNOWN_APP_ALIASES = mapOf(
        "tv.danmaku.bili" to setOf("哔哩哔哩", "哔哩哔哩动画", "bilibili", "b站"),
        "com.taobao.taobao" to setOf("淘宝", "手机淘宝", "taobao"),
        "com.jingdong.app.mall" to setOf("京东", "京东商城", "jd"),
        "com.larus.nova" to setOf("豆包"),
        "com.ss.android.ugc.aweme" to setOf("抖音", "douyin"),
        "com.xingin.xhs" to setOf("小红书"),
        "com.tencent.mm" to setOf("微信", "wechat"),
        "com.tencent.mobileqq" to setOf("qq", "腾讯qq"),
        "com.sankuai.meituan" to setOf("美团"),
        "com.autonavi.minimap" to setOf("高德地图", "高德"),
        "com.baidu.baidumap" to setOf("百度地图"),
    )
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
    @Volatile private var activeThread: Thread? = null
    private var keepAliveFuture: ScheduledFuture<*>? = null
    @Volatile private var paused = false
    @Volatile private var cancelled = false
    private val observationRevision = AtomicLong()
    @Volatile private var pendingInstruction: Pair<String, Int>? = null
    private var workerRunning = false

    fun initialize(context: Context) {
        synchronized(lock) {
            applicationContext = context.applicationContext
            PhoneUiShowerRuntime.initialize(context.applicationContext)
            state = readState(context.applicationContext)
            if (state.optString("status") in setOf("starting", "running", "paused")) {
                state.put("status", "interrupted")
                    .put("statusText", "应用进程曾中断，请重新发送任务")
                    .put("error", "应用进程曾中断，请重新发送任务")
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
            if (workerRunning) {
                throw IllegalStateException("已有手机操作任务正在运行，taskId=${state.optString("id")}")
            }
            archiveCurrentLocked()
            cancelled = false
            paused = false
            observationRevision.incrementAndGet()
            state = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("task", cleanTask)
                .put("initialTask", cleanTask)
                .put("mode", mode.value)
                .put("maxSteps", maxSteps.coerceIn(1, 100))
                .put("step", 0)
                .put("round", 1)
                .put("status", "starting")
                .put("statusText", "正在初始化手机操作环境")
                .put("createdAt", Instant.now().toString())
                .put("updatedAt", Instant.now().toString())
                .put("events", JSONArray())
            appendEventLocked("status", "任务已创建", "模式：${if (mode == PhoneUiScreenMode.MAIN) "主屏幕" else "虚拟屏幕"}；最大步数：${maxSteps.coerceIn(1, 100)}")
            persistLocked()
            CodexForegroundService.ensureStarted(context)
            launchWorkerLocked(context.applicationContext, continuing = false)
            return snapshotLocked()
        }
    }

    fun continueTask(context: Context, task: String, maxSteps: Int): JSONObject = synchronized(lock) {
        require(state.optString("id").isNotBlank()) { "请先新建会话" }
        require(task.isNotBlank()) { "补充指令不能为空" }
        require(pendingInstruction == null) { "上一条补充指令正在接收，请稍候" }
        pendingInstruction = task.trim() to maxSteps.coerceIn(1, 100)
        observationRevision.incrementAndGet()
        paused = false
        PhoneUiAgentModelClient.cancelRequest(activeThread)
        state.put("statusText", "补充指令已接收，正在结束旧请求；不会执行旧请求返回的动作")
        appendEventLocked("user", "收到补充指令", task.trim())
        persistLocked()
        if (!workerRunning) launchWorkerLocked(context.applicationContext, continuing = true)
        snapshotLocked()
    }

    fun selectConversation(taskId: String): JSONObject = synchronized(lock) {
        if (state.optString("id") == taskId) return@synchronized snapshotLocked()
        require(!workerRunning) { "请先终止当前任务并等待执行结束" }
        val selected = snapshot(taskId)
        archiveCurrentLocked()
        state = selected
        persistLocked()
        snapshotLocked()
    }

    fun newConversation(): JSONObject = synchronized(lock) {
        require(!workerRunning) { "请先终止当前任务并等待执行结束" }
        archiveCurrentLocked()
        state = emptyState()
        persistLocked()
        snapshotLocked()
    }

    // A single worker owns all actions. A new instruction invalidates the old
    // observation immediately, but changes round state only after old I/O exits.
    private fun launchWorkerLocked(context: Context, continuing: Boolean) {
        workerRunning = true
        activeFuture = executor.submit {
            activeThread = Thread.currentThread()
            var nextIsContinuation = continuing
            try {
                while (true) {
                    synchronized(lock) {
                        pendingInstruction?.let { (instruction, limit) ->
                            if (!state.has("initialTask")) state.put("initialTask", state.optString("task"))
                            if (!state.has("conversationContext")) PhoneUiConversationContext.fromState(state).save(state)
                            val rounds = state.optJSONArray("rounds") ?: JSONArray().also { state.put("rounds", it) }
                            rounds.put(JSONObject().put("round", state.optInt("round", 1))
                                .put("task", state.optString("task")).put("status", if (state.optString("status") in setOf("starting", "running", "paused")) "superseded" else state.optString("status"))
                                .put("events", state.optJSONArray("events") ?: JSONArray()))
                            state.put("events", JSONArray())
                            state.put("task", instruction).put("round", state.optInt("round", 1) + 1)
                                .put("step", 0).put("maxSteps", limit)
                                .put("status", "starting").put("statusText", "正在承接会话上下文")
                            state.remove("error")
                            state.remove("result")
                            pendingInstruction = null
                            cancelled = false
                            paused = false
                            Thread.interrupted()
                            appendEventLocked("user", "第${state.optInt("round")}轮指令", instruction)
                            persistLocked()
                        }
                    }
                    if (!cancelled) runTask(context, nextIsContinuation)
                    synchronized(lock) {
                        if (pendingInstruction == null) {
                            workerRunning = false
                            activeThread = null
                            return@submit
                        }
                    }
                    nextIsContinuation = true
                }
            } finally {
                synchronized(lock) {
                    // Normal return already released ownership while holding lock.
                    if (activeThread === Thread.currentThread()) {
                        activeThread = null
                        workerRunning = false
                    }
                }
            }
        }
    }

    fun snapshot(taskId: String? = null): JSONObject = synchronized(lock) {
        val expected = taskId.orEmpty().trim()
        if (expected.isEmpty() || state.optString("id") == expected) return@synchronized snapshotLocked()
        val context = applicationContext ?: throw IllegalStateException("手机操作运行时尚未初始化")
        val history = readHistory(context)
        for (index in history.length() - 1 downTo 0) {
            val item = history.optJSONObject(index) ?: continue
            if (item.optString("id") == expected) return@synchronized JSONObject(item.toString())
        }
        throw IllegalArgumentException("未找到手机操作任务：$expected")
    }

    fun uiSnapshot(): JSONObject = synchronized(lock) {
        // The 500ms UI poll does not need a second copy of the model's full history.
        val visible = JSONObject()
        state.keys().forEach { key -> if (key != "conversationContext") visible.put(key, state.get(key)) }
        JSONObject(visible.toString())
    }

    fun history(): JSONArray = synchronized(lock) {
        val context = applicationContext ?: return@synchronized JSONArray()
        val rows = readHistory(context)
        val id = state.optString("id")
        if (id.isNotBlank()) {
            for (index in rows.length() - 1 downTo 0) {
                if (rows.optJSONObject(index)?.optString("id") == id) rows.remove(index)
            }
            rows.put(snapshotLocked())
        }
        rows
    }

    fun clearHistory() = synchronized(lock) {
        val context = applicationContext ?: return@synchronized
        historyFile(context).writeText("[]")
        PhoneUiObservationJournal.clear(context)
    }

    fun pause(taskId: String? = null): JSONObject = synchronized(lock) {
        requireCurrentTaskLocked(taskId)
        if (state.optString("status") in setOf("starting", "running")) {
            paused = true
            observationRevision.incrementAndGet()
            state.put("status", "paused").put("statusText", "任务已暂停，用户可以接管屏幕")
            appendEventLocked("status", "任务已暂停", "点击继续后，智能体将从当前页面重新截图判断。")
            persistLocked()
        }
        snapshotLocked()
    }

    fun resume(taskId: String? = null): JSONObject = synchronized(lock) {
        requireCurrentTaskLocked(taskId)
        if (state.optString("status") == "paused") {
            paused = false
            observationRevision.incrementAndGet()
            state.put("status", "running").put("statusText", "任务继续执行")
            appendEventLocked("status", "任务已继续", "智能体正在重新观察当前屏幕。")
            persistLocked()
        }
        snapshotLocked()
    }

    fun cancel(taskId: String? = null): JSONObject = synchronized(lock) {
        requireCurrentTaskLocked(taskId)
        cancelled = true
        pendingInstruction = null
        paused = false
        // Future.cancel marks done before blocking I/O has exited. Keep ownership until
        // the worker really returns, otherwise an old task can overwrite its successor.
        activeThread?.interrupt()
        PhoneUiAgentModelClient.cancelRequest(activeThread)
        stopKeepAliveLocked()
        if (state.optString("status") in setOf("starting", "running", "paused")) {
            state.put("status", "cancelled").put("statusText", "任务已由用户终止")
                .put("error", "任务已由用户终止")
            appendEventLocked("status", "任务已终止", "后续模型请求和屏幕动作均已停止。")
            persistLocked()
        }
        snapshotLocked()
    }

    fun hasVirtualDisplay(): Boolean = PhoneUiShowerRuntime.controller.getDisplayId()?.let { it > 0 } == true

    private fun runTask(context: Context, continuing: Boolean = false) = runBlocking {
        var taskMode: PhoneUiScreenMode? = null
        try {
            val config = PhoneUiAgentModelStore.loadCurrent(context)
                ?: throw IllegalStateException("当前没有手机操作智能体模型")
            val mode = PhoneUiScreenMode.entries.firstOrNull { it.value == synchronized(lock) { state.optString("mode") } }
                ?: PhoneUiScreenMode.MAIN
            taskMode = mode
            updateStatus("starting", "正在启动Shizuku屏幕控制服务")
            val serverReady = ShowerServerManager.ensureServerStarted(context)
            if (!serverReady) {
                val target = if (mode == PhoneUiScreenMode.MAIN) "主屏幕控制服务" else "虚拟屏幕服务"
                val detail = ShowerServerManager.lastError.ifBlank { "未收到服务握手" }
                throw IllegalStateException("$target 未能启动：$detail")
            }
            val previousDisplayId = PhoneUiShowerRuntime.controller.getDisplayId()?.takeIf { it > 0 }
            val screenReady = if (mode == PhoneUiScreenMode.MAIN) {
                PhoneUiVirtualDisplayCapture.detach()
                PhoneUiShowerRuntime.controller.prepareMainDisplay(context)
            } else {
                val metrics = context.resources.displayMetrics
                val existingSize = if (previousDisplayId != null) PhoneUiShowerRuntime.controller.getVideoSize() else null
                PhoneUiShowerRuntime.controller.ensureDisplay(
                    context,
                    existingSize?.first ?: metrics.widthPixels,
                    existingSize?.second ?: metrics.heightPixels,
                    metrics.densityDpi,
                    3_000,
                )
            }
            if (!screenReady) throw IllegalStateException(if (mode == PhoneUiScreenMode.MAIN) "主屏幕控制链初始化失败" else "虚拟屏幕创建失败")
            val displayId = PhoneUiShowerRuntime.controller.getDisplayId()
                ?: throw IllegalStateException("屏幕服务没有返回displayId")
            if (mode == PhoneUiScreenMode.VIRTUAL) {
                if (!PhoneUiVirtualDisplayCapture.attach(context)) {
                    throw IllegalStateException("虚拟屏幕原生视频渲染器初始化失败")
                }
                val task = synchronized(lock) { state.optString("task") }
                val target = resolveTaskTargetApp(context, task)
                    ?: (if (continuing) launchableApps(context).firstOrNull {
                        it.packageName == synchronized(lock) { state.optString("targetPackage") }
                    } else null)
                    ?: resolveTaskTargetApp(context, synchronized(lock) { state.optString("initialTask") })
                if (target != null) synchronized(lock) { state.put("targetPackage", target.packageName) }
                awaitRunnable()
                if (cancelled || pendingInstruction != null) return@runBlocking
                val prewarmState = if (target != null) {
                    PhoneUiShowerRuntime.controller.syncDisplay(target.packageName)
                } else JSONObject()
                val targetAlreadyAvailable = target != null && (
                    prewarmState.optJSONObject("tasks")?.has("restoredTaskId") == true ||
                        taskExistsOnDisplay(prewarmState, target.packageName, displayId)
                    )
                if (prewarmState.optJSONObject("tasks")?.has("restoredTaskId") == true) delay(650)
                if (continuing && previousDisplayId == displayId) {
                    // Continue on the user's current screen without relaunching its app.
                } else if (targetAlreadyAvailable) {
                    appendEvent("status", "正在恢复目标应用", "${target?.label}已在虚拟屏幕displayId=${displayId}运行，继续使用现有页面。")
                } else if (target != null) {
                    appendEvent("status", "正在预热目标应用", "${target.label}将启动到虚拟屏幕displayId=$displayId。")
                    if (!PhoneUiShowerRuntime.controller.launchApp(target.packageName)) {
                        throw IllegalStateException("无法在虚拟屏幕启动目标应用：${target.label}")
                    }
                } else if (!launchBootstrapScreen(context, displayId)) {
                    throw IllegalStateException("虚拟屏幕首个可见窗口初始化失败")
                }
                delay(1_200)
            } else if (!PhoneUiAgentProgressOverlay.show(context)) {
                appendEvent("status", "主屏幕进度悬浮窗未显示", "请在手机操作智能体页面授权悬浮窗；任务仍将继续执行。")
            }
            appendEvent("status", "屏幕环境已就绪", "displayId=$displayId；模型=${config.displayName}")
            if (mode == PhoneUiScreenMode.MAIN) startKeepAlive()
            updateStatus("running", "正在观察屏幕并规划第一步")

            val task = synchronized(lock) { state.optString("task") }
            val maxSteps = synchronized(lock) { state.optInt("maxSteps", 25) }
            val conversation = synchronized(lock) { PhoneUiConversationContext.fromState(state) }
            val history = conversation.history
            var actionResult = if (continuing) "用户发来了新的补充指令：$task。请根据当前新截图和已确认的历史进展继续，新的指令优先于旧目标。" else ""
            if (continuing && conversation.lastActionResult.isNotBlank()) {
                actionResult += "\n上一轮最后动作的实际返回：${conversation.lastActionResult}"
            }
            var previousScreenshot: ByteArray? = null
            var previousActionSignature = ""
            var identicalActionStreak = 0
            var executedSteps = 0
            var minimumVirtualFrameUs = 0L
            while (executedSteps < maxSteps) {
                val step = executedSteps + 1
                awaitRunnable()
                if (cancelled || pendingInstruction != null) return@runBlocking
                val revision = observationRevision.get()
                if (conversation.needsCompaction(task, config)) {
                    val summaryModel = PhoneUiSummaryModelStore.selected(context)
                    if (summaryModel == null) {
                        paused = true
                        appendEvent("status", "上下文接近容量上限", "请在模型管理中选择压缩模型后继续；尚未调用任何压缩服务。")
                        updateStatus("paused", "等待选择上下文压缩模型")
                        awaitRunnable()
                        continue
                    }
                    updateStep(step, "正在整理会话上下文，尚未执行屏幕动作")
                    try {
                        conversation.compact(summaryModel.config, task) { selected, source ->
                            PhoneUiAgentModelClient.summarizeContext(selected, source, summaryModel.wireProtocol)
                        }
                    } catch (error: Exception) {
                        if (cancelled || pendingInstruction != null) return@runBlocking
                        paused = true
                        appendEvent("error", "上下文整理未完成", "${error.message}；原始上下文已保留，点击继续后重试。")
                        updateStatus("paused", "上下文整理失败，原始记录已保留")
                        awaitRunnable()
                        continue
                    }
                    appendEvent("status", "会话上下文已整理", "摘要模型：${summaryModel.config.displayName}；完整任务记录仍保留，本轮剩余步数不变。")
                    synchronized(lock) { conversation.save(state); persistLocked() }
                    if (cancelled || pendingInstruction != null) return@runBlocking
                }
                updateStep(step, "正在截取当前屏幕")
                val sourceState = if (mode == PhoneUiScreenMode.VIRTUAL) {
                    PhoneUiShowerRuntime.controller.syncDisplay(synchronized(lock) { state.optString("targetPackage") })
                } else JSONObject()
                if (sourceState.optJSONObject("tasks")?.has("restoredTaskId") == true) delay(650)
                val screenshot = captureScreenshot(context, mode, minimumVirtualFrameUs)
                if (cancelled || pendingInstruction != null) return@runBlocking
                if (revision != observationRevision.get()) continue
                val priorScreenshot = previousScreenshot
                if (actionResult.isNotBlank() && priorScreenshot != null) {
                    actionResult += "；${screenChangeHint(priorScreenshot, screenshot)}"
                }
                previousScreenshot = screenshot
                val dimensions = imageDimensions(screenshot)
                val frameQuality = PhoneUiFrameQuality.inspect(screenshot)
                if (mode == PhoneUiScreenMode.VIRTUAL) {
                    minimumVirtualFrameUs = maxOf(
                        minimumVirtualFrameUs,
                        PhoneUiVirtualDisplayCapture.diagnostics()["copiedUs"] ?: 0L,
                    )
                }
                synchronized(lock) {
                    state.put("lastObservation", JSONObject()
                        .put("step", step).put("displayId", displayId).put("mode", mode.value)
                        .put("capturedAt", Instant.now().toString())
                        .put("width", dimensions.first).put("height", dimensions.second)
                        .put("frameQuality", JSONObject()
                            .put("usable", frameQuality.usable)
                            .put("sampled", frameQuality.sampled)
                            .put("nonBlackPermille", frameQuality.nonBlackPermille)
                            .put("averageLuma", frameQuality.averageLuma)
                            .put("lumaRange", frameQuality.lumaRange))
                        .put("source", sourceState)
                        .put("video", if (mode == PhoneUiScreenMode.VIRTUAL) JSONObject(PhoneUiVirtualDisplayCapture.diagnostics()) else JSONObject.NULL)
                        .put("sha256", MessageDigest.getInstance("SHA-256").digest(screenshot)
                            .joinToString("") { "%02x".format(it) }))
                }
                val observationFile = PhoneUiObservationJournal.save(context, screenshot, synchronized(lock) {
                    JSONObject(state.getJSONObject("lastObservation").toString())
                        .put("taskId", state.optString("id")).put("round", state.optInt("round", 1))
                        .put("model", config.modelId)
                })
                updateStep(step, "模型正在判断下一步操作")
                val decision = PhoneUiAgentModelClient.decide(
                    config,
                    conversation.taskWithMemory(task),
                    screenshot,
                    history,
                    actionResult,
                    step,
                    maxSteps,
                    screenInfo = currentScreenInfo(sourceState, displayId),
                    onProgress = { progress ->
                        if (!cancelled && !paused && pendingInstruction == null) updateStep(step, progress)
                    },
                    shouldStop = { cancelled || pendingInstruction != null },
                )
                PhoneUiObservationJournal.decision(observationFile, decision.raw)
                awaitRunnable()
                if (cancelled || pendingInstruction != null) return@runBlocking
                if (revision != observationRevision.get()) {
                    actionResult = "用户暂停后选择继续。之前截图对应的待执行动作已作废；请仅依据这次新截图重新判断，不要假定用户做过任何操作。"
                    previousScreenshot = null
                    previousActionSignature = ""
                    identicalActionStreak = 0
                    continue
                }
                if (decision.thinking.isNotBlank()) {
                    appendEvent("thinking", "第${step}步判断", decision.thinking)
                }
                val currentPrompt = PhoneUiAgentModelClient.historyUserPrompt(config.protocol, conversation.taskWithMemory(task), history.isEmpty(), actionResult)
                history += "user" to currentPrompt
                history += "assistant" to PhoneUiAgentModelClient.historyAssistant(config.protocol, decision)
                executedSteps++
                synchronized(lock) { conversation.save(state); persistLocked() }
                if (decision.action.finished) {
                    val message = buildFinalMessage(task, decision.action.message, decision.thinking)
                    if (decision.action.successful) {
                        appendEvent("result", "任务完成", message)
                        updateStatus("completed", message)
                    } else {
                        appendEvent("error", "任务未完成", message)
                        updateStatus("failed", message)
                    }
                    return@runBlocking
                }
                if (requiresTakeover(decision.action)) {
                    val message = decision.action.message.orEmpty().ifBlank { "当前步骤需要用户手动完成" }
                    paused = true
                    appendEvent("takeover", "等待用户接管", message)
                    updateStatus("paused", message)
                    awaitRunnable()
                    if (cancelled || pendingInstruction != null) return@runBlocking
                    actionResult = "用户选择继续，没有提供任何已完成操作的确认。请仅依据这次新截图重新判断，不要沿用此前关于页面的推测。"
                    previousScreenshot = null
                    previousActionSignature = ""
                    identicalActionStreak = 0
                    continue
                }
                val signature = actionSignature(decision.action)
                val normalizedAction = decision.action.name.trim().lowercase()
                val duplicateType = normalizedAction in setOf("type", "type_name") &&
                    signature == previousActionSignature
                val executionResult = if (duplicateType) {
                    "检测到连续相同文本输入，已保留输入框现有内容且未再次执行全选删除；请根据新截图定位发送按钮"
                } else {
                    executeAction(context, decision.action, dimensions.first, dimensions.second)
                }
                appendEvent("action", "第${step}步：${decision.action.name}", executionResult)
                if (normalizedAction == "launch") {
                    val launchedPackage = resolvePackage(context, decision.action.app.orEmpty())
                    synchronized(lock) { state.put("targetPackage", launchedPackage) }
                }
                identicalActionStreak = if (signature == previousActionSignature) identicalActionStreak + 1 else 1
                previousActionSignature = signature
                actionResult = modelActionResult(decision.action, executionResult, identicalActionStreak)
                conversation.lastActionResult = actionResult
                synchronized(lock) { conversation.save(state); persistLocked() }
                updateStep(step, "动作已执行，正在等待页面稳定")
                delay(actionSettleDelayMs(normalizedAction))
            }
            appendEvent("error", "达到最大步数", "任务尚未明确完成，已停止继续操作。")
            updateStatus("step_limit", "已达到最大步数，任务停止")
        } catch (error: Throwable) {
            if (!cancelled && pendingInstruction == null) {
                Log.e(TAG, "Phone UI task failed", error)
                appendEvent("error", "任务异常", error.message ?: error.javaClass.simpleName)
                updateStatus("failed", error.message ?: "任务执行失败")
            }
        } finally {
            if (taskMode == PhoneUiScreenMode.MAIN) PhoneUiAgentProgressOverlay.dismissAfter(4_000)
        }
    }

    private suspend fun captureScreenshot(
        context: Context,
        mode: PhoneUiScreenMode,
        afterPresentationUs: Long = 0L,
    ): ByteArray {
        if (mode == PhoneUiScreenMode.VIRTUAL) {
            captureUsableVirtualFrame(
                "virtual-video",
                attempts = 12,
                afterPresentationUs = afterPresentationUs,
            )?.let { return it }
            appendEvent("status", "正在恢复目标应用画面", "视频流暂未产生包含应用内容的画面，正在重新连接解码器；无内容画面不会发送给模型。")
            PhoneUiVirtualDisplayCapture.attach(context, force = true)
            captureUsableVirtualFrame("virtual-video-reconnected", attempts = 8, 0L)?.let { return it }
            throw IllegalStateException("虚拟屏幕视频流没有产生包含应用内容的有效画面；任务已停止，未向模型发送黑色占位帧；${PhoneUiVirtualDisplayCapture.diagnostics()}")
        }

        PhoneUiAgentProgressOverlay.hideForCapture()
        try {
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
        } finally {
            PhoneUiAgentProgressOverlay.restoreAfterCapture()
        }
    }

    private suspend fun captureUsableVirtualFrame(
        source: String,
        attempts: Int,
        afterPresentationUs: Long,
    ): ByteArray? {
        var frameFloorUs = afterPresentationUs
        repeat(attempts) { attempt ->
            val frame = validScreenshot(
                PhoneUiVirtualDisplayCapture.capturePng(4_000, frameFloorUs),
                "$source-${attempt + 1}",
            )
            if (frame != null) {
                val quality = PhoneUiFrameQuality.inspect(frame)
                if (quality.usable) return frame
                frameFloorUs = maxOf(
                    frameFloorUs,
                    PhoneUiVirtualDisplayCapture.diagnostics()["copiedUs"] ?: 0L,
                )
                Log.w(TAG, "Rejected content-free virtual frame: source=$source attempt=${attempt + 1} $quality")
                synchronized(lock) {
                    state.put("lastRejectedFrame", JSONObject()
                        .put("source", source).put("attempt", attempt + 1)
                        .put("capturedAt", Instant.now().toString())
                        .put("bytes", frame.size).put("sampled", quality.sampled)
                        .put("nonBlackPermille", quality.nonBlackPermille)
                        .put("averageLuma", quality.averageLuma)
                        .put("lumaRange", quality.lumaRange)
                        .put("sha256", MessageDigest.getInstance("SHA-256").digest(frame)
                            .joinToString("") { "%02x".format(it) }))
                    persistLocked()
                }
            }
            delay(if (attempt < 3) 350 else 750)
        }
        return null
    }

    private fun actionSettleDelayMs(action: String): Long = when (action) {
        "launch" -> 1_400L
        "tap", "double tap", "long press", "swipe", "back", "home" -> 900L
        "type", "type_name" -> 500L
        else -> 150L
    }

    internal fun currentScreenInfo(source: JSONObject, displayId: Int): String {
        val roots = source.optJSONObject("tasks")?.optJSONArray("roots") ?: return ""
        for (index in 0 until roots.length()) {
            val root = roots.optJSONObject(index) ?: continue
            if (root.optInt("displayId", -1) != displayId) continue
            val top = root.optString("top").trim()
            if (top.isNotEmpty()) return "当前前台应用：$top"
        }
        return ""
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
                delay(250)
                val keyboardVisible = isSoftwareKeyboardVisible()
                val keyboardDismissed = keyboardVisible && controller.key(KeyEvent.KEYCODE_BACK)
                if (keyboardDismissed) delay(300)
                buildString {
                    append("已向当前输入框粘贴${value.length}个字符")
                    if (keyboardDismissed) {
                        append("；已自动收起输入法，下一步必须根据新截图重新定位发送按钮")
                    } else {
                        append("；未检测到展开的输入法，下一步仍须根据新截图重新定位发送按钮")
                    }
                }
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

    private fun isSoftwareKeyboardVisible(): Boolean {
        val result = ShizukuController.executeShellCommand("dumpsys input_method")
        if (!result.success) {
            Log.w(TAG, "Unable to inspect input method state: code=" + result.errorCode + " detail=" + result.error)
            return false
        }
        val state = result.stdout
        if (Regex("mInputShown\\s*=\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(state)) return true
        return Regex("mImeWindowVis\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .findAll(state)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .any { it != 0 }
    }

    private fun modelActionResult(action: PhoneUiAction, executionResult: String, identicalActionStreak: Int): String {
        val startX = action.x ?: 500
        val startY = action.y ?: 500
        val endX = action.endX ?: startX
        val endY = action.endY ?: startY
        val normalizedResult = when (action.name.trim().lowercase()) {
            "tap" -> "Tap已执行，模型坐标=[$startX,$startY]。请根据当前新截图判断点击是否生效。"
            "double tap" -> "Double Tap已执行，模型坐标=[$startX,$startY]。请根据当前新截图判断页面变化。"
            "long press" -> "Long Press已执行，模型坐标=[$startX,$startY]。请根据当前新截图判断长按结果。"
            "swipe" -> {
                val deltaX = endX - startX
                val deltaY = endY - startY
                val direction = if (kotlin.math.abs(deltaY) >= kotlin.math.abs(deltaX)) {
                    if (deltaY < 0) {
                        "手指向上滑，通常显示页面更下方的内容"
                    } else {
                        "手指向下滑，通常显示页面更上方的内容"
                    }
                } else if (deltaX < 0) {
                    "手指向左滑，通常显示横向区域右侧的内容"
                } else {
                    "手指向右滑，通常显示横向区域左侧的内容"
                }
                "Swipe已执行，模型坐标start=[$startX,$startY]、end=[$endX,$endY]；方向语义：$direction。请先检查当前新截图中的可见内容和位置变化，再决定下一步。"
            }
            else -> executionResult
        }
        return if (identicalActionStreak <= 1) {
            normalizedResult
        } else {
            "$normalizedResult 这是同一动作连续第${identicalActionStreak}次执行；动作未被宿主拦截，但必须结合当前新截图判断是否应改换策略。"
        }
    }

    private fun actionSignature(action: PhoneUiAction): String = listOf(
        action.name.trim().lowercase(),
        action.x,
        action.y,
        action.endX,
        action.endY,
        action.text,
        action.app,
    ).joinToString("|")

    private fun screenChangeHint(beforePng: ByteArray, afterPng: ByteArray): String {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        val before = BitmapFactory.decodeByteArray(beforePng, 0, beforePng.size, options)
            ?: return "当前新截图已到达，请直接核对可见内容"
        val after = BitmapFactory.decodeByteArray(afterPng, 0, afterPng.size, options)
            ?: run {
                before.recycle()
                return "当前新截图已到达，请直接核对可见内容"
            }
        return try {
            if (before.width != after.width || before.height != after.height) {
                "当前新截图尺寸已经变化，页面状态发生了明显改变"
            } else {
                var changed = 0
                var sampled = 0
                val strideX = (before.width / 18).coerceAtLeast(1)
                val strideY = (before.height / 32).coerceAtLeast(1)
                var y = strideY / 2
                while (y < before.height) {
                    var x = strideX / 2
                    while (x < before.width) {
                        val first = before.getPixel(x, y)
                        val second = after.getPixel(x, y)
                        val difference = kotlin.math.abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)) +
                            kotlin.math.abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)) +
                            kotlin.math.abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second))
                        if (difference >= 72) changed++
                        sampled++
                        x += strideX
                    }
                    y += strideY
                }
                val percent = if (sampled == 0) 0 else changed * 100 / sampled
                when {
                    percent >= 18 -> "当前新截图相对动作前明显变化，采样变化约$percent%；必须检查新出现的项目，不能无依据重复原动作"
                    percent >= 4 -> "当前新截图相对动作前有局部变化，采样变化约$percent%；请核对目标和滚动位置"
                    else -> "图像粗采样变化约$percent%；小字、光标及局部控件变化可能未被采样覆盖，不能据此断定动作失败，请直接核对新截图"
                }
            }
        } finally {
            before.recycle()
            after.recycle()
        }
    }

    private fun buildFinalMessage(task: String, rawMessage: String?, thinking: String): String {
        return rawMessage.orEmpty().trim().ifBlank {
            thinking.trim().ifBlank { "模型已结束任务，但未提供执行结果说明。" }
        }
    }

    private fun resolvePackage(context: Context, raw: String): String {
        val rawTarget = raw.trim()
        if (rawTarget.contains('.')) {
            val exists = runCatching { context.packageManager.getApplicationInfo(rawTarget, 0) }.isSuccess
            if (exists) return rawTarget
        }
        val target = cleanAppName(rawTarget)
        require(target.isNotEmpty()) { "Launch动作缺少应用名称" }
        val normalized = normalizeAppName(target)
        val matches = launchableApps(context).filter { app ->
            app.names.any { name ->
                val candidate = normalizeAppName(name)
                candidate == normalized || candidate.contains(normalized) || normalized.contains(candidate)
            }
        }
        return matches.maxByOrNull { app -> app.names.maxOf { normalizeAppName(it).length } }?.packageName
            ?: throw IllegalArgumentException("未找到可启动应用：$target，请让模型返回准确包名")
    }

    internal fun taskExistsOnDisplay(sourceState: JSONObject, packageName: String, displayId: Int): Boolean {
        if (packageName.isBlank()) return false
        val roots = sourceState.optJSONObject("tasks")?.optJSONArray("roots") ?: return false
        for (index in 0 until roots.length()) {
            val root = roots.optJSONObject(index) ?: continue
            if (root.optInt("displayId", -1) != displayId) continue
            if (listOf(root.optString("top"), root.optString("base")).any {
                    it == packageName || it.startsWith("$packageName/")
                }) return true
        }
        return false
    }

    private data class LaunchableApp(val packageName: String, val label: String, val names: Set<String>)

    private fun resolveTaskTargetApp(context: Context, task: String): LaunchableApp? {
        val compactTask = normalizeAppName(task)
        return launchableApps(context)
            .mapNotNull { app ->
                val score = app.names.maxOfOrNull { rawName ->
                    val name = normalizeAppName(rawName)
                    if (name.length < 2) return@maxOfOrNull 0
                    val explicit = listOf("打开$name", "启动$name", "进入$name", "使用$name", "用$name")
                        .any(compactTask::contains)
                    val namedAsApp = compactTask.contains(name + "app") || compactTask.contains(name + "应用")
                    if (explicit || namedAsApp || compactTask.contains(normalizeAppName(app.packageName))) name.length else 0
                } ?: 0
                app.takeIf { score > 0 }?.let { it to score }
            }
            .maxWithOrNull(compareBy<Pair<LaunchableApp, Int>> { it.second }.thenBy { it.first.label.length })
            ?.first
    }

    private fun launchableApps(context: Context): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, 0)
        return activities.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(context.packageManager)?.toString()?.trim().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            val aliases = linkedSetOf(label, cleanAppName(label))
            cleanAppName(label).removePrefix("手机").takeIf { it.length >= 2 }?.let(aliases::add)
            KNOWN_APP_ALIASES[packageName.lowercase()]?.let(aliases::addAll)
            LaunchableApp(packageName, label, aliases)
        }.distinctBy(LaunchableApp::packageName)
    }

    private fun cleanAppName(value: String): String = value.trim()
        .replace(Regex("(?i)(?:\\s*(?:app|应用|安卓版))+$"), "")
        .trim()

    private fun normalizeAppName(value: String): String = value.lowercase()
        .replace(Regex("[\\s\\p{Punct}，。！？、（）【】《》“”‘’]+"), "")

    private fun launchBootstrapScreen(context: Context, displayId: Int): Boolean = runCatching {
        val intent = Intent(context, PhoneUiAgentBootstrapActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
        context.startActivity(intent, options.toBundle())
        true
    }.getOrElse { error ->
        Log.e(TAG, "Failed to launch virtual display bootstrap screen", error)
        false
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
        if (state.optString("mode") == PhoneUiScreenMode.MAIN.value) PhoneUiAgentProgressOverlay.update("第${step}步", text)
        persistLocked()
    }

    private fun updateStatus(status: String, text: String) = synchronized(lock) {
        state.put("status", status).put("statusText", text).put("updatedAt", Instant.now().toString())
        when (status) {
            "completed" -> state.put("result", text).remove("error")
            "failed", "cancelled", "step_limit", "interrupted" -> state.put("error", text).remove("result")
            "starting", "running" -> state.remove("result").also { state.remove("error") }
        }
        if (state.optString("mode") == PhoneUiScreenMode.MAIN.value) PhoneUiAgentProgressOverlay.update("手机操作智能体", text)
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
        if (state.optString("mode") == PhoneUiScreenMode.MAIN.value) PhoneUiAgentProgressOverlay.update(title, detail)
    }

    private fun snapshotLocked(): JSONObject = JSONObject(state.toString())

    private fun requireCurrentTaskLocked(taskId: String?) {
        val expected = taskId.orEmpty().trim()
        if (expected.isEmpty()) return
        require(state.optString("id") == expected) {
            "手机操作任务已切换，拒绝控制其他任务：expected=$expected actual=${state.optString("id")}"
        }
    }

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
        for (index in history.length() - 1 downTo 0) {
            if (history.optJSONObject(index)?.optString("id") == state.optString("id")) history.remove(index)
        }
        history.put(JSONObject(state.toString()))
        while (history.length() > 50) history.remove(0)
        val target = historyFile(context)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(history.toString())
        check(temp.renameTo(target)) { "历史会话写入失败，原始记录已保留" }
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
