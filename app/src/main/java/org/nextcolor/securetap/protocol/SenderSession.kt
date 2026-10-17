package org.nextcolor.securetap.protocol

import org.nextcolor.securetap.crypto.SessionCrypto
import java.security.SecureRandom

/**
 * Card-side state machine. One instance per armed payload; it answers APDUs from the
 * receiver and is single-use: after FINISH the plaintext is wiped.
 */
class SenderSession(
    plaintext: ByteArray,
    private val events: Events,
    private val random: SecureRandom = SecureRandom(),
) {
    interface Events {
        fun onReceiverConnected(sas: String)
        fun onProgress(sent: Int, total: Int)
        fun onFinished()
    }

    private var plaintext: ByteArray? = plaintext
    private val sessionId = ByteArray(Protocol.SESSION_ID_SIZE).also(random::nextBytes)
    private val keyPair = SessionCrypto.generateKeyPair(random)
    private val senderPub = SessionCrypto.encodePublicKey(keyPair.public)
    private var readerPub: ByteArray? = null
    private var blob: ByteArray? = null
    private var selected = false

    var finished = false
        private set

    @Synchronized
    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return Protocol.SW_WRONG_LENGTH
        val data = commandData(apdu) ?: return Protocol.SW_WRONG_LENGTH
        val ins = apdu[1]

        if (apdu[0] == 0x00.toByte() && ins == Protocol.INS_SELECT) {
            if (!data.contentEquals(Protocol.AID)) return Protocol.SW_NOT_READY
            val pt = plaintext ?: return Protocol.SW_NOT_READY
            selected = true
            return byteArrayOf(Protocol.VERSION) + senderPub + sessionId + Protocol.intBytes(pt.size) + Protocol.SW_OK
        }
        if (apdu[0] != Protocol.CLA) return Protocol.SW_CLA_UNSUPPORTED
        if (!selected || finished) return Protocol.SW_BAD_STATE

        return when (ins) {
            Protocol.INS_KEY -> keyExchange(data)
            Protocol.INS_READ -> read(data)
            Protocol.INS_FINISH -> finish()
            else -> Protocol.SW_INS_UNSUPPORTED
        }
    }

    /** Called when the NFC link drops; the next tap has to SELECT again. */
    @Synchronized
    fun onDeactivated() {
        selected = false
    }

    @Synchronized
    fun wipe() {
        plaintext?.fill(0)
        plaintext = null
        blob = null
    }

    private fun keyExchange(data: ByteArray): ByteArray {
        if (data.size != SessionCrypto.PUBLIC_KEY_SIZE) return Protocol.SW_WRONG_LENGTH
        val existing = blob
        if (existing != null && readerPub.contentEquals(data)) {
            // Same receiver reconnecting: hand back the same ciphertext so it can resume.
            return Protocol.intBytes(existing.size) + Protocol.SW_OK
        }
        val pt = plaintext ?: return Protocol.SW_BAD_STATE
        val keys = try {
            SessionCrypto.deriveKeys(keyPair.private, data, senderPub, data, sessionId)
        } catch (e: Exception) {
            return Protocol.SW_BAD_DATA
        }
        val encrypted = SessionCrypto.encrypt(keys, pt, random)
        keys.aesKey.fill(0)
        readerPub = data.copyOf()
        blob = encrypted
        events.onReceiverConnected(keys.sas)
        return Protocol.intBytes(encrypted.size) + Protocol.SW_OK
    }

    private fun read(data: ByteArray): ByteArray {
        val b = blob ?: return Protocol.SW_BAD_STATE
        if (data.size != 6) return Protocol.SW_WRONG_LENGTH
        val offset = Protocol.readInt(data, 0)
        val len = Protocol.readShort(data, 4)
        if (offset < 0 || offset > b.size || len == 0) return Protocol.SW_BAD_DATA
        val end = minOf(b.size, offset + len)
        events.onProgress(end, b.size)
        return b.copyOfRange(offset, end) + Protocol.SW_OK
    }

    private fun finish(): ByteArray {
        if (blob == null) return Protocol.SW_BAD_STATE
        finished = true
        wipe()
        events.onFinished()
        return Protocol.SW_OK
    }

    /** Extracts the command data field from a short APDU, or null if malformed. */
    private fun commandData(apdu: ByteArray): ByteArray? {
        if (apdu.size <= 5) return ByteArray(0) // case 1 or case 2 (Le only)
        val lc = apdu[4].toInt() and 0xFF
        if (lc == 0 || apdu.size < 5 + lc) return null
        return apdu.copyOfRange(5, 5 + lc)
    }
}
