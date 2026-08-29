package com.codex.mobile

import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CollaborationActivity : AppCompatActivity() {
    private data class RunRow(
        val id: String,
        val title: String,
        val status: String,
        val leader: String,
        val archived: Boolean,
        val summary: String,
        val payload: JSONObject,
    )

    private data class CollapsibleBlock(
        val toggle: Button,
        val content: TextView,
        var label: String = "",
        var expanded: Boolean = false,
    )

    private data class AgentDetailViews(
        val heading: TextView,
        val assignment: CollapsibleBlock,
        val action: TextView,
        val response: CollapsibleBlock,
        val error: TextView,
    )

    private data class TaskDetailViews(
        val heading: TextView,
        val objective: CollapsibleBlock,
        val progress: TextView,
        val response: CollapsibleBlock,
        val error: TextView,
    )

    private data class DetailViews(
        val turnLabel: TextView,
        val previousTurn: Button,
        val nextTurn: Button,
        val status: TextView,
        val error: TextView,
        val userHeading: TextView,
        val userMessage: TextView,
        val summaryHeading: TextView,
        val summary: TextView,
        val agents: Map<String, AgentDetailViews>,
        val tasksHeading: TextView,
        val taskContainer: LinearLayout,
        val tasks: MutableMap<String, TaskDetailViews>,
        val input: EditText,
        val send: Button,
        val abort: Button,
        val more: Button,
        val rename: Button,
        val export: Button,
        val share: Button,
        val delete: Button,
    )

    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private val allRows = mutableListOf<RunRow>()
    private val rows = mutableListOf<RunRow>()
    private val adapter = RunAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var loading = false
    private var active = false
    private var lastRenderFingerprint = ""
    private var filter = "current"
    private var detailDialog: AlertDialog? = null
    private var detailRunId = ""
    private var detailViews: DetailViews? = null
    private var detailScrollView: ScrollView? = null
    private var detailInput: EditText? = null
    private var detailMoreExpanded = false
    private var detailFingerprint = ""
    private var detailSelectedTurnNumber = 0
    private var detailRenderedTurnNumber = 0
    private var detailLatestTurnNumber = 0
    private var detailOldestTurnNumber = 1
    private var detailLoading = false
    private var detailLastFinalSummary = ""
    private var pendingExportFile: File? = null
    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val source = pendingExportFile
        pendingExportFile = null
        if (uri == null || source == null) return@registerForActivityResult
        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("无法打开导出位置")
            }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, "协作任务已导出", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(this, "导出失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
    private val poll = object : Runnable { override fun run() = refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collaboration)
        statusView = findViewById(R.id.tvCollaborationStatus)
        listView = findViewById(R.id.listCollaborationRuns)
        listView.adapter = adapter
        findViewById<Button>(R.id.btnCollaborationRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnCollaborationCurrent).setOnClickListener { filter = "current"; applyFilter() }
        findViewById<Button>(R.id.btnCollaborationHistory).setOnClickListener { filter = "history"; applyFilter() }
        findViewById<Button>(R.id.btnCollaborationArchived).setOnClickListener { filter = "archived"; applyFilter() }
        listView.setOnItemClickListener { _, _, position, _ -> showRunDetails(rows[position]) }
    }

    override fun onResume() {
        super.onResume()
        active = true
        handler.removeCallbacks(poll)
        refresh()
    }

    override fun onPause() {
        active = false
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun isActiveStatus(status: String): Boolean =
        status == "planning" || status == "running" || status == "reviewing"

    private fun refresh() {
        if (loading) return
        loading = true
        Thread {
            val result = runCatching { CollaborationClient.listRuns(this) }
            runOnUiThread {
                loading = false
                result.onSuccess { payload ->
                    val nextRows = mutableListOf<RunRow>()
                    payload.optJSONArray("runs")?.let { array ->
                        for (index in 0 until array.length()) {
                            array.optJSONObject(index)?.let { nextRows += parseRun(it) }
                        }
                    }
                    val fingerprint = nextRows.joinToString("|") { "${it.id}:${it.status}:${it.payload}" }
                    if (fingerprint != lastRenderFingerprint) {
                        allRows.clear()
                        allRows += nextRows
                        lastRenderFingerprint = fingerprint
                        applyFilter()
                        refreshOpenDetail()
                    }
                    val runningCount = allRows.count { isActiveStatus(it.status) }
                    val waitingCount = allRows.count { it.status == "waiting_user" }
                    val nextStatus = when {
                        runningCount > 0 -> "当前任务：${allRows.first { isActiveStatus(it.status) }.summary}"
                        waitingCount > 0 -> "当前有${waitingCount}项协作任务等待您的回复"
                        allRows.isNotEmpty() -> "当前没有运行中的协作任务"
                        else -> "当前没有协作任务"
                    }
                    if (statusView.text.toString() != nextStatus) statusView.text = nextStatus
                    handler.removeCallbacks(poll)
                    if (active && (runningCount > 0 || detailDialog?.isShowing == true)) {
                        handler.postDelayed(poll, 2_000L)
                    }
                }.onFailure { error ->
                    statusView.text = "协作看板刷新失败：${error.message ?: "unknown"}"
                    handler.removeCallbacks(poll)
                    if (active && detailDialog?.isShowing == true) handler.postDelayed(poll, 3_000L)
                }
            }
        }.start()
    }

    private fun applyFilter() {
        val currentRows = allRows.filter { !it.archived && (isActiveStatus(it.status) || it.status == "waiting_user") }
        val latestId = allRows.firstOrNull { !it.archived }?.id.orEmpty()
        rows.clear()
        rows += allRows.filter { row ->
            when (filter) {
                "archived" -> row.archived
                "history" -> !row.archived && !isActiveStatus(row.status) && row.status != "waiting_user"
                else -> if (currentRows.isNotEmpty()) currentRows.any { it.id == row.id } else row.id == latestId
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun parseRun(run: JSONObject): RunRow {
        val status = run.optString("status")
        val finalSummary = run.optString("finalSummary").trim()
        val error = run.optString("errorText").trim()
        val leader = run.optString("leader")
        val agents = run.optJSONObject("agents")
        val leaderAction = agents?.optJSONObject(leader)?.optString("actionText").orEmpty().trim()
        val activeAction = listOf("codex", "claude", "minis").asSequence()
            .mapNotNull { agents?.optJSONObject(it) }
            .firstOrNull { it.optString("status") == "running" }
            ?.optString("actionText").orEmpty().trim()
        val summary = when {
            error.isNotEmpty() -> "任务异常：$error"
            finalSummary.isNotEmpty() -> "总调度回复：$finalSummary"
            activeAction.isNotEmpty() -> activeAction
            leaderAction.isNotEmpty() -> leaderAction
            else -> statusLabel(status)
        }
        return RunRow(
            id = run.optString("id"),
            title = run.optString("title").ifBlank { "三智能体协作" },
            status = status,
            leader = leader,
            archived = run.optBoolean("archived"),
            summary = summary,
            payload = run,
        )
    }

    private fun statusLabel(status: String): String = when (status) {
        "idle" -> "未调用"
        "queued" -> "已排队"
        "pending" -> "等待中"
        "planning" -> "总调度分析中"
        "running" -> "成员执行中"
        "retrying" -> "恢复重试中"
        "reviewing" -> "总调度审核中"
        "waiting_user" -> "等待用户"
        "completed" -> "已完成"
        "failed" -> "失败"
        "canceled" -> "已取消"
        "stalled" -> "已停滞"
        "aborted" -> "已终止"
        else -> status.ifBlank { "未知" }
    }

    private fun agentLabel(agentId: String): String = when (agentId) {
        "claude" -> "Claude Code"
        "minis" -> "Minis"
        else -> "Codex"
    }

    private fun createText(container: LinearLayout, heading: Boolean = false): TextView {
        val view = TextView(this).apply {
            textSize = if (heading) 17f else 15f
            setPadding(0, if (heading) dp(16) else dp(5), 0, dp(5))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isScreenReaderFocusable = true
                if (heading) isAccessibilityHeading = true
            }
        }
        container.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return view
    }

    private fun setTextBlock(view: TextView, text: String) {
        val normalized = text.trim()
        view.visibility = if (normalized.isEmpty()) View.GONE else View.VISIBLE
        if (view.text.toString() != normalized) view.text = normalized
    }

    private fun createCollapsibleBlock(container: LinearLayout): CollapsibleBlock {
        val toggle = Button(this).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        container.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val content = createText(container).apply { visibility = View.GONE }
        val block = CollapsibleBlock(toggle = toggle, content = content)
        toggle.setOnClickListener {
            block.expanded = !block.expanded
            updateCollapsibleBlock(block)
            if (block.expanded) {
                block.content.post {
                    block.content.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                }
            }
        }
        return block
    }

    private fun setCollapsibleBlock(block: CollapsibleBlock, label: String, text: String) {
        val normalized = text.trim()
        block.label = label
        block.toggle.visibility = if (normalized.isEmpty()) View.GONE else View.VISIBLE
        if (block.content.text.toString() != normalized) block.content.text = normalized
        if (normalized.isEmpty()) block.expanded = false
        updateCollapsibleBlock(block)
    }

    private fun updateCollapsibleBlock(block: CollapsibleBlock) {
        val hasContent = block.content.text.isNotBlank()
        block.toggle.text = if (block.expanded) "收起${block.label}" else "展开${block.label}"
        block.content.visibility = if (hasContent && block.expanded) View.VISIBLE else View.GONE
    }

    private fun collapseAgentDetails() {
        detailViews?.agents?.values?.forEach { agent ->
            agent.assignment.expanded = false
            agent.response.expanded = false
            updateCollapsibleBlock(agent.assignment)
            updateCollapsibleBlock(agent.response)
        }
        detailViews?.tasks?.values?.forEach { task ->
            task.objective.expanded = false
            task.response.expanded = false
            updateCollapsibleBlock(task.objective)
            updateCollapsibleBlock(task.response)
        }
    }

    private fun createTaskDetailViews(container: LinearLayout): TaskDetailViews = TaskDetailViews(
        heading = createText(container, heading = true),
        objective = createCollapsibleBlock(container),
        progress = createText(container),
        response = createCollapsibleBlock(container),
        error = createText(container),
    )

    private fun clearTaskDetails() {
        val views = detailViews ?: return
        views.taskContainer.removeAllViews()
        views.tasks.clear()
        setTextBlock(views.tasksHeading, "")
    }

    private fun renderTaskDetails(run: JSONObject) {
        val views = detailViews ?: return
        val taskRows = run.optJSONArray("selectedTasks") ?: run.optJSONArray("tasks") ?: JSONArray()
        setTextBlock(views.tasksHeading, if (taskRows.length() > 0) "成员任务记录" else "")
        for (index in 0 until taskRows.length()) {
            val task = taskRows.optJSONObject(index) ?: continue
            val taskId = task.optString("taskId").ifBlank { "task-$index" }
            val taskViews = views.tasks.getOrPut(taskId) { createTaskDetailViews(views.taskContainer) }
            val agent = agentLabel(task.optString("agentId"))
            val status = statusLabel(task.optString("status"))
            setTextBlock(taskViews.heading, "第${index + 1}项成员任务，$agent，$status")
            val objective = buildString {
                append(task.optString("objective").trim())
                val expected = task.optString("expectedOutput").trim()
                if (expected.isNotEmpty()) append("\n预期结果：").append(expected)
            }
            setCollapsibleBlock(taskViews.objective, "第${index + 1}项任务要求", objective)
            val attempt = task.optInt("attempt", 0)
            setTextBlock(
                taskViews.progress,
                "任务状态：$status${if (attempt > 0) "，第${attempt}次执行" else ""}",
            )
            setCollapsibleBlock(taskViews.response, "第${index + 1}项任务结果", task.optString("responseText"))
            setTextBlock(
                taskViews.error,
                task.optString("errorText").trim().takeIf { it.isNotEmpty() }?.let { "失败原因：$it" }.orEmpty(),
            )
        }
    }

    private fun showRunDetails(row: RunRow) {
        detailDialog?.dismiss()
        detailRunId = row.id
        detailFingerprint = ""
        detailSelectedTurnNumber = row.payload.optInt("turnNumber", 1)
        detailRenderedTurnNumber = 0
        detailLatestTurnNumber = detailSelectedTurnNumber
        detailOldestTurnNumber = row.payload.optInt("oldestTurnNumber", 1)
        detailLoading = false
        detailLastFinalSummary = ""
        detailMoreExpanded = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(10))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val turnLabel = createText(root)
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val previousTurn = Button(this).apply { text = "上一轮" }
        val nextTurn = Button(this).apply { text = "下一轮" }
        navigation.addView(previousTurn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        navigation.addView(nextTurn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(navigation, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val status = createText(content)
        val error = createText(content)
        val userHeading = createText(content, heading = true)
        val userMessage = createText(content)
        val agentViews = linkedMapOf<String, AgentDetailViews>()
        val orderedIds = listOf(row.leader) + listOf("codex", "claude", "minis").filter { it != row.leader }
        orderedIds.forEach { id ->
            agentViews[id] = AgentDetailViews(
                heading = createText(content, heading = true),
                assignment = createCollapsibleBlock(content),
                action = createText(content),
                response = createCollapsibleBlock(content),
                error = createText(content),
            )
        }
        val tasksHeading = createText(content, heading = true)
        val taskContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(taskContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val taskViews = linkedMapOf<String, TaskDetailViews>()
        val summaryHeading = createText(content, heading = true)
        val summary = createText(content)
        val scroll = ScrollView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val input = EditText(this).apply {
            minLines = 2
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        footer.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val send = Button(this).apply { text = "发送" }
        val abort = Button(this).apply { text = "终止协作" }
        val more = Button(this).apply { text = "更多" }
        val rename = Button(this).apply { text = "重命名" }
        val export = Button(this).apply { text = "导出" }
        val share = Button(this).apply { text = "分享" }
        val delete = Button(this).apply { text = "删除" }
        listOf(send, abort, more, rename, export, share, delete).forEach { button ->
            footer.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        detailViews = DetailViews(turnLabel, previousTurn, nextTurn, status, error, userHeading, userMessage, summaryHeading, summary, agentViews, tasksHeading, taskContainer, taskViews, input, send, abort, more, rename, export, share, delete)
        detailInput = input
        detailScrollView = scroll
        bindDetailActions()
        renderRunDetails(row)
        detailDialog = AlertDialog.Builder(this)
            .setTitle(row.title)
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    detailRunId = ""
                    detailViews = null
                    detailScrollView = null
                    detailInput = null
                    detailMoreExpanded = false
                    detailFingerprint = ""
                    detailSelectedTurnNumber = 0
                    detailRenderedTurnNumber = 0
                    detailLatestTurnNumber = 0
                    detailOldestTurnNumber = 1
                    detailLoading = false
                    detailLastFinalSummary = ""
                    detailDialog = null
                }
                dialog.show()
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                focusContinuationControls()
            }
        loadDetailTurn(detailSelectedTurnNumber)
        handler.removeCallbacks(poll)
        if (active) handler.postDelayed(poll, 700L)
    }

    private fun refreshOpenDetail() {
        if (detailDialog?.isShowing != true || detailRunId.isBlank()) return
        val row = allRows.firstOrNull { it.id == detailRunId }
        if (row == null) {
            detailDialog?.dismiss()
            return
        }
        val nextLatest = row.payload.optInt("turnNumber", detailLatestTurnNumber.coerceAtLeast(1))
        if (detailSelectedTurnNumber == detailLatestTurnNumber || detailSelectedTurnNumber == 0) {
            detailSelectedTurnNumber = nextLatest
        }
        detailLatestTurnNumber = nextLatest
        detailOldestTurnNumber = row.payload.optInt("oldestTurnNumber", detailOldestTurnNumber)
        loadDetailTurn(detailSelectedTurnNumber)
    }

    private fun loadDetailTurn(turnNumber: Int) {
        if (detailLoading || detailRunId.isBlank() || detailDialog?.isShowing != true) return
        val requestedTurn = turnNumber.coerceIn(detailOldestTurnNumber, detailLatestTurnNumber.coerceAtLeast(detailOldestTurnNumber))
        detailSelectedTurnNumber = requestedTurn
        detailLoading = true
        detailViews?.previousTurn?.isEnabled = false
        detailViews?.nextTurn?.isEnabled = false
        Thread {
            val result = runCatching { CollaborationClient.getRun(this, detailRunId, requestedTurn) }
            runOnUiThread {
                detailLoading = false
                if (detailDialog?.isShowing != true || requestedTurn != detailSelectedTurnNumber) return@runOnUiThread
                result.onSuccess { payload ->
                    val run = payload.optJSONObject("run") ?: return@onSuccess
                    renderRunDetails(parseRun(run))
                }.onFailure { error ->
                    Toast.makeText(this, "协作轮次加载失败：${error.message}", Toast.LENGTH_LONG).show()
                    updateTurnNavigation()
                }
            }
        }.start()
    }

    private fun renderRunDetails(row: RunRow) {
        val views = detailViews ?: return
        val fingerprint = row.payload.toString()
        if (fingerprint == detailFingerprint) return
        detailFingerprint = fingerprint
        detailDialog?.setTitle(row.title)
        val run = row.payload
        detailLatestTurnNumber = run.optInt("turnNumber", detailLatestTurnNumber.coerceAtLeast(1))
        detailOldestTurnNumber = run.optInt("oldestTurnNumber", detailOldestTurnNumber)
        val turn = run.optJSONObject("selectedTurn") ?: run
        val turnNumber = turn.optInt("turnNumber", detailLatestTurnNumber)
        if (detailRenderedTurnNumber != 0 && detailRenderedTurnNumber != turnNumber) {
            collapseAgentDetails()
            clearTaskDetails()
            detailLastFinalSummary = ""
        }
        detailRenderedTurnNumber = turnNumber
        detailSelectedTurnNumber = turnNumber
        setTextBlock(views.turnLabel, "第${turnNumber}轮，共${run.optInt("turnCount", detailLatestTurnNumber)}轮")
        setTextBlock(views.status, "${statusLabel(turn.optString("status", row.status))}，总调度${agentLabel(row.leader)}，第${turnNumber}轮对话")
        setTextBlock(views.error, turn.optString("errorText").trim().takeIf { it.isNotEmpty() }?.let { "任务异常：$it" }.orEmpty())
        val turnUserMessage = turn.optString("userMessage").ifBlank { if (turnNumber == 1) run.optString("prompt") else "" }
        setTextBlock(views.userHeading, if (turnUserMessage.isBlank()) "" else "用户本轮消息")
        setTextBlock(views.userMessage, turnUserMessage)
        val finalSummary = turn.optString("finalSummary").trim()
        val agents = turn.optJSONObject("agents")
        views.agents.forEach { (id, agentViews) ->
            val agent = agents?.optJSONObject(id) ?: JSONObject()
            val role = if (agent.optString("role") == "leader") "总调度" else "协作成员"
            setTextBlock(agentViews.heading, "${agentLabel(id)}，$role，${statusLabel(agent.optString("status"))}")
            setCollapsibleBlock(agentViews.assignment, "${agentLabel(id)}本轮任务", agent.optString("assignmentText"))
            setTextBlock(agentViews.action, agent.optString("actionText").trim().takeIf { it.isNotEmpty() }?.let { "当前进展：$it" }.orEmpty())
            val response = agent.optString("responseText").trim()
                .takeIf { it.isNotEmpty() && !(role == "总调度" && it == finalSummary) }
                .orEmpty()
            setCollapsibleBlock(
                agentViews.response,
                "${agentLabel(id)}${if (role == "总调度") "阶段回复" else "分工结果"}",
                response,
            )
            setTextBlock(agentViews.error, agent.optString("errorText").trim().takeIf { it.isNotEmpty() }?.let { "失败原因：$it" }.orEmpty())
        }
        renderTaskDetails(run)

        val previousFinalSummary = detailLastFinalSummary
        detailLastFinalSummary = finalSummary
        setTextBlock(views.summaryHeading, if (finalSummary.isEmpty()) "" else "总调度最终回复")
        setTextBlock(views.summary, finalSummary)
        if (detailDialog?.isShowing == true && turnNumber == detailLatestTurnNumber && finalSummary.isNotEmpty() && finalSummary != previousFinalSummary) {
            focusFinalSummary()
        }

        views.input.hint = if (isActiveStatus(row.status)) "补充指令" else "继续协作"
        views.abort.visibility = if (isActiveStatus(row.status)) View.VISIBLE else View.GONE
        updateTurnNavigation()
        updateMoreActions(row)
    }

    private fun updateTurnNavigation() {
        val views = detailViews ?: return
        views.previousTurn.isEnabled = !detailLoading && detailSelectedTurnNumber > detailOldestTurnNumber
        views.nextTurn.isEnabled = !detailLoading && detailSelectedTurnNumber < detailLatestTurnNumber
    }

    private fun updateMoreActions(row: RunRow) {
        val views = detailViews ?: return
        views.more.text = if (detailMoreExpanded) "收起更多操作" else "更多"
        val managementVisibility = if (detailMoreExpanded) View.VISIBLE else View.GONE
        views.rename.visibility = if (!isActiveStatus(row.status) && detailMoreExpanded) View.VISIBLE else View.GONE
        views.export.visibility = managementVisibility
        views.share.visibility = managementVisibility
        views.delete.visibility = if (!isActiveStatus(row.status) && detailMoreExpanded) View.VISIBLE else View.GONE
    }

    private fun focusFinalSummary() {
        val summary = detailViews?.summary ?: return
        if (summary.visibility != View.VISIBLE || summary.text.isBlank()) return
        summary.post {
            summary.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    private fun focusContinuationControls() {
        val input = detailViews?.input ?: return
        input.post {
            input.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    private fun currentDetailRow(): RunRow? = allRows.firstOrNull { it.id == detailRunId }

    private fun bindDetailActions() {
        val views = detailViews ?: return
        views.previousTurn.setOnClickListener {
            if (detailSelectedTurnNumber > detailOldestTurnNumber) loadDetailTurn(detailSelectedTurnNumber - 1)
        }
        views.nextTurn.setOnClickListener {
            if (detailSelectedTurnNumber < detailLatestTurnNumber) loadDetailTurn(detailSelectedTurnNumber + 1)
        }
        views.send.setOnClickListener {
            val row = currentDetailRow() ?: return@setOnClickListener
            val prompt = views.input.text.toString().trim()
            if (prompt.isBlank()) {
                Toast.makeText(this, "请输入消息", Toast.LENGTH_SHORT).show()
            } else {
                views.input.isEnabled = false
                perform("消息已提交", { CollaborationClient.continueRun(this, row.id, prompt) }) { payload ->
                    views.input.text.clear()
                    payload.optJSONObject("run")?.let { run ->
                        detailLatestTurnNumber = run.optInt("turnNumber", detailLatestTurnNumber)
                        detailSelectedTurnNumber = detailLatestTurnNumber
                        detailFingerprint = ""
                    }
                    applyReturnedRun(payload)
                    handler.removeCallbacks(poll)
                    if (active) handler.postDelayed(poll, 400L)
                }
            }
        }
        views.abort.setOnClickListener { currentDetailRow()?.let { abort(it.id) } }
        views.more.setOnClickListener {
            val row = currentDetailRow() ?: return@setOnClickListener
            detailMoreExpanded = !detailMoreExpanded
            updateMoreActions(row)
            if (detailMoreExpanded) {
                views.rename.takeIf { it.visibility == View.VISIBLE }
                    ?.post { it.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
                    ?: views.export.post {
                        views.export.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                    }
            }
        }
        views.rename.setOnClickListener { currentDetailRow()?.let(::showRename) }
        views.export.setOnClickListener { currentDetailRow()?.let(::exportRun) }
        views.share.setOnClickListener { currentDetailRow()?.let(::shareRun) }
        views.delete.setOnClickListener {
            val row = currentDetailRow() ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("删除协作任务")
                .setMessage("确定删除“${row.title}”？")
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton("删除") { _, _ ->
                    perform("协作任务已删除", { CollaborationClient.delete(this, row.id) }) {
                        detailDialog?.dismiss()
                    }
                }
                .show()
        }
    }

    private fun applyReturnedRun(payload: JSONObject) {
        val run = payload.optJSONObject("run") ?: return
        val row = parseRun(run)
        val index = allRows.indexOfFirst { it.id == row.id }
        if (index >= 0) allRows[index] = row else allRows.add(0, row)
        lastRenderFingerprint = allRows.joinToString("|") { "${it.id}:${it.status}:${it.payload}" }
        applyFilter()
        renderRunDetails(row)
    }

    private fun showRename(row: RunRow) {
        val input = EditText(this).apply { setText(row.title); selectAll() }
        AlertDialog.Builder(this)
            .setTitle("重命名协作任务")
            .setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton("保存") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotBlank()) perform("任务名称已更新", { CollaborationClient.rename(this, row.id, title) }) {
                    applyReturnedRun(it)
                }
            }
            .show()
    }

    private fun exportRun(row: RunRow) {
        Thread {
            val result = runCatching {
                val payload = CollaborationClient.exportRun(this, row.id).optJSONObject("run") ?: row.payload
                buildRunExport(row.copy(payload = payload))
            }
            runOnUiThread {
                result.onSuccess { file ->
                    pendingExportFile = file
                    exportDocumentLauncher.launch(file.name)
                }.onFailure { error ->
                    Toast.makeText(this, "导出准备失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun shareRun(row: RunRow) {
        Thread {
            val result = runCatching {
                val payload = CollaborationClient.exportRun(this, row.id).optJSONObject("run") ?: row.payload
                buildRunExport(row.copy(payload = payload))
            }
            runOnUiThread {
                result.onSuccess { file ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("三智能体协作任务", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { startActivity(Intent.createChooser(intent, "分享协作任务")) }
                        .onFailure { error ->
                            Toast.makeText(this, "分享失败：${error.message}", Toast.LENGTH_LONG).show()
                        }
                }.onFailure { error ->
                    Toast.makeText(this, "分享准备失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun buildRunExport(row: RunRow): File {
        val exportDirectory = File(cacheDir, "share").apply {
            if (!exists() && !mkdirs()) error("无法创建分享缓存目录")
        }
        exportDirectory.listFiles()
            ?.filter { it.name.startsWith("三智能体协作_") && it.lastModified() < System.currentTimeMillis() - 86_400_000L }
            ?.forEach { it.delete() }
        val safeTitle = row.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(48).ifBlank { "协作任务" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = File(exportDirectory, "三智能体协作_${safeTitle}_${stamp}.zip")
        val readable = buildReadableExport(row)
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            addZipText(zip, "协作任务详情.txt", readable)
            addZipText(zip, "协作任务原始记录.json", row.payload.toString(2))
        }
        return target
    }

    private fun addZipText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildReadableExport(row: RunRow): String {
        val run = row.payload
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        fun time(value: Long): String = if (value > 0L) formatter.format(Date(value)) else "无"
        fun appendField(builder: StringBuilder, label: String, value: String) {
            builder.append(label).append("：").append(value.ifBlank { "无" }).append('\n')
        }
        fun appendAgents(builder: StringBuilder, agents: JSONObject) {
            listOf(row.leader).plus(listOf("codex", "claude", "minis").filter { it != row.leader }).forEach { id ->
                val agent = agents.optJSONObject(id) ?: JSONObject()
                builder.append('\n').append("【").append(agentLabel(id)).append("】").append('\n')
                appendField(builder, "角色", if (agent.optString("role") == "leader") "总调度" else "协作成员")
                appendField(builder, "状态", statusLabel(agent.optString("status")))
                appendField(builder, "本轮任务", agent.optString("assignmentText"))
                appendField(builder, "当前进展", agent.optString("actionText"))
                appendField(builder, "协作结果", agent.optString("responseText"))
                appendField(builder, "失败原因", agent.optString("errorText"))
            }
        }
        val output = StringBuilder()
        appendField(output, "任务标题", row.title)
        appendField(output, "任务编号", row.id)
        appendField(output, "任务状态", statusLabel(row.status))
        appendField(output, "总调度", agentLabel(row.leader))
        appendField(output, "当前轮次", run.optInt("turnNumber", 1).toString())
        appendField(output, "创建时间", time(run.optLong("createdAtMs")))
        appendField(output, "更新时间", time(run.optLong("updatedAtMs")))
        appendField(output, "完成时间", time(run.optLong("completedAtMs")))
        appendField(output, "用户原始任务", run.optString("prompt"))
        appendField(output, "任务错误", run.optString("errorText"))
        val turns = run.optJSONArray("turns") ?: JSONArray()
        if (turns.length() > 0) {
            output.append('\n').append("各轮协作详情").append('\n')
            for (index in 0 until turns.length()) {
                val turn = turns.optJSONObject(index) ?: continue
                output.append('\n').append("第").append(turn.optInt("turnNumber", index + 1)).append("轮").append('\n')
                appendField(output, "用户消息", turn.optString("userMessage"))
                appendField(output, "轮次状态", statusLabel(turn.optString("status")))
                appendAgents(output, turn.optJSONObject("agents") ?: JSONObject())
                appendField(output, "总调度最终回复", turn.optString("finalSummary"))
                appendField(output, "任务错误", turn.optString("errorText"))
            }
        } else {
            output.append('\n').append("三智能体详情").append('\n')
            appendAgents(output, run.optJSONObject("agents") ?: JSONObject())
        }
        output.append('\n').append("协作事件时间线").append('\n')
        val events = run.optJSONArray("events") ?: JSONArray()
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val agent = event.optString("agentId").takeIf { it.isNotBlank() }?.let(::agentLabel) ?: "系统"
            output.append(index + 1).append(". ")
                .append(time(event.optLong("atMs"))).append("，")
                .append(agent).append("，")
                .append(eventTypeLabel(event.optString("type"))).append('\n')
                .append(event.optString("text").ifBlank { "无" }).append('\n')
        }
        output.append('\n').append("总调度最终回复").append('\n')
            .append(run.optString("finalSummary").ifBlank { "无" }).append('\n')
        return output.toString()
    }

    private fun eventTypeLabel(type: String): String = when (type) {
        "user" -> "用户消息"
        "decision" -> "调度决策"
        "assignment" -> "任务委派"
        "progress" -> "执行进展"
        "result" -> "成员结果"
        "final" -> "最终回复"
        "error" -> "错误"
        "control" -> "控制操作"
        else -> type.ifBlank { "事件" }
    }

    private fun perform(
        successMessage: String,
        request: () -> JSONObject,
        onSuccess: (JSONObject) -> Unit = {},
    ) {
        Thread {
            val result = runCatching(request)
            runOnUiThread {
                result.onSuccess { payload ->
                    onSuccess(payload)
                    detailInput?.isEnabled = true
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
                    refresh()
                }.onFailure { error ->
                    detailInput?.isEnabled = true
                    Toast.makeText(this, "操作失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun abort(runId: String) = perform("终止请求已发送", { CollaborationClient.abort(this, runId) }) {
        applyReturnedRun(it)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class RunAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@CollaborationActivity)
                .inflate(R.layout.item_collaboration_run, parent, false)
            val row = rows[position]
            view.findViewById<TextView>(R.id.tvCollaborationRunTitle).text = row.title
            view.findViewById<TextView>(R.id.tvCollaborationRunStatus).text =
                "${statusLabel(row.status)}，总调度${agentLabel(row.leader)}"
            view.findViewById<TextView>(R.id.tvCollaborationRunDetail).text = row.summary
            return view
        }
    }
}
