package com.ai.assistance.showerclient

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * H.264 decoder that renders the Shower video stream onto a Surface.
 * Each instance handles one video stream for a specific virtual display.
 */
class ShowerVideoRenderer {

    companion object {
        private const val TAG = "ShowerVideoRenderer"
    }

    private val lock = Any()

    @Volatile
    private var decoder: MediaCodec? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var csd0: ByteArray? = null

    @Volatile
    private var csd1: ByteArray? = null

    private val pendingFrames = mutableListOf<ByteArray>()

    @Volatile
    private var width: Int = 0

    @Volatile
    private var height: Int = 0

    @Volatile
    private var warnedNoSurface: Boolean = false

    @Volatile private var renderedPresentationUs = 0L

    fun attach(surface: Surface, videoWidth: Int, videoHeight: Int) {
        synchronized(lock) {
            this.surface = surface
            this.width = videoWidth
            this.height = videoHeight
            warnedNoSurface = false
            releaseDecoderLocked()
            csd0 = null
            csd1 = null
            renderedPresentationUs = 0L
            pendingFrames.clear()
        }
    }

    fun detach() {
        synchronized(lock) {
            releaseDecoderLocked()
            surface = null
            pendingFrames.clear()
            warnedNoSurface = false
        }
    }

    private fun releaseDecoderLocked() {
        val dec = decoder
        decoder = null
        renderedPresentationUs = 0L
        if (dec != null) {
            try {
                dec.stop()
            } catch (_: Exception) {
            }
            try {
                dec.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Called for each H.264 packet. */
    fun onFrame(data: ByteArray) {
        synchronized(lock) {
            if (surface == null || width <= 0 || height <= 0) {
                if (!warnedNoSurface) {
                    ShowerLog.w(TAG, "onFrame: no surface or invalid size; dropping frames")
                    warnedNoSurface = true
                }
                return
            }

            val packet = maybeAvccToAnnexb(data)

            if (decoder == null) {
                val nalUnits = splitAnnexbNalUnits(packet)
                var containsVideoFrame = nalUnits.isEmpty()
                nalUnits.forEach { unit ->
                    when (unit.type) {
                        7 -> if (csd0 == null) csd0 = unit.bytes
                        8 -> if (csd1 == null) csd1 = unit.bytes
                        else -> containsVideoFrame = true
                    }
                }
                if (containsVideoFrame) {
                    if (pendingFrames.size >= 120) pendingFrames.removeAt(0)
                    pendingFrames.add(packet)
                }

                if (csd0 != null && csd1 != null) {
                    initDecoderLocked()
                    val framesToProcess = pendingFrames.toList()
                    pendingFrames.clear()
                    framesToProcess.forEach { frame -> queueFrameToDecoder(frame) }
                }
                return
            }

            queueFrameToDecoder(packet)
        }
    }

    private data class NalUnit(val type: Int, val bytes: ByteArray)

    private fun splitAnnexbNalUnits(packet: ByteArray): List<NalUnit> {
        fun startCodeLengthAt(index: Int): Int {
            if (index + 3 <= packet.size && packet[index] == 0.toByte() && packet[index + 1] == 0.toByte() && packet[index + 2] == 1.toByte()) {
                return 3
            }
            if (index + 4 <= packet.size && packet[index] == 0.toByte() && packet[index + 1] == 0.toByte() && packet[index + 2] == 0.toByte() && packet[index + 3] == 1.toByte()) {
                return 4
            }
            return 0
        }

        val starts = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index < packet.size - 2) {
            val length = startCodeLengthAt(index)
            if (length > 0) {
                starts += index to length
                index += length
            } else {
                index++
            }
        }
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexedNotNull { unitIndex, (start, codeLength) ->
            val payloadStart = start + codeLength
            val end = starts.getOrNull(unitIndex + 1)?.first ?: packet.size
            if (payloadStart >= end) return@mapIndexedNotNull null
            NalUnit(packet[payloadStart].toInt() and 0x1F, packet.copyOfRange(start, end))
        }
    }

    private fun queueFrameToDecoder(packet: ByteArray) {
        synchronized(lock) {
            val dec = decoder ?: return
            try {
                val inIndex = dec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer: ByteBuffer? = dec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(packet)
                        dec.queueInputBuffer(inIndex, 0, packet.size, System.nanoTime() / 1000, 0)
                    }
                }

                drainOutputLocked(dec)
            } catch (e: Exception) {
                ShowerLog.e(TAG, "Decoder error on frame", e)
                releaseDecoderLocked()
                pendingFrames.clear()
            }
        }
    }

