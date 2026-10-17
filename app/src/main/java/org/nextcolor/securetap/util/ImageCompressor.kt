package org.nextcolor.securetap.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageCompressor {
    /** ~120 KB takes roughly 10–20 s over NFC; keep pictures small. */
    const val TARGET_BYTES = 120 * 1024
    private const val START_MAX_DIM = 1280
    private const val MIN_DIM = 240

    /**
     * Re-encodes the picture as a JPEG under [TARGET_BYTES]. Re-encoding also strips EXIF
     * metadata such as GPS location; ImageDecoder applies the EXIF rotation first.
     */
    fun compress(context: Context, uri: Uri): ByteArray {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        var bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1.0, START_MAX_DIM.toDouble() / max(w, h))
            decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }
        if (bitmap.hasAlpha()) bitmap = flattenOnWhite(bitmap)

        while (true) {
            for (quality in intArrayOf(85, 75, 65, 55, 45)) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= TARGET_BYTES) return out.toByteArray()
            }
            val w = (bitmap.width * 0.75).roundToInt()
            val h = (bitmap.height * 0.75).roundToInt()
            if (max(w, h) < MIN_DIM) throw IllegalArgumentException("Couldn't shrink the picture enough")
            bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
    }

    /** Decodes received bytes for display, downsampled to keep memory bounded. */
    fun decodeForDisplay(bytes: ByteArray, maxDim: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }
}
