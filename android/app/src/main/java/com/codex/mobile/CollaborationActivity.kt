package com.codex.mobile

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
                    if (active && runningCount > 0) handler.postDelayed(poll, 2_000L)
                }.onFailure { error ->
                    statusView.text = "协作看板刷新失败：${error.message ?: "unknown"}"
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

    private fun addText(container: LinearLayout, text: String, heading: Boolean = false) {
        if (text.isBlank()) return
        val view = TextView(this).apply {
            this.text = text
            textSize = if (heading) 17f else 15f
            setPadding(0, if (heading) dp(16) else dp(5), 0, dp(5))
            if (heading && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        }
        container.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun addButton(container: LinearLayout, label: String, action: () -> Unit) {
        container.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun showRunDetails(row: RunRow) {
        val run = row.payload
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        addText(content, "${statusLabel(row.status)}，总调度${agentLabel(row.leader)}，第${run.optInt("turnNumber", 1)}轮对话")
        run.optString("errorText").trim().takeIf { it.isNotEmpty() }?.let { addText(content, "任务异常：$it") }
        run.optString("finalSummary").trim().takeIf { it.isNotEmpty() }?.let { addText(content, "总调度回复：$it", true) }

        val agents = run.optJSONObject("agents")
        val orderedIds = listOf(row.leader) + listOf("codex", "claude", "minis").filter { it != row.leader }
        orderedIds.forEach { id ->
            val agent = agents?.optJSONObject(id) ?: JSONObject()
            val role = if (agent.optString("role") == "leader") "总调度" else "协作成员"
            addText(content, "${agentLabel(id)}，$role，${statusLabel(agent.optString("status"))}", true)
            agent.optString("assignmentText").trim().takeIf { it.isNotEmpty() }?.let { addText(content, "本轮任务：$it") }
            agent.optString("actionText").trim().takeIf { it.isNotEmpty() }?.let { addText(content, "当前进展：$it") }
            agent.optString("responseText").trim()
                .takeIf { it.isNotEmpty() && !(role == "总调度" && it == run.optString("finalSummary").trim()) }
                ?.let { addText(content, "${if (role == "总调度") "总调度回复" else "分工结果"}：$it") }
            agent.optString("errorText").trim().takeIf { it.isNotEmpty() }?.let { addText(content, "失败原因：$it") }
        }

        val input = EditText(this).apply {
            hint = if (isActiveStatus(row.status)) "补充指令" else "继续协作"
            minLines = 2
        }
        content.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addButton(content, "发送") {
            val prompt = input.text.toString().trim()
            if (prompt.isBlank()) Toast.makeText(this, "请输入消息", Toast.LENGTH_SHORT).show()
            else perform("消息已提交") { CollaborationClient.continueRun(this, row.id, prompt) }
        }
        if (isActiveStatus(row.status)) {
            addButton(content, "终止协作") { abort(row.id) }
        } else {
            addButton(content, "重命名") { showRename(row) }
            addButton(content, if (run.optBoolean("archived")) "取消归档" else "归档") {
                perform("归档状态已更新") { CollaborationClient.archive(this, row.id, !run.optBoolean("archived")) }
            }
            addButton(content, "删除") {
                AlertDialog.Builder(this)
                    .setTitle("删除协作任务")
                    .setMessage("确定删除“${row.title}”？")
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton("删除") { _, _ -> perform("协作任务已删除") { CollaborationClient.delete(this, row.id) } }
                    .show()
            }
        }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle(row.title)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showRename(row: RunRow) {
        val input = EditText(this).apply { setText(row.title); selectAll() }
        AlertDialog.Builder(this)
            .setTitle("重命名协作任务")
            .setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton("保存") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotBlank()) perform("任务名称已更新") { CollaborationClient.rename(this, row.id, title) }
            }
            .show()
    }

    private fun perform(successMessage: String, request: () -> JSONObject) {
        Thread {
            val result = runCatching(request)
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
                    refresh()
                }.onFailure { error -> Toast.makeText(this, "操作失败：${error.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun abort(runId: String) = perform("终止请求已发送") { CollaborationClient.abort(this, runId) }
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
