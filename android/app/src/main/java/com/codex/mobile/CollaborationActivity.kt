package com.codex.mobile

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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

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

    private data class DetailViews(
        val status: TextView,
        val error: TextView,
        val summaryHeading: TextView,
        val summary: TextView,
        val agents: Map<String, AgentDetailViews>,
        val input: EditText,
        val send: Button,
        val abort: Button,
        val rename: Button,
        val archive: Button,
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
    private var detailInput: EditText? = null
    private var detailFingerprint = ""
    private var detailTurnNumber = 0
    private var detailLastFinalSummary = ""
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
        "pending" -> "等待中"
        "planning" -> "总调度分析中"
        "running" -> "成员执行中"
        "reviewing" -> "总调度审核中"
        "waiting_user" -> "等待用户"
        "completed" -> "已完成"
        "failed" -> "失败"
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
    }

    private fun showRunDetails(row: RunRow) {
        detailDialog?.dismiss()
        detailRunId = row.id
        detailFingerprint = ""
        detailTurnNumber = 0
        detailLastFinalSummary = ""
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val status = createText(content)
        val error = createText(content)
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
        val summaryHeading = createText(content, heading = true)
        val summary = createText(content)
        val input = EditText(this).apply {
            minLines = 2
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        content.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val send = Button(this).apply { text = "发送" }
        val abort = Button(this).apply { text = "终止协作" }
        val rename = Button(this).apply { text = "重命名" }
        val archive = Button(this)
        val delete = Button(this).apply { text = "删除" }
        listOf(send, abort, rename, archive, delete).forEach { button ->
            content.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        detailViews = DetailViews(status, error, summaryHeading, summary, agentViews, input, send, abort, rename, archive, delete)
        detailInput = input
        bindDetailActions()
        renderRunDetails(row)
        val scroll = ScrollView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(content)
        }
        detailDialog = AlertDialog.Builder(this)
            .setTitle(row.title)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    detailRunId = ""
                    detailViews = null
                    detailInput = null
                    detailFingerprint = ""
                    detailTurnNumber = 0
                    detailLastFinalSummary = ""
                    detailDialog = null
                }
                dialog.show()
                focusFinalSummary()
            }
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
        renderRunDetails(row)
    }

    private fun renderRunDetails(row: RunRow) {
        val views = detailViews ?: return
        val fingerprint = row.payload.toString()
        if (fingerprint == detailFingerprint) return
        detailFingerprint = fingerprint
        detailDialog?.setTitle(row.title)
        val run = row.payload
        val turnNumber = run.optInt("turnNumber", 1)
        if (detailTurnNumber != 0 && detailTurnNumber != turnNumber) collapseAgentDetails()
        detailTurnNumber = turnNumber
        setTextBlock(views.status, "${statusLabel(row.status)}，总调度${agentLabel(row.leader)}，第${turnNumber}轮对话")
        setTextBlock(views.error, run.optString("errorText").trim().takeIf { it.isNotEmpty() }?.let { "任务异常：$it" }.orEmpty())
        val finalSummary = run.optString("finalSummary").trim()
        val agents = run.optJSONObject("agents")
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

        val previousFinalSummary = detailLastFinalSummary
        detailLastFinalSummary = finalSummary
        setTextBlock(views.summaryHeading, if (finalSummary.isEmpty()) "" else "总调度最终回复")
        setTextBlock(views.summary, finalSummary)
        if (detailDialog?.isShowing == true && finalSummary.isNotEmpty() && finalSummary != previousFinalSummary) {
            focusFinalSummary()
        }

        views.input.hint = if (isActiveStatus(row.status)) "补充指令" else "继续协作"
        views.abort.visibility = if (isActiveStatus(row.status)) View.VISIBLE else View.GONE
        views.rename.visibility = if (isActiveStatus(row.status)) View.GONE else View.VISIBLE
        views.archive.visibility = if (isActiveStatus(row.status)) View.GONE else View.VISIBLE
        views.delete.visibility = if (isActiveStatus(row.status)) View.GONE else View.VISIBLE
        views.archive.text = if (run.optBoolean("archived")) "取消归档" else "归档"
    }

    private fun focusFinalSummary() {
        val summary = detailViews?.summary ?: return
        if (summary.visibility != View.VISIBLE || summary.text.isBlank()) return
        summary.post {
            summary.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    private fun currentDetailRow(): RunRow? = allRows.firstOrNull { it.id == detailRunId }

    private fun bindDetailActions() {
        val views = detailViews ?: return
        views.send.setOnClickListener {
            val row = currentDetailRow() ?: return@setOnClickListener
            val prompt = views.input.text.toString().trim()
            if (prompt.isBlank()) {
                Toast.makeText(this, "请输入消息", Toast.LENGTH_SHORT).show()
            } else {
                views.input.isEnabled = false
                perform("消息已提交", { CollaborationClient.continueRun(this, row.id, prompt) }) { payload ->
                    views.input.text.clear()
                    applyReturnedRun(payload)
                    handler.removeCallbacks(poll)
                    if (active) handler.postDelayed(poll, 400L)
                }
            }
        }
        views.abort.setOnClickListener { currentDetailRow()?.let { abort(it.id) } }
        views.rename.setOnClickListener { currentDetailRow()?.let(::showRename) }
        views.archive.setOnClickListener {
            currentDetailRow()?.let { row ->
                val archived = row.payload.optBoolean("archived")
                perform("归档状态已更新", { CollaborationClient.archive(this, row.id, !archived) }) {
                    applyReturnedRun(it)
                }
            }
        }
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
