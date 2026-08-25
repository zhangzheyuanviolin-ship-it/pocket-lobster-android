package com.codex.mobile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
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
        val detail: String,
    )

    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private val rows = mutableListOf<RunRow>()
    private val adapter = RunAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var loading = false
    private var active = false
    private var lastRenderFingerprint = ""
    private val poll = object : Runnable {
        override fun run() {
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collaboration)
        statusView = findViewById(R.id.tvCollaborationStatus)
        listView = findViewById(R.id.listCollaborationRuns)
        listView.adapter = adapter

        findViewById<Button>(R.id.btnCollaborationRefresh).setOnClickListener { refresh() }
        listView.setOnItemClickListener { _, _, position, _ -> showRunActions(rows[position]) }
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

    private fun refresh() {
        if (loading) return
        loading = true
        Thread {
            val result = runCatching { CollaborationClient.listRuns(this) }
            runOnUiThread {
                loading = false
                result.onSuccess { payload ->
                    val nextRows = mutableListOf<RunRow>()
                    val array = payload.optJSONArray("runs")
                    if (array != null) {
                        for (index in 0 until array.length()) {
                            val run = array.optJSONObject(index) ?: continue
                            nextRows += parseRun(run)
                        }
                    }
                    val fingerprint = nextRows.joinToString("|") { "${it.id}:${it.status}:${it.detail}" }
                    if (fingerprint != lastRenderFingerprint) {
                        rows.clear()
                        rows += nextRows
                        lastRenderFingerprint = fingerprint
                        adapter.notifyDataSetChanged()
                    }
                    val runningCount = rows.count { it.status == "running" }
                    val nextStatus = when {
                        runningCount > 0 -> "当前有${runningCount}项协作任务正在运行，状态将自动更新"
                        rows.isNotEmpty() -> "当前没有运行中的协作任务，下方是历史协作记录"
                        else -> "当前没有协作任务，可在任一智能体页面打开三智能体协作后发送任务"
                    }
                    if (statusView.text.toString() != nextStatus) statusView.text = nextStatus
                    if (active && runningCount > 0) handler.postDelayed(poll, 2_000L)
                }.onFailure { error ->
                    val nextStatus = "协作看板刷新失败：${error.message ?: "unknown"}"
                    if (statusView.text.toString() != nextStatus) statusView.text = nextStatus
                }
            }
        }.start()
    }

    private fun parseRun(run: JSONObject): RunRow {
        val details = mutableListOf<String>()
        run.optString("errorText").trim().takeIf { it.isNotEmpty() }?.let {
            details += "任务异常：$it"
        }
        val agents = run.optJSONObject("agents")
        val runCompleted = run.optString("status") == "completed"
        listOf("codex" to "Codex", "claude" to "Claude Code", "minis" to "Minis").forEach { (id, label) ->
            val agent = agents?.optJSONObject(id)
            val status = statusLabel(agent?.optString("status").orEmpty())
            val role = if (agent?.optString("role") == "leader") "总调度" else "协作成员"
            val error = agent?.optString("errorText").orEmpty()
            details += if (error.isBlank()) "$label，$role，$status" else "$label，$role，$status，失败原因：$error"
            agent?.optString("actionText")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                details += "$label 当前进展：$it"
            }
            agent?.optString("responseText")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                val outputLabel = if (role == "总调度") {
                    if (runCompleted) "最终汇总" else "阶段输出"
                } else {
                    "分工输出"
                }
                details += "$label $outputLabel：$it"
            }
        }
        return RunRow(
            id = run.optString("id"),
            title = run.optString("title").ifBlank { "三智能体协作" },
            status = run.optString("status"),
            leader = run.optString("leader"),
            detail = details.joinToString("\n"),
        )
    }

    private fun statusLabel(status: String): String = when (status) {
        "pending" -> "等待中"
        "running" -> "执行中"
        "synthesizing" -> "正在汇总"
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

    private fun showRunActions(row: RunRow) {
        val builder = AlertDialog.Builder(this)
            .setTitle(row.title)
            .setMessage("状态：${statusLabel(row.status)}\n总调度：${agentLabel(row.leader)}\n\n${row.detail}")
            .setNegativeButton(getString(R.string.cancel), null)
        if (row.status == "running") {
            builder.setPositiveButton("终止协作") { _, _ -> abort(row.id) }
        }
        builder.show()
    }

    private fun abort(runId: String) {
        Thread {
            val result = runCatching { CollaborationClient.abort(this, runId) }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, "终止请求已发送", Toast.LENGTH_SHORT).show()
                    refresh()
                }.onFailure { error ->
                    Toast.makeText(this, "终止失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private inner class RunAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@CollaborationActivity)
                .inflate(R.layout.item_collaboration_run, parent, false)
            val row = rows[position]
            view.findViewById<TextView>(R.id.tvCollaborationRunTitle).text =
                "${if (row.status == "running") "当前任务" else "历史任务"}：${row.title}"
            view.findViewById<TextView>(R.id.tvCollaborationRunStatus).text =
                "${statusLabel(row.status)}，总调度${agentLabel(row.leader)}"
            view.findViewById<TextView>(R.id.tvCollaborationRunDetail).text = row.detail
            return view
        }
    }
}
