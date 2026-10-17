package org.nextcolor.securetap.util

import java.io.ByteArrayOutputStream

/**
 * Removes EXIF, XMP, IPTC and text metadata from JPEG, PNG and WebP files without re-encoding
 * the picture. Colour profiles stay so colours don't shift. Anything else is returned as is.
 */
object MetadataStripper {
    fun strip(bytes: ByteArray): ByteArray = when {
        bytes.startsWith(0xFF, 0xD8) -> jpeg(bytes)
        bytes.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> png(bytes)
        bytes.size >= 12 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP" -> webp(bytes)
        else -> bytes
    }

    private fun jpeg(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var i = 2
        while (i + 4 <= b.size && b.u8(i) == 0xFF) {
            val marker = b.u8(i + 1)
            // Start of scan: the compressed picture follows, copy the rest untouched.
            if (marker == 0xDA) break
            val end = i + 2 + b.u16be(i + 2)
            if (end > b.size) break
            val keep = when (marker) {
                0xE0 -> true // JFIF
                0xE2 -> b.ascii(i + 4, 12) == "ICC_PROFILE\u0000"
                0xEE -> true // Adobe colour transform, needed to decode some files
                in 0xE1..0xEF, 0xFE -> false // EXIF, XMP, IPTC, comments…
                else -> true
            }
            if (keep) out.write(b, i, end - i)
            i = end
        }
        out.write(b, i, b.size - i)
        return out.toByteArray()
    }

    private val PNG_DROPPED = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")

    private fun png(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var i = 8
        while (i + 12 <= b.size) {
            val end = i + 12 + b.u32be(i).toInt()
            if (end > b.size || end < i) break
            if (b.ascii(i + 4, 4) !in PNG_DROPPED) out.write(b, i, end - i)
            i = end
        }
        out.write(b, i, b.size - i)
        return out.toByteArray()
    }

    private fun webp(b: ByteArray): ByteArray {
        val body = ByteArrayOutputStream(b.size)
        var i = 12
        while (i + 8 <= b.size) {
            val size = b.u32le(i + 4).toInt()
            val end = i + 8 + size + (size and 1)
            if (end > b.size || end < i) break
            when (b.ascii(i, 4)) {
                "EXIF", "XMP " -> {}
                "VP8X" -> {
                    val chunk = b.copyOfRange(i, end)
                    // Clear the "has EXIF" and "has XMP" flags.
                    chunk[8] = (chunk[8].toInt() and 0x08.inv() and 0x04.inv()).toByte()
                    body.write(chunk)
                }
                else -> body.write(b, i, end - i)
            }
            i = end
        }
        body.write(b, i, b.size - i)
        val out = ByteArrayOutputStream(body.size() + 12)
        out.write(b, 0, 4)
        val riffSize = body.size() + 4
        for (shift in intArrayOf(0, 8, 16, 24)) out.write(riffSize ushr shift and 0xFF)
        out.write(b, 8, 4)
        body.writeTo(out)
        return out.toByteArray()
    }

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16be(i: Int) = u8(i) shl 8 or u8(i + 1)
    private fun ByteArray.u32be(i: Int) = (u16be(i).toLong() shl 16) or u16be(i + 2).toLong()
    private fun ByteArray.u32le(i: Int) = u8(i).toLong() or (u8(i + 1).toLong() shl 8) or
        (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)
    private fun ByteArray.ascii(i: Int, n: Int) = if (i + n > size) "" else String(this, i, n, Charsets.ISO_8859_1)
    private fun ByteArray.startsWith(vararg prefix: Int) = size >= prefix.size && prefix.indices.all { u8(it) == prefix[it] }
}
