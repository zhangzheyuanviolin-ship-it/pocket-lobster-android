package com.codex.mobile

import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ai.assistance.showerclient.ui.ShowerSurfaceView
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

class PhoneUiAgentDisplayActivity : AppCompatActivity() {
    private val inputExecutor = Executors.newSingleThreadExecutor()
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_ui_display)
        val surface = findViewById<ShowerSurfaceView>(R.id.viewPhoneUiDisplay)
        val status = findViewById<TextView>(R.id.tvPhoneUiDisplayStatus)
        if (!PhoneUiAgentRuntime.hasVirtualDisplay()) {
            status.text = "虚拟屏幕不可用，请先在手机操作智能体页面启动虚拟屏幕任务"
            surface.isEnabled = false
        } else {
            PhoneUiAgentRuntime.pause()
            surface.bindController(PhoneUiShowerRuntime.controller)
            status.text = "虚拟屏幕已暂停智能体并交给您操作；完成后点击继续智能体任务"
            surface.setOnTouchListener { view, event ->
                val size = PhoneUiShowerRuntime.controller.getVideoSize() ?: return@setOnTouchListener true
                val scaleX = size.first.toFloat() / view.width.coerceAtLeast(1)
                val scaleY = size.second.toFloat() / view.height.coerceAtLeast(1)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x; downY = event.y; downAt = System.currentTimeMillis()
                    }
                    MotionEvent.ACTION_UP -> {
                        val endX = event.x; val endY = event.y
                        val startPx = (downX * scaleX).toInt(); val startPy = (downY * scaleY).toInt()
                        val endPx = (endX * scaleX).toInt(); val endPy = (endY * scaleY).toInt()
                        val duration = (System.currentTimeMillis() - downAt).coerceIn(100, 2_000)
                        inputExecutor.submit {
                            runBlocking {
                                if (kotlin.math.abs(endX - downX) > 24 || kotlin.math.abs(endY - downY) > 24) {
                                    PhoneUiShowerRuntime.controller.swipe(startPx, startPy, endPx, endPy, duration)
                                } else {
                                    PhoneUiShowerRuntime.controller.tap(endPx, endPy)
                                }
                            }
                        }
                    }
                }
                true
            }
        }
        findViewById<Button>(R.id.btnPhoneUiDisplayResume).setOnClickListener {
            PhoneUiAgentRuntime.resume()
            Toast.makeText(this, "智能体任务已继续", Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<Button>(R.id.btnPhoneUiDisplayClose).setOnClickListener { finish() }
    }

    override fun onDestroy() {
        inputExecutor.shutdownNow()
        super.onDestroy()
    }
}
