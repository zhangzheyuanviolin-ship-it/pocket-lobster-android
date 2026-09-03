package com.codex.mobile

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.ai.assistance.showerclient.ShowerLog
import com.ai.assistance.showerclient.ui.ShowerSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Keeps the virtual display video decoder alive and exposes its latest rendered frame. */
object PhoneUiVirtualDisplayCapture {
    private const val TAG = "PhoneUiVirtualCapture"
    @Volatile private var windowManager: WindowManager? = null
    @Volatile private var surfaceView: ShowerSurfaceView? = null

    suspend fun attach(context: Context): Boolean = withContext(Dispatchers.Main) {
        detachLocked()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            ShowerLog.w(TAG, "Overlay permission is unavailable; Shower screenshot RPC remains the fallback")
            return@withContext false
        }
        val videoSize = PhoneUiShowerRuntime.controller.getVideoSize()
        if (videoSize != null) {
            // Re-registering the sink makes the encoder resend codec setup frames after recovery.
            PhoneUiShowerRuntime.controller.ensureDisplay(
                context.applicationContext,
                videoSize.first,
                videoSize.second,
                context.resources.displayMetrics.densityDpi,
                3_000,
            )
        }
        return@withContext runCatching {
            val manager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = ShowerSurfaceView(context.applicationContext).apply {
                bindController(PhoneUiShowerRuntime.controller)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val params = WindowManager.LayoutParams().apply {
                width = 1
                height = 1
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.END
                x = 0
                y = 0
                title = "Pocket Lobster phone agent frame capture"
            }
            manager.addView(view, params)
            windowManager = manager
            surfaceView = view
            true
        }.getOrElse { error ->
            ShowerLog.e(TAG, "Failed to attach virtual display frame capture", error)
            detachLocked()
            false
        }
    }

    suspend fun capturePng(timeoutMs: Long = 5_000L): ByteArray? {
        val attempts = (timeoutMs / 125L).coerceAtLeast(1L).toInt()
        repeat(attempts) {
            val bytes = surfaceView?.captureCurrentFramePng()
            if (bytes != null && bytes.size > 1_024) return bytes
            delay(125)
        }
        return null
    }

    suspend fun detach() = withContext(Dispatchers.Main) { detachLocked() }

    private fun detachLocked() {
        val view = surfaceView
        val manager = windowManager
        surfaceView = null
        windowManager = null
        if (view != null && manager != null) {
            runCatching { manager.removeViewImmediate(view) }
        }
    }
}
