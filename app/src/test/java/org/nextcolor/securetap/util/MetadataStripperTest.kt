package org.nextcolor.securetap.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MetadataStripperTest {
    private fun bytes(vararg parts: Any): ByteArray = ByteArrayOutputStream().apply {
        for (p in parts) when (p) {
            is Int -> write(p)
            is String -> write(p.toByteArray(Charsets.ISO_8859_1))
            is ByteArray -> write(p)
        }
    }.toByteArray()

    private fun jpegSegment(marker: Int, payload: String) =
        bytes(0xFF, marker, (payload.length + 2) shr 8, (payload.length + 2) and 0xFF, payload)

    private fun ByteArray.contains(s: String) = String(this, Charsets.ISO_8859_1).contains(s)

    @Test fun jpegLosesExifXmpAndCommentsButKeepsColourAndPixels() {
        val scan = bytes(0xFF, 0xDA, 0x00, 0x02, "PIXELS", 0xFF, 0xD9)
        val jpeg = bytes(
            0xFF, 0xD8,
            jpegSegment(0xE0, "JFIF\u0000"),
            jpegSegment(0xE1, "Exif\u0000\u0000GPS-SECRET"),
            jpegSegment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000XMP-SECRET"),
            jpegSegment(0xE2, "ICC_PROFILE\u0000PROFILE"),
            jpegSegment(0xED, "Photoshop 3.0\u0000IPTC-SECRET"),
            jpegSegment(0xFE, "COMMENT-SECRET"),
            jpegSegment(0xDB, "QUANT"),
            scan,
        )
        val out = MetadataStripper.strip(jpeg)
        for (secret in listOf("GPS-SECRET", "XMP-SECRET", "IPTC-SECRET", "COMMENT-SECRET")) assertFalse(secret, out.contains(secret))
        for (kept in listOf("JFIF", "ICC_PROFILE", "QUANT", "PIXELS")) assertTrue(kept, out.contains(kept))
        assertArrayEquals(scan, out.copyOfRange(out.size - scan.size, out.size))
    }

    private fun pngChunk(type: String, data: String) =
        bytes(0, 0, 0, data.length, type, data, 0, 0, 0, 0)

    @Test fun pngLosesTextAndExifChunks() {
        val png = bytes(
            0x89, "PNG\r\n", 0x1A, "\n",
            pngChunk("IHDR", "HEADER"), pngChunk("iCCP", "PROFILE"),
            pngChunk("eXIf", "GPS-SECRET"), pngChunk("tEXt", "TEXT-SECRET"), pngChunk("iTXt", "XMP-SECRET"),
            pngChunk("IDAT", "PIXELS"), pngChunk("IEND", ""),
        )
        val out = MetadataStripper.strip(png)
        for (secret in listOf("GPS-SECRET", "TEXT-SECRET", "XMP-SECRET")) assertFalse(secret, out.contains(secret))
        for (kept in listOf("IHDR", "PROFILE", "PIXELS", "IEND")) assertTrue(kept, out.contains(kept))
    }

    private fun webpChunk(type: String, data: String) =
        bytes(type, data.length, 0, 0, 0, data, *(if (data.length % 2 == 1) arrayOf<Any>(0) else emptyArray()))

    @Test fun webpLosesExifAndXmpAndClearsTheirFlags() {
        val body = bytes(
            "WEBP",
            webpChunk("VP8X", "\u000C123456789"), // flags 0x0C: has EXIF, has XMP
            webpChunk("VP8 ", "PIXELS"),
            webpChunk("EXIF", "GPS-SECRET"),
            webpChunk("XMP ", "XMP-SECRET1"),
        )
        val webp = bytes("RIFF", body.size, 0, 0, 0, body)
        val out = MetadataStripper.strip(webp)
        for (secret in listOf("GPS-SECRET", "XMP-SECRET")) assertFalse(secret, out.contains(secret))
        assertTrue(out.contains("PIXELS"))
        assertTrue(out[20].toInt() and 0x0C == 0)
        val riffSize = (out[4].toInt() and 0xFF) or ((out[5].toInt() and 0xFF) shl 8)
        assertTrue(riffSize == out.size - 8)
    }

    @Test fun otherFilesAreUntouched() {
        val gif = "GIF89a-whatever".toByteArray()
        assertArrayEquals(gif, MetadataStripper.strip(gif))
    }
}
