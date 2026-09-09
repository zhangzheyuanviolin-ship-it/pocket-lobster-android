package com.codex.mobile

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.ai.assistance.showerclient.ShowerLog
import com.ai.assistance.showerclient.ui.ShowerSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the virtual display video decoder alive and exposes its latest rendered frame. */
object PhoneUiVirtualDisplayCapture {
    private const val TAG = "PhoneUiVirtualCapture"
    @Volatile private var windowManager: WindowManager? = null
    @Volatile private var windowView: View? = null
    @Volatile private var surfaceView: ShowerSurfaceView? = null
    private var attachedDisplayId: Int? = null
    private var attachedSize: Pair<Int, Int>? = null

    suspend fun attach(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.Main) {
        val displayId = PhoneUiShowerRuntime.controller.getDisplayId()
        val size = PhoneUiShowerRuntime.controller.getVideoSize()
        if (!force && displayId != null && attachedDisplayId == displayId && attachedSize == size &&
            windowView?.isAttachedToWindow == true && surfaceView?.holder?.surface?.isValid == true) {
            return@withContext true
        }
        detachLocked()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            ShowerLog.w(TAG, "Overlay permission is unavailable; Shower screenshot RPC remains the fallback")
            return@withContext false
        }
        return@withContext runCatching {
            val manager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = ShowerSurfaceView(context.applicationContext).apply {
                bindController(PhoneUiShowerRuntime.controller)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val density = context.resources.displayMetrics.density
            val surfaceSize = density.toInt().coerceAtLeast(1)
            val hostSize = (48 * density).toInt().coerceAtLeast(surfaceSize)
            val host = FrameLayout(context.applicationContext).apply {
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(
                    view,
                    FrameLayout.LayoutParams(surfaceSize, surfaceSize, Gravity.TOP or Gravity.END),
                )
            }
            val params = WindowManager.LayoutParams().apply {
                width = hostSize
                height = hostSize
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
            manager.addView(host, params)
            windowManager = manager
            windowView = host
            surfaceView = view
            attachedDisplayId = displayId
            attachedSize = size
            true
        }.getOrElse { error ->
            ShowerLog.e(TAG, "Failed to attach virtual display frame capture", error)
            detachLocked()
            false
        }
    }

    suspend fun capturePng(timeoutMs: Long = 5_000L): ByteArray? {
        return withTimeoutOrNull(timeoutMs) {
            var bytes: ByteArray? = null
            while (bytes == null || bytes.size <= 1_024) {
                bytes = surfaceView?.captureCurrentFramePng()
                if (bytes == null || bytes.size <= 1_024) delay(125)
            }
            bytes
        }
    }

    suspend fun detach() = withContext(Dispatchers.Main) { detachLocked() }

    fun diagnostics(): Map<String, Long> = (surfaceView?.captureDiagnostics().orEmpty()) +
        mapOf("displayId" to (attachedDisplayId ?: -1).toLong())

    private fun detachLocked() {
        val view = windowView
        val manager = windowManager
        windowView = null
        surfaceView = null
        windowManager = null
        attachedDisplayId = null
        attachedSize = null
        if (view != null && manager != null) {
            runCatching { manager.removeViewImmediate(view) }
        }
    }
}
