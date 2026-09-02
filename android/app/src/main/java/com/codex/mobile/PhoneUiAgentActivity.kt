package com.codex.mobile

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class PhoneUiAgentActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var eventsLayout: LinearLayout
    private lateinit var eventsScroll: ScrollView
    private lateinit var taskInput: EditText
    private lateinit var maxStepsInput: EditText
    private lateinit var mainMode: RadioButton
    private lateinit var sendButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var displayButton: Button
    private var lastRenderKey = ""

    private val poller = object : Runnable {
        override fun run() {
            render(PhoneUiAgentRuntime.snapshot())
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_ui_agent)
        CodexForegroundService.ensureStarted(this)
        statusView = findViewById(R.id.tvPhoneUiStatus)
        eventsLayout = findViewById(R.id.layoutPhoneUiEvents)
        eventsScroll = findViewById(R.id.scrollPhoneUiEvents)
        taskInput = findViewById(R.id.inputPhoneUiTask)
        maxStepsInput = findViewById(R.id.inputPhoneUiMaxSteps)
        mainMode = findViewById(R.id.radioPhoneUiMain)
        sendButton = findViewById(R.id.btnPhoneUiSend)
        pauseButton = findViewById(R.id.btnPhoneUiPauseResume)
        stopButton = findViewById(R.id.btnPhoneUiStop)
        displayButton = findViewById(R.id.btnPhoneUiOpenDisplay)

        sendButton.setOnClickListener { requestStart() }
        pauseButton.setOnClickListener {
            val status = PhoneUiAgentRuntime.snapshot().optString("status")
            if (status == "paused") PhoneUiAgentRuntime.resume() else PhoneUiAgentRuntime.pause()
            render(PhoneUiAgentRuntime.snapshot(), force = true)
        }
        stopButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("终止手机操作任务")
                .setMessage("确定立即停止模型请求和后续屏幕动作吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("终止") { _, _ ->
                    PhoneUiAgentRuntime.cancel()
                    render(PhoneUiAgentRuntime.snapshot(), force = true)
                }
                .show()
        }
        displayButton.setOnClickListener {
            if (!PhoneUiAgentRuntime.hasVirtualDisplay()) {
                Toast.makeText(this, "当前没有可接管的虚拟屏幕", Toast.LENGTH_SHORT).show()
            } else {
                PhoneUiAgentRuntime.pause()
                startActivity(Intent(this, PhoneUiAgentDisplayActivity::class.java))
            }
        }
        findViewById<Button>(R.id.btnPhoneUiModels).setOnClickListener {
            startActivity(Intent(this, PhoneUiAgentModelManagerActivity::class.java))
        }
        findViewById<Button>(R.id.btnPhoneUiHistory).setOnClickListener { showHistory() }
        render(PhoneUiAgentRuntime.snapshot(), force = true)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    override fun onPause() {
        handler.removeCallbacks(poller)
        super.onPause()
    }

    private fun requestStart() {
        val task = taskInput.text.toString().trim()
        if (task.isEmpty()) {
            taskInput.error = "请输入任务"
            return
        }
        val maxSteps = maxStepsInput.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 25
        val mode = if (mainMode.isChecked) PhoneUiScreenMode.MAIN else PhoneUiScreenMode.VIRTUAL
        val start = {
            runCatching { PhoneUiAgentRuntime.startTask(this, task, mode, maxSteps) }
                .onSuccess {
                    taskInput.text.clear()
                    render(it, force = true)
                }
                .onFailure { Toast.makeText(this, "启动失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
        if (mode == PhoneUiScreenMode.MAIN) {
            AlertDialog.Builder(this)
                .setTitle("主屏幕操作确认")
                .setMessage("智能体将操作您当前使用的主屏幕。您可以随时返回本页面暂停或终止任务。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始") { _, _ -> start() }
                .show()
        } else {
            start()
        }
    }

    private fun render(snapshot: JSONObject, force: Boolean = false) {
        val renderKey = snapshot.optString("updatedAt") + ":" + snapshot.optString("status") + ":" + snapshot.optInt("step")
        if (!force && renderKey == lastRenderKey) return
        lastRenderKey = renderKey
        val status = snapshot.optString("status", "idle")
        val step = snapshot.optInt("step")
        val maxSteps = snapshot.optInt("maxSteps", 25)
        statusView.text = "状态：${statusLabel(status)}；步骤：$step/$maxSteps；${snapshot.optString("statusText")}"
        statusView.announceForAccessibility(statusView.text)
        val active = status in setOf("starting", "running", "paused")
        sendButton.isEnabled = !active
        pauseButton.isEnabled = status in setOf("running", "paused")
        pauseButton.text = if (status == "paused") "继续任务" else "暂停任务"
        stopButton.isEnabled = active
        displayButton.isEnabled = PhoneUiAgentRuntime.hasVirtualDisplay()

        eventsLayout.removeAllViews()
        val events = snapshot.optJSONArray("events")
        if (events == null || events.length() == 0) {
            eventsLayout.addView(eventView("任务动态", "发送任务后，每一步判断和动作结果会显示在这里。"))
        } else {
            for (index in 0 until events.length()) {
                val event = events.optJSONObject(index) ?: continue
                eventsLayout.addView(eventView(event.optString("title"), event.optString("detail")))
            }
        }
        eventsScroll.post { eventsScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun eventView(title: String, detail: String): View = TextView(this).apply {
        text = "$title\n$detail"
        contentDescription = "$title，$detail"
        setTextColor(android.graphics.Color.rgb(226, 232, 240))
        textSize = 15f
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setPadding(0, 12, 0, 12)
    }

    private fun statusLabel(status: String): String = when (status) {
        "idle" -> "空闲"
        "starting" -> "初始化"
        "running" -> "执行中"
        "paused" -> "已暂停"
        "takeover" -> "等待接管"
        "completed" -> "已完成"
        "cancelled" -> "已终止"
        "step_limit" -> "达到步数上限"
        "interrupted" -> "进程中断"
        else -> "失败"
    }

    private fun showHistory() {
        val history = PhoneUiAgentRuntime.history()
        if (history.length() == 0) {
            Toast.makeText(this, "暂无历史任务", Toast.LENGTH_SHORT).show()
            return
        }
        val rows = (history.length() - 1 downTo 0).mapNotNull { history.optJSONObject(it) }
        val labels = rows.map { row ->
            "${statusLabel(row.optString("status"))}，${row.optString("task").take(60)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("手机操作历史任务")
            .setItems(labels) { _, which -> showHistoryDetail(rows[which]) }
            .setNeutralButton("清空历史") { _, _ -> confirmClearHistory() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showHistoryDetail(row: JSONObject) {
        val detail = buildString {
            append("任务：${row.optString("task")}\n")
            append("状态：${statusLabel(row.optString("status"))}\n")
            append("模式：${if (row.optString("mode") == "virtual") "虚拟屏幕" else "主屏幕"}\n")
            val events = row.optJSONArray("events")
            if (events != null) {
                for (index in 0 until events.length()) {
                    val event = events.optJSONObject(index) ?: continue
                    append("\n${event.optString("title")}\n${event.optString("detail")}")
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("历史任务详情")
            .setMessage(detail)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空手机操作历史")
            .setMessage("确定永久清空全部手机操作历史任务吗？当前任务不会被删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清空") { _, _ -> PhoneUiAgentRuntime.clearHistory() }
            .show()
    }
}
