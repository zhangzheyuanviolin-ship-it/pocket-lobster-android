package com.codex.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Read-only progress window for main-screen tasks. It is hidden while screenshots are captured. */
object PhoneUiAgentProgressOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var textView: TextView? = null
    private var hiddenForCapture = false
    private var generation = 0L

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    suspend fun show(context: Context): Boolean = withContext(Dispatchers.Main) {
        if (!canShow(context)) return@withContext false
        generation += 1
        hiddenForCapture = false
        if (textView != null) {
            textView?.visibility = View.VISIBLE
            return@withContext true
        }
        runCatching {
            val appContext = context.applicationContext
            val density = appContext.resources.displayMetrics.density
            val view = TextView(appContext).apply {
                text = "手机操作智能体正在启动"
                contentDescription = text
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 5
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.argb(232, 15, 23, 42))
                    cornerRadius = 8 * density
                    setStroke((1 * density).toInt().coerceAtLeast(1), Color.rgb(71, 85, 105))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isFocusable = true
            }
            val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams().apply {
                width = (300 * density).toInt()
                height = WindowManager.LayoutParams.WRAP_CONTENT
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                format = android.graphics.PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.END
                x = (8 * density).toInt()
                y = (48 * density).toInt()
                title = "口袋大龙虾手机操作进度"
            }
            manager.addView(view, params)
            windowManager = manager
            textView = view
            true
        }.getOrElse {
            dismissNowLocked()
            false
        }
    }

    fun update(title: String, detail: String) {
        val text = buildString {
            append(title.trim())
            detail.trim().takeIf(String::isNotBlank)?.let { append('\n').append(it) }
        }
        mainHandler.post {
            textView?.let { view ->
                view.text = text
                view.contentDescription = text.replace('\n', '，')
            }
        }
    }

    suspend fun hideForCapture() = withContext(Dispatchers.Main) {
        hiddenForCapture = true
        textView?.visibility = View.INVISIBLE
        delay(120)
    }

    suspend fun restoreAfterCapture() = withContext(Dispatchers.Main) {
        hiddenForCapture = false
        textView?.visibility = View.VISIBLE
    }

    fun dismissAfter(delayMs: Long) {
        val expectedGeneration = generation
        mainHandler.postDelayed({
            if (generation == expectedGeneration && !hiddenForCapture) dismissNowLocked()
        }, delayMs)
    }

    fun dismissNow() = mainHandler.post { dismissNowLocked() }

    private fun dismissNowLocked() {
        val manager = windowManager
        val view = textView
        windowManager = null
        textView = null
        hiddenForCapture = false
        if (manager != null && view != null) runCatching { manager.removeViewImmediate(view) }
    }
}
