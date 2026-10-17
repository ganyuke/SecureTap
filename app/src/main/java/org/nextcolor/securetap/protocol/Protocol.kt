package org.nextcolor.securetap.protocol

/**
 * Wire protocol between the sender (phone emulating a card via HCE) and the receiver
 * (phone in NFC reader mode). All commands are short ISO 7816-4 APDUs.
 *
 *   SELECT AID          -> version(1) || senderPub(65) || sessionId(16) || plaintextLen(4)
 *   KEY  readerPub(65)  -> blobLen(4)                  (sender derives keys, encrypts payload)
 *   READ offset(4) len(2) -> blob[offset, offset+len)
 *   FINISH              -> (empty)                     (receiver verified the GCM tag)
 *
 * Re-sending KEY with the same reader key returns the same ciphertext, so a receiver whose
 * phone slipped away mid-transfer can tap again and continue READing from where it stopped.
 */
object Protocol {
    const val VERSION: Byte = 1
    val AID = byteArrayOf(0xF0.toByte(), 0x53, 0x45, 0x43, 0x54, 0x41, 0x50, 0x01)

    const val CLA: Byte = 0x80.toByte()
    const val INS_SELECT: Byte = 0xA4.toByte()
    const val INS_KEY: Byte = 0x10
    const val INS_READ: Byte = 0x20
    const val INS_FINISH: Byte = 0x30

    const val SESSION_ID_SIZE = 16

    val SW_OK = sw(0x9000)
    val SW_NOT_READY = sw(0x6A82)       // nothing armed to send
    val SW_BAD_STATE = sw(0x6985)       // command out of order
    val SW_BAD_DATA = sw(0x6A80)
    val SW_WRONG_LENGTH = sw(0x6700)
    val SW_INS_UNSUPPORTED = sw(0x6D00)
    val SW_CLA_UNSUPPORTED = sw(0x6E00)

    fun selectApdu(): ByteArray =
        byteArrayOf(0x00, INS_SELECT, 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    fun command(ins: Byte, data: ByteArray = ByteArray(0)): ByteArray {
        require(data.size <= 255)
        val header = byteArrayOf(CLA, ins, 0x00, 0x00)
        return if (data.isEmpty()) header + byteArrayOf(0x00)
        else header + byteArrayOf(data.size.toByte()) + data + byteArrayOf(0x00)
    }

    fun sw(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    fun readShort(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}

class ProtocolException(message: String, val reason: Reason = Reason.INVALID) : Exception(message) {
    enum class Reason { SENDER_NOT_READY, INCOMPATIBLE, INVALID }
}
