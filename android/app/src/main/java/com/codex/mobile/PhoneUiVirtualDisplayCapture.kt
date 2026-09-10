package com.codex.mobile

import android.content.Context
import com.ai.assistance.showerclient.ShowerFrameCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Model observations do not depend on an off-screen preview window's lifecycle. */
object PhoneUiVirtualDisplayCapture {
    private val mutex = Mutex()
    @Volatile private var capture: ShowerFrameCapture? = null
    private var attachedDisplayId: Int? = null
    private var attachedSize: Pair<Int, Int>? = null

    suspend fun attach(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val controller = PhoneUiShowerRuntime.controller
            val id = controller.getDisplayId() ?: return@withLock false
            val size = controller.getVideoSize() ?: return@withLock false
            if (!force && capture != null && attachedDisplayId == id && attachedSize == size) return@withLock true
            detachLocked()
            try {
                val created = ShowerFrameCapture(size.first, size.second)
                capture = created
                attachedDisplayId = id
                attachedSize = size
                controller.addBinaryHandler(this@PhoneUiVirtualDisplayCapture, created::onFrame)
                check(controller.refreshVideoStream()) { "Video producer is disconnected" }
                true
            } catch (error: Exception) {
                detachLocked()
                false
            }
        }
    }

    suspend fun capturePng(timeoutMs: Long = 5_000L): ByteArray? = mutex.withLock {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                capture?.capturePng()?.let { return@withTimeoutOrNull it }
                delay(125)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    suspend fun detach() = withContext(Dispatchers.IO) { mutex.withLock { detachLocked() } }

    fun diagnostics(): Map<String, Long> = capture?.diagnostics().orEmpty() +
        mapOf("displayId" to (attachedDisplayId ?: -1).toLong())

    private fun detachLocked() {
        PhoneUiShowerRuntime.controller.removeBinaryHandler(this)
        capture?.close()
        capture = null
        attachedDisplayId = null
        attachedSize = null
    }
}
