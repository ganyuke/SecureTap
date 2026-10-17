package org.nextcolor.securetap.protocol

/**
 * What gets transferred. Encoded plaintext: kind(1) || labelLen(1) || label || data.
 * For images the label is the MIME type.
 */
sealed class Payload {
    data class Text(val text: String) : Payload()
    data class Link(val url: String) : Payload()
    data class Secret(val secret: String) : Payload()
    class Image(val mimeType: String, val bytes: ByteArray) : Payload()

    fun encode(): ByteArray {
        val (kind, label, data) = when (this) {
            is Text -> Triple(KIND_TEXT, "", text.toByteArray())
            is Link -> Triple(KIND_LINK, "", url.toByteArray())
            is Secret -> Triple(KIND_SECRET, "", secret.toByteArray())
            is Image -> Triple(KIND_IMAGE, mimeType, bytes)
        }
        val labelBytes = label.toByteArray()
        require(labelBytes.size <= 255)
        val out = byteArrayOf(kind, labelBytes.size.toByte()) + labelBytes + data
        if (out.size > MAX_ENCODED_SIZE) throw ProtocolException("Payload too large (${out.size / 1024} KB)")
        return out
    }

    companion object {
        /** NFC moves roughly 5–20 KB/s, so anything larger means holding the phones still for a long time. */
        const val MAX_ENCODED_SIZE = 512 * 1024

        private const val KIND_TEXT: Byte = 1
        private const val KIND_LINK: Byte = 2
        private const val KIND_SECRET: Byte = 3
        private const val KIND_IMAGE: Byte = 4
        private val ALLOWED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

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
                else -> throw ProtocolException("Unknown payload kind")
            }
        }
    }
}