    // A decoder can produce its last output after onFrame returns. Capture also drains
    // this queue so a static page cannot strand its final frame behind an old Surface.
    private fun drainOutputLocked(dec: MediaCodec) {
        val info = BufferInfo()
        while (true) {
            when (val index = dec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                else -> if (index >= 0) dec.releaseOutputBuffer(index, true) else return
            }
        }
    }

    private fun maybeAvccToAnnexb(packet: ByteArray): ByteArray {
        if (packet.size >= 4) {
            val b0 = packet[0].toInt() and 0xFF
            val b1 = packet[1].toInt() and 0xFF
            val b2 = packet[2].toInt() and 0xFF
            val b3 = packet[3].toInt() and 0xFF
            if (b0 == 0 && b1 == 0 && ((b2 == 0 && b3 == 1) || b2 == 1)) {
                return packet
            }
        }
        val out = ByteArrayOutputStream()
        var i = 0
        val n = packet.size
        while (i + 4 <= n) {
            val nalLen =
                ((packet[i].toInt() and 0xFF) shl 24) or
                    ((packet[i + 1].toInt() and 0xFF) shl 16) or
                    ((packet[i + 2].toInt() and 0xFF) shl 8) or
                    (packet[i + 3].toInt() and 0xFF)
            i += 4
            if (nalLen <= 0 || i + nalLen > n) return packet
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(packet, i, nalLen)
            i += nalLen
        }
        val result = out.toByteArray()
        return if (result.isNotEmpty()) result else packet
    }

    suspend fun captureCurrentFramePng(): ByteArray? {
        val requestedPresentationUs = System.nanoTime() / 1000
        val fresh = withTimeoutOrNull(4_000L) {
            while (renderedPresentationUs < requestedPresentationUs) {
                withContext(Dispatchers.IO) {
                    synchronized(lock) {
                        decoder?.let { dec ->
                            runCatching { drainOutputLocked(dec) }.onFailure {
                                ShowerLog.e(TAG, "Capture output drain failed", it)
                            }
                        }
                    }
                }
                delay(10)
            }
            true
        } ?: false
        if (!fresh) {
            ShowerLog.w(TAG, "No newly rendered video frame before capture deadline")
            return null
        }
        val s: Surface
        val w: Int
        val h: Int
        synchronized(lock) {
            val localSurface = surface
            if (localSurface == null || width <= 0 || height <= 0) return null
            s = localSurface
            w = width
            h = height
        }

        if (Build.VERSION.SDK_INT < 26) return null

        return withTimeoutOrNull(2_000L) { withContext(Dispatchers.Main) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                cont.invokeOnCancellation {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                try {
                    PixelCopy.request(s, bitmap, { result ->
                        if (!cont.isActive) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            return@request
                        }
                        val bytes = if (result == PixelCopy.SUCCESS) {
                            runCatching {
                                val baos = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                                baos.toByteArray()
                            }.getOrNull()
                        } else {
                            null
                        }
                        if (!bitmap.isRecycled) bitmap.recycle()
                        cont.resume(bytes)
                    }, handler)
                } catch (_: Exception) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (cont.isActive) {
                        cont.resume(null)
                    }
                }
            }
        } }
    }

    private fun initDecoderLocked() {
        val s = surface ?: return
        val localCsd0 = csd0 ?: return
        val localCsd1 = csd1 ?: return
        if (width <= 0 || height <= 0) return

        try {
            val csd0Annexb = maybeAvccToAnnexb(localCsd0)
            val csd1Annexb = maybeAvccToAnnexb(localCsd1)

            val format = MediaFormat.createVideoFormat("video/avc", width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0Annexb))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1Annexb))

            val dec = MediaCodec.createDecoderByType("video/avc")
            dec.configure(format, s, null, 0)
            dec.setOnFrameRenderedListener({ codec, presentationUs, _ ->
                if (decoder === codec) renderedPresentationUs = maxOf(renderedPresentationUs, presentationUs)
            }, Handler(Looper.getMainLooper()))
            dec.start()
            decoder = dec
            ShowerLog.d(TAG, "MediaCodec decoder initialized for ${width}x${height}")
        } catch (e: Exception) {
            ShowerLog.e(TAG, "Failed to init decoder", e)
            releaseDecoderLocked()
        }
    }
}
