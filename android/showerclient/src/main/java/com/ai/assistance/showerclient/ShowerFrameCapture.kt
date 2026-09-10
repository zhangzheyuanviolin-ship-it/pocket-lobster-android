package com.ai.assistance.showerclient

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** CPU-readable decoder output, independent of any visible window or preview Surface. */
class ShowerFrameCapture(width: Int, height: Int) : AutoCloseable {
    private val lock = Any()
    private val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
    private val thread = HandlerThread("ShowerCaptureImages").apply { start() }
    private val renderer = ShowerVideoRenderer()
    private var image: Image? = null
    private var closed = false
    private var copiedPresentationUs = 0L

    init {
        reader.setOnImageAvailableListener({ source ->
            synchronized(lock) {
                if (!closed) {
                    runCatching { source.acquireLatestImage() }.getOrNull()?.let { next ->
                        image?.close()
                        image = next
                    }
                }
            }
        }, Handler(thread.looper))
        renderer.attach(reader.surface, width, height)
    }

    fun onFrame(data: ByteArray) = renderer.onFrame(data)

    fun diagnostics(): Map<String, Long> = renderer.diagnostics() + synchronized(lock) {
        mapOf("imageUs" to (image?.timestamp?.div(1000) ?: 0L), "copiedUs" to copiedPresentationUs)
    }

    suspend fun capturePng(): ByteArray? = withContext(Dispatchers.IO) {
        val (generation, expectedUs) = renderer.awaitDecodedFrame() ?: return@withContext null
        val bitmap = withTimeoutOrNull(3_000L) {
            while (true) {
                if (renderer.diagnostics()["generation"] != generation) return@withTimeoutOrNull null
                val result = synchronized(lock) {
                    val current = image
                    if (closed || current == null || current.timestamp / 1000 < expectedUs) null
                    else toBitmap(current).also { copiedPresentationUs = current.timestamp / 1000 }
                }
                if (result != null) return@withTimeoutOrNull result
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } ?: return@withContext null
        try {
            if (renderer.diagnostics()["generation"] != generation) return@withContext null
            ByteArrayOutputStream().use { bytes ->
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) bytes.toByteArray() else null
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        synchronized(lock) { if (closed) return }
        // Release producer outside the image lock so callbacks can drain its buffers.
        renderer.detach()
        synchronized(lock) {
            closed = true
            reader.setOnImageAvailableListener(null, null)
            image?.close()
            image = null
            reader.close()
        }
        thread.quitSafely()
    }

    private fun toBitmap(source: Image): Bitmap {
        require(source.format == ImageFormat.YUV_420_888) { "Unexpected decoded image format ${source.format}" }
        val crop = source.cropRect
        val planes = source.planes
        val y = planes[0]; val u = planes[1]; val v = planes[2]
        val pixels = IntArray(crop.width() * crop.height())
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        for (row in 0 until crop.height()) {
            val sy = crop.top + row
            for (col in 0 until crop.width()) {
                val sx = crop.left + col
                val yy = (yb.get(yb.position() + sy * y.rowStride + sx * y.pixelStride).toInt() and 255) - 16
                val uu = (ub.get(ub.position() + (sy / 2) * u.rowStride + (sx / 2) * u.pixelStride).toInt() and 255) - 128
                val vv = (vb.get(vb.position() + (sy / 2) * v.rowStride + (sx / 2) * v.pixelStride).toInt() and 255) - 128
                val c = 298 * yy.coerceAtLeast(0)
                val r = ((c + 409 * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((c - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
                val b = ((c + 516 * uu + 128) shr 8).coerceIn(0, 255)
                pixels[row * crop.width() + col] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
    }
}
