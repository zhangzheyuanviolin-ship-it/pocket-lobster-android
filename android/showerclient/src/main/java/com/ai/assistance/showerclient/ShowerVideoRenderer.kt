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

    @Volatile private var submittedPresentationUs = 0L
    @Volatile private var queuedPresentationUs = 0L
    private var awaitingKeyFrame = true
    @Volatile private var surfaceGeneration = 0L
    @Volatile private var lastPixelCopyResult = -1

    fun diagnostics(): Map<String, Long> = synchronized(lock) {
        mapOf("generation" to surfaceGeneration, "queuedUs" to queuedPresentationUs,
            "submittedUs" to submittedPresentationUs, "pixelCopyResult" to lastPixelCopyResult.toLong(),
            "width" to width.toLong(), "height" to height.toLong())
    }

    fun attach(surface: Surface, videoWidth: Int, videoHeight: Int) {
        synchronized(lock) {
            surfaceGeneration++
            this.surface = surface
            this.width = videoWidth
            this.height = videoHeight
            warnedNoSurface = false
            releaseDecoderLocked()
            csd0 = null
            csd1 = null
            submittedPresentationUs = 0L
            pendingFrames.clear()
        }
    }

    fun detach() {
        synchronized(lock) {
            surfaceGeneration++
            releaseDecoderLocked()
            surface = null
            pendingFrames.clear()
            warnedNoSurface = false
        }
    }

    private fun releaseDecoderLocked() {
        // In-flight PixelCopy results from the previous decoder are obsolete too,
        // even if the Java Surface object itself has not changed.
        surfaceGeneration++
        val dec = decoder
        decoder = null
        submittedPresentationUs = 0L
        queuedPresentationUs = 0L
        awaitingKeyFrame = true
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
                val types = splitAnnexbNalUnits(packet).map { it.type }
                if (types.none { it in 1..5 }) return
                if (awaitingKeyFrame && 5 !in types) return
                drainOutputLocked(dec)
                var inIndex = dec.dequeueInputBuffer(10000)
                var attempts = 0
                while (inIndex < 0 && attempts++ < 3) {
                    drainOutputLocked(dec)
                    inIndex = dec.dequeueInputBuffer(10000)
                }
                if (inIndex >= 0) {
                    val inputBuffer: ByteBuffer? = dec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(packet)
                        val presentationUs = System.nanoTime() / 1000
                        dec.queueInputBuffer(inIndex, 0, packet.size, presentationUs, 0)
                        queuedPresentationUs = presentationUs
                        awaitingKeyFrame = false
                    }
                } else {
                    // Dropping a reference frame silently corrupts all following P frames.
                    ShowerLog.w(TAG, "Decoder input stalled; waiting for a new key frame")
                    releaseDecoderLocked()
                    pendingFrames.clear()
                    return
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
                else -> if (index >= 0) {
                    dec.releaseOutputBuffer(index, true)
                    submittedPresentationUs = maxOf(submittedPresentationUs, info.presentationTimeUs)
                } else return
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
        val (generation, requestedPresentationUs) = synchronized(lock) { surfaceGeneration to queuedPresentationUs }
        val fresh = withTimeoutOrNull(4_000L) {
            while (true) {
                if (generation != surfaceGeneration) return@withTimeoutOrNull false
                withContext(Dispatchers.IO) {
                    synchronized(lock) {
                        decoder?.let { dec ->
                            runCatching { drainOutputLocked(dec) }.onFailure {
                                ShowerLog.e(TAG, "Capture output drain failed", it)
                            }
                        }
                    }
                }
                // A static screen need not emit a frame after this capture request.
                // Drain input already received, then copy the Surface's latest real buffer.
                if (submittedPresentationUs > 0L &&
                    submittedPresentationUs >= requestedPresentationUs) break
                delay(10)
            }
            true
        } ?: false
        if (!fresh) {
            ShowerLog.w(TAG, "No decoded video buffer before capture deadline: submittedUs=$submittedPresentationUs requestedUs=$requestedPresentationUs")
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

        val copied = withTimeoutOrNull(2_000L) { withContext(Dispatchers.Main) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            suspendCancellableCoroutine<Bitmap?> { cont ->
                val handler = Handler(Looper.getMainLooper())
                // PixelCopy owns the destination until its callback, even after cancellation.
                try {
                    PixelCopy.request(s, bitmap, { result ->
                        lastPixelCopyResult = result
                        if (!cont.isActive) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            return@request
                        }
                        val success = result == PixelCopy.SUCCESS && generation == surfaceGeneration
                        if (!success && !bitmap.isRecycled) bitmap.recycle()
                        cont.resume(if (success) bitmap else null, onCancellation = { _, discarded, _ ->
                            if (discarded != null && !discarded.isRecycled) discarded.recycle()
                        })
                    }, handler)
                } catch (_: Exception) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (cont.isActive) {
                        cont.resume(null)
                    }
                }
            }
        } } ?: return null
        return try {
            withContext(Dispatchers.IO) {
                if (generation != surfaceGeneration) return@withContext null
                runCatching {
                    val baos = ByteArrayOutputStream()
                    if (!copied.compress(Bitmap.CompressFormat.PNG, 100, baos)) null else baos.toByteArray()
                }.getOrNull()
            }
        } finally {
            if (!copied.isRecycled) copied.recycle()
        }
    }

    private fun initDecoderLocked() {
        val s = surface ?: return
        val localCsd0 = csd0 ?: return
        val localCsd1 = csd1 ?: return
        if (width <= 0 || height <= 0) return

        var created: MediaCodec? = null
        try {
            val csd0Annexb = maybeAvccToAnnexb(localCsd0)
            val csd1Annexb = maybeAvccToAnnexb(localCsd1)

            val format = MediaFormat.createVideoFormat("video/avc", width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0Annexb))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1Annexb))

            val dec = MediaCodec.createDecoderByType("video/avc")
            created = dec
            dec.configure(format, s, null, 0)
            dec.start()
            decoder = dec
            ShowerLog.d(TAG, "MediaCodec decoder initialized for ${width}x${height}")
        } catch (e: Exception) {
            ShowerLog.e(TAG, "Failed to init decoder", e)
            if (decoder !== created) runCatching { created?.release() }
            releaseDecoderLocked()
        }
    }
}
