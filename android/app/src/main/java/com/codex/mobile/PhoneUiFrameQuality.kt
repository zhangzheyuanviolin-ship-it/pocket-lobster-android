package com.codex.mobile

import android.graphics.BitmapFactory

internal data class PhoneUiFrameQuality(
    val usable: Boolean,
    val sampled: Int,
    val nonBlackPermille: Int,
    val averageLuma: Int,
    val lumaRange: Int,
) {
    companion object {
        fun inspect(png: ByteArray): PhoneUiFrameQuality {
            val bitmap = BitmapFactory.decodeByteArray(
                png,
                0,
                png.size,
                BitmapFactory.Options().apply { inSampleSize = 8 },
            ) ?: return PhoneUiFrameQuality(false, 0, 0, 0, 0)
            return try {
                val left = (bitmap.width * 0.04).toInt()
                val right = (bitmap.width * 0.96).toInt().coerceAtLeast(left + 1)
                val top = (bitmap.height * 0.08).toInt()
                val bottom = (bitmap.height * 0.97).toInt().coerceAtLeast(top + 1)
                val pixels = IntArray((right - left) * (bottom - top))
                bitmap.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
                inspectPixels(pixels)
            } finally {
                bitmap.recycle()
            }
        }

        internal fun inspectPixels(pixels: IntArray): PhoneUiFrameQuality {
            if (pixels.isEmpty()) return PhoneUiFrameQuality(false, 0, 0, 0, 0)
            var nonBlack = 0
            var lumaSum = 0L
            var minLuma = 255
            var maxLuma = 0
            pixels.forEach { color ->
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                val luma = (red * 54 + green * 183 + blue * 19) shr 8
                if (maxOf(red, green, blue) >= 24) nonBlack++
                lumaSum += luma
                minLuma = minOf(minLuma, luma)
                maxLuma = maxOf(maxLuma, luma)
            }
            val permille = nonBlack * 1_000 / pixels.size
            val average = (lumaSum / pixels.size).toInt()
            // Reject only content-free black producer frames. Real dark pages still
            // contain enough text, icons, or controls to exceed this low threshold.
            val usable = permille >= 3 || average >= 18
            return PhoneUiFrameQuality(usable, pixels.size, permille, average, maxLuma - minLuma)
        }
    }
}
