package org.nextcolor.securetap.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageCompressor {
    /** ~120 KB takes roughly 10–20 s over NFC; keep pictures small. */
    const val TARGET_BYTES = 120 * 1024
    private const val START_MAX_DIM = 1280
    private const val MIN_DIM = 240

    /**
     * Re-encodes the picture as a JPEG under [TARGET_BYTES]. Re-encoding drops the EXIF metadata,
     * GPS location included; see [copyMetadata] to put it back. ImageDecoder applies the EXIF
     * rotation first.
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

    enum class Location { Present, Removed, None }

    /**
     * Where the picture says it was taken. Android removes the location by zeroing the GPS values
     * in place, so the GPS tags are still there with nothing usable in them: that's [Location.Removed].
     * A picture taken without location has no GPS tags at all.
     */
    fun location(exif: ExifInterface): Location = when {
        exif.latLong != null -> Location.Present
        exif.hasAttribute(ExifInterface.TAG_GPS_LATITUDE) -> Location.Removed
        else -> Location.None
    }

    fun location(context: Context, uri: Uri): Location =
        runCatching { context.contentResolver.openInputStream(uri)?.use { location(ExifInterface(it)) } }.getOrNull() ?: Location.None

    fun location(bytes: ByteArray): Location = runCatching { location(ExifInterface(bytes.inputStream())) }.getOrDefault(Location.None)

    /**
     * Copies date, camera and location tags from the picture at [source] into a JPEG made by
     * [compress]. Orientation isn't copied because the pixels are already upright.
     */
    fun copyMetadata(context: Context, source: Uri, jpeg: ByteArray): ByteArray {
        val from = context.contentResolver.openInputStream(source)?.use { ExifInterface(it) } ?: return jpeg
        // ExifInterface can only rewrite a file, not a byte array.
        val tmp = File.createTempFile("exif", ".jpg", context.cacheDir)
        try {
            tmp.writeBytes(jpeg)
            val to = ExifInterface(tmp)
            for (tag in KEPT_TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
            to.saveAttributes()
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    /**
     * The file with its metadata removed by [MetadataStripper], pixels untouched. The orientation
     * is put back so the picture still shows upright.
     */
    fun withoutMetadata(context: Context, bytes: ByteArray): ByteArray {
        val stripped = MetadataStripper.strip(bytes)
        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return stripped
        val tmp = File.createTempFile("strip", null, context.cacheDir)
        try {
            tmp.writeBytes(stripped)
            ExifInterface(tmp).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()) }.saveAttributes()
            return tmp.readBytes()
        } catch (e: Exception) {
            return stripped
        } finally {
            tmp.delete()
        }
    }

    private val KEPT_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
    )

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
