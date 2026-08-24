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
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2_000L)
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
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun refresh() {
        if (loading) return
        loading = true
        Thread {
            val result = runCatching { CollaborationClient.listRuns() }
            runOnUiThread {
                loading = false
                result.onSuccess { payload ->
                    rows.clear()
                    val array = payload.optJSONArray("runs")
                    if (array != null) {
                        for (index in 0 until array.length()) {
                            val run = array.optJSONObject(index) ?: continue
                            rows += parseRun(run)
                        }
                    }
                    adapter.notifyDataSetChanged()
                    statusView.text = if (rows.isEmpty()) "暂无协作任务" else "共${rows.size}条协作任务，自动刷新中"
                }.onFailure { error ->
                    statusView.text = "协作看板刷新失败：${error.message ?: "unknown"}"
                }
            }
        }.start()
    }

    private fun parseRun(run: JSONObject): RunRow {
        val details = mutableListOf<String>()
        val agents = run.optJSONObject("agents")
        listOf("codex" to "Codex", "claude" to "Claude Code", "minis" to "Minis").forEach { (id, label) ->
            val agent = agents?.optJSONObject(id)
            val status = statusLabel(agent?.optString("status").orEmpty())
            val error = agent?.optString("errorText").orEmpty()
            details += if (error.isBlank()) "$label：$status" else "$label：$status，$error"
            agent?.optString("requestText")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                details += "$label 收到：$it"
            }
            agent?.optString("actionText")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                details += "$label 动作：$it"
            }
            agent?.optString("responseText")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                details += "$label 回复：$it"
            }
        }
        val summary = run.optString("finalSummary").trim()
        if (summary.isNotEmpty()) details += "总调度汇总：$summary"
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

    private fun showRunActions(row: RunRow) {
        val builder = AlertDialog.Builder(this)
            .setTitle(row.title)
            .setMessage("状态：${statusLabel(row.status)}\n总调度：${row.leader}\n\n${row.detail}")
            .setNegativeButton(getString(R.string.cancel), null)
        if (row.status == "running") {
            builder.setPositiveButton("终止协作") { _, _ -> abort(row.id) }
        }
        builder.show()
    }

    private fun abort(runId: String) {
        Thread {
            val result = runCatching { CollaborationClient.abort(runId) }
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
            view.findViewById<TextView>(R.id.tvCollaborationRunTitle).text = row.title
            view.findViewById<TextView>(R.id.tvCollaborationRunStatus).text =
                "${statusLabel(row.status)}，总调度${row.leader}"
            view.findViewById<TextView>(R.id.tvCollaborationRunDetail).text = row.detail
            return view
        }
    }
}
