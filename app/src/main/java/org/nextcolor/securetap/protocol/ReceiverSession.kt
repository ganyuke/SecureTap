package org.nextcolor.securetap.protocol

import org.nextcolor.securetap.crypto.SessionCrypto
import java.io.IOException
import java.security.SecureRandom

fun interface ApduTransport {
    @Throws(IOException::class)
    fun transceive(apdu: ByteArray): ByteArray
}

/**
 * Reader-side state machine. Keep one instance alive across taps: if the link drops
 * mid-transfer, calling [run] again on the next tap resumes from the last received byte.
 */
class ReceiverSession(private val random: SecureRandom = SecureRandom()) {
    class Result(val payload: Payload, val sas: String)

    private var keyPair = SessionCrypto.generateKeyPair(random)
    private var readerPub = SessionCrypto.encodePublicKey(keyPair.public)
    private var sessionId: ByteArray? = null
    private var keys: SessionCrypto.SessionKeys? = null
    private var buffer: ByteArray? = null
    private var received = 0

    /**
     * @param deviceName shown on the sending phone so its user can tell which phone got the payload
     * @throws IOException when the link drops (keep this session to resume)
     * @throws ProtocolException / GeneralSecurityException on a bad or tampered transfer
     */
    fun run(
        transport: ApduTransport,
        deviceName: String,
        onConnected: (sas: String) -> Unit = {},
        onProgress: (received: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val select = checkOk(transport.transceive(Protocol.selectApdu()), "Sender not ready", ProtocolException.Reason.SENDER_NOT_READY)
        if (select.size != 1 + SessionCrypto.PUBLIC_KEY_SIZE + Protocol.SESSION_ID_SIZE + 4) {
            throw ProtocolException("Unexpected handshake")
        }
        if (select[0] != Protocol.VERSION) throw ProtocolException("Unsupported version ${select[0]}", ProtocolException.Reason.INCOMPATIBLE)
        val senderPub = select.copyOfRange(1, 1 + SessionCrypto.PUBLIC_KEY_SIZE)
        val sid = select.copyOfRange(1 + SessionCrypto.PUBLIC_KEY_SIZE, select.size - 4)
        val plaintextLen = Protocol.readInt(select, select.size - 4)
        if (plaintextLen < 0 || plaintextLen > Payload.MAX_ENCODED_SIZE) throw ProtocolException("Payload too large")

        if (!sid.contentEquals(sessionId)) {
            // A different transfer than last time: start over with a fresh key pair.
            if (sessionId != null) {
                keyPair = SessionCrypto.generateKeyPair(random)
                readerPub = SessionCrypto.encodePublicKey(keyPair.public)
            }
            sessionId = sid
            buffer = null
            received = 0
            keys = SessionCrypto.deriveKeys(keyPair.private, senderPub, senderPub, readerPub, sid)
        }
        val k = keys!!
        onConnected(k.sas)

        val keyResp = checkOk(transport.transceive(Protocol.command(Protocol.INS_KEY, readerPub)), "Key exchange rejected")
        if (keyResp.size != 4) throw ProtocolException("Unexpected key response")
        val total = Protocol.readInt(keyResp, 0)
        if (total != plaintextLen + SessionCrypto.overhead()) throw ProtocolException("Length mismatch")

        val buf = buffer?.takeIf { it.size == total } ?: ByteArray(total).also { buffer = it; received = 0 }
        while (received < total) {
            val want = minOf(SHORT_CHUNK, total - received)
            val cmd = Protocol.command(Protocol.INS_READ, Protocol.intBytes(received) + Protocol.sw(want))
            val data = checkOk(transport.transceive(cmd), "Read failed")
            if (data.isEmpty() || data.size > want) throw ProtocolException("Bad chunk")
            System.arraycopy(data, 0, buf, received, data.size)
            received += data.size
            onProgress(received, total)
        }

        val plaintext = SessionCrypto.decrypt(k, buf) // throws if tampered
        try {
            val name = SessionCrypto.encrypt(k, truncateUtf8(deviceName), random, Protocol.RECEIVER_NAME_LABEL)
            checkOk(transport.transceive(Protocol.command(Protocol.INS_FINISH, name)), "Finish rejected")
        } catch (_: IOException) {
            // We already have the verified data; the sender just won't see the confirmation.
        }
        k.aesKey.fill(0)
        buf.fill(0)
        buffer = null
        keys = null
        sessionId = null
        val payload = Payload.decode(plaintext)
        plaintext.fill(0)
        return Result(payload, k.sas)
    }

    private fun checkOk(
        resp: ByteArray,
        error: String,
        reason: ProtocolException.Reason = ProtocolException.Reason.INVALID,
    ): ByteArray {
        if (resp.size < 2) throw ProtocolException("Short response")
        val sw1 = resp[resp.size - 2]
        val sw2 = resp[resp.size - 1]
        if (sw1 != 0x90.toByte() || sw2 != 0x00.toByte()) throw ProtocolException(error, reason)
        return resp.copyOfRange(0, resp.size - 2)
    }

    /** Shortens [name] to fit [Protocol.MAX_RECEIVER_NAME_BYTES], dropping whole characters. */
    private fun truncateUtf8(name: String): ByteArray {
        var s = name.trim()
        while (s.toByteArray(Charsets.UTF_8).size > Protocol.MAX_RECEIVER_NAME_BYTES) {
            s = s.substring(0, s.offsetByCodePoints(s.length, -1))
        }
        return s.toByteArray(Charsets.UTF_8)
    }

    companion object {
        /**
         * Bytes per READ. Fits a short APDU response (256 bytes including the status word).
         * Card emulation can't reliably send longer responses, even when the reader's NFC
         * controller supports extended-length APDUs: they arrive truncated.
         */
        const val SHORT_CHUNK = 254
    }
}
