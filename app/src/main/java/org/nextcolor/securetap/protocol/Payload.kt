package org.nextcolor.securetap.protocol

/**
 * What gets transferred. Encoded plaintext: kind(1) || labelLen(1) || label || data.
 * For images the label is the MIME type, for files the file name.
 */
sealed class Payload {
    data class Text(val text: String) : Payload()
    data class Link(val url: String) : Payload()
    data class Secret(val secret: String) : Payload()
    class Image(val mimeType: String, val bytes: ByteArray) : Payload()
    class File(val name: String, val bytes: ByteArray) : Payload()

    fun encode(): ByteArray {
        val (kind, label, data) = when (this) {
            is Text -> Triple(KIND_TEXT, "", text.toByteArray())
            is Link -> Triple(KIND_LINK, "", url.toByteArray())
            is Secret -> Triple(KIND_SECRET, "", secret.toByteArray())
            is Image -> Triple(KIND_IMAGE, mimeType, bytes)
            is File -> Triple(KIND_FILE, fitName(name), bytes)
        }
        val labelBytes = label.toByteArray()
        require(labelBytes.size <= 255)
        val out = byteArrayOf(kind, labelBytes.size.toByte()) + labelBytes + data
        if (out.size > MAX_ENCODED_SIZE) throw ProtocolException("Payload too large (${out.size / 1024} KB)")
        return out
    }

    companion object {
        /**
         * NFC moves roughly 5–20 KB/s. Past [SLOW_SIZE] the sender sees how long it'll take, past
         * [CONFIRM_SIZE] they have to confirm, and this is the hard limit (minutes of holding still).
         */
        const val MAX_ENCODED_SIZE = 4 * 1024 * 1024
        const val SLOW_SIZE = 64 * 1024
        const val CONFIRM_SIZE = 512 * 1024

        private const val KIND_TEXT: Byte = 1
        private const val KIND_LINK: Byte = 2
        private const val KIND_SECRET: Byte = 3
        private const val KIND_IMAGE: Byte = 4
        private const val KIND_FILE: Byte = 5
        /** Image types the receiver will decode; anything else has to go as a file. */
        val ALLOWED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        fun decode(b: ByteArray): Payload {
            if (b.size < 2) throw ProtocolException("Payload truncated")
            val labelLen = b[1].toInt() and 0xFF
            if (b.size < 2 + labelLen) throw ProtocolException("Payload truncated")
            val label = String(b, 2, labelLen, Charsets.UTF_8)
            val data = b.copyOfRange(2 + labelLen, b.size)
            return when (b[0]) {
                KIND_TEXT -> Text(String(data, Charsets.UTF_8))
                KIND_LINK -> Link(String(data, Charsets.UTF_8))
                KIND_SECRET -> Secret(String(data, Charsets.UTF_8))
                KIND_IMAGE -> {
                    if (label !in ALLOWED_IMAGE_TYPES) throw ProtocolException("Unsupported image type")
                    Image(label, data)
                }
                KIND_FILE -> File(safeName(label), data)
                else -> throw ProtocolException("Unknown payload kind")
            }
        }

        /** Shortens a file name to fit the label, keeping the extension and whole characters. */
        internal fun fitName(name: String): String {
            val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 16 } ?: name.length
            var stem = name.substring(0, dot)
            val ext = name.substring(dot)
            while (stem.isNotEmpty() && (stem + ext).toByteArray().size > 255) {
                stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
            }
            return stem + ext
        }

        /** The sender picks the name, so drop anything that could act as a path or hide the extension. */
        internal fun safeName(name: String): String {
            val cleaned = name.filterNot { it == '/' || it == '\\' || it.isISOControl() || it == '\u202E' }
                .trim().trimStart('.')
            return cleaned.ifBlank { "file" }
        }
    }
}
