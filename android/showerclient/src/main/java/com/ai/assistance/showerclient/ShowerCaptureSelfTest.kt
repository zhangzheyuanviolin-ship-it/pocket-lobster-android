package com.ai.assistance.showerclient

import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Binder
import android.os.Parcel
import com.ai.assistance.shower.IShowerVideoSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Headless synthetic codec test: no display creation, UI, network, accounts or app input. */
object ShowerCaptureSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        runBlocking {
            verifyTransport()
            val width = args.getOrNull(0)?.toInt() ?: 320
            val height = args.getOrNull(1)?.toInt() ?: 240
            val encoder = MediaCodec.createEncoderByType("video/avc")
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val capture = ShowerFrameCapture(width, height)
            val replay = mutableListOf<ByteArray>()
            try {
                for (frame in 0 until 8) {
                    val input = encoder.dequeueInputBuffer(1_000_000)
                    check(input >= 0) { "Encoder input unavailable" }
                    val image = checkNotNull(encoder.getInputImage(input))
                    val values = if (frame % 2 == 0) intArrayOf(81, 90, 240) else intArrayOf(41, 240, 110)
                    image.planes.forEachIndexed { p, plane ->
                        val w = if (p == 0) width else width / 2
                        val h = if (p == 0) height else height / 2
                        val buffer = plane.buffer
                        for (y in 0 until h) for (x in 0 until w) {
                            buffer.put(buffer.position() + y * plane.rowStride + x * plane.pixelStride, values[p].toByte())
                        }
                    }
                    encoder.queueInputBuffer(input, 0, width * height * 3 / 2, frame * 1_000_000L, 0)
                    val info = MediaCodec.BufferInfo()
                    var delivered = false
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (!delivered && System.nanoTime() < deadline) {
                        val out = encoder.dequeueOutputBuffer(info, 50_000)
                        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            for (key in listOf("csd-0", "csd-1")) {
                                encoder.outputFormat.getByteBuffer(key)?.duplicate()?.let { data ->
                                    val packet = ByteArray(data.remaining()).also { data.get(it) }
                                    replay += packet; capture.onFrame(packet)
                                }
                            }
                        } else if (out >= 0) {
                            val data = encoder.getOutputBuffer(out)!!.duplicate()
                            data.position(info.offset); data.limit(info.offset + info.size)
                            val packet = ByteArray(info.size).also { data.get(it) }
                            replay += packet; capture.onFrame(packet)
                            delivered = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            encoder.releaseOutputBuffer(out, false)
                        }
                    }
                    check(delivered) { "Encoder output timed out" }
                    verify(capture, blue = frame % 2 != 0)
                }
                repeat(3) { delay(500); verify(capture, blue = true) }
                capture.close()
                val resumed = ShowerFrameCapture(width, height)
                try {
                    replay.forEach(resumed::onFrame)
                    verify(resumed, blue = true)
                    println("{\"ok\":true,\"width\":$width,\"height\":$height,\"changingFrames\":8,\"staticCaptures\":3,\"decoderRecreated\":true,\"diagnostics\":\"${resumed.diagnostics()}\"}")
                } finally { resumed.close() }
            } finally {
                capture.close()
                encoder.stop(); encoder.release()
            }
        }
    }

    private fun verifyTransport() {
        var received: ByteArray? = null
        val sink = object : IShowerVideoSink.Stub() {
            override fun onVideoFrame(data: ByteArray) { received = data }
        }
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                sink.transact(code, data, reply, flags)
        }
        val proxy = IShowerVideoSink.Stub.asInterface(remote)
        for (size in listOf(65536, 65537, 2 * 1024 * 1024 + 17)) {
            val sent = ByteArray(size) { (it * 31).toByte() }
            proxy.onVideoFrame(sent)
            check(received?.contentEquals(sent) == true) { "Video chunk reassembly failed for $size bytes" }
        }
        println("{\"transportBoundaryTests\":3,\"ok\":true}")
    }

    private suspend fun verify(capture: ShowerFrameCapture, blue: Boolean) {
        val png = checkNotNull(capture.capturePng()) { "No decoded observation: ${capture.diagnostics()}" }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        try {
            for ((x, y) in listOf(0 to 0, bitmap.width / 2 to bitmap.height / 2, bitmap.width - 1 to bitmap.height - 1)) {
                val color = bitmap.getPixel(x, y)
                check(if (blue) Color.blue(color) > 200 && Color.red(color) < 40
                    else Color.red(color) > 200 && Color.blue(color) < 40) {
                    "Stale/wrong decoded pixels: blue=$blue actual=${Integer.toHexString(color)} ${capture.diagnostics()}"
                }
            }
        } finally { bitmap.recycle() }
    }
}
