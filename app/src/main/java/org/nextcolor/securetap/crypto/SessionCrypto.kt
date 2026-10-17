package org.nextcolor.securetap.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ephemeral ECDH (P-256) + HKDF-SHA256 + AES-256-GCM.
 *
 * Every transfer uses fresh key pairs on both phones, so there's forward secrecy: nothing
 * long-lived exists that could decrypt a captured transfer later.
 */
object SessionCrypto {
    const val PUBLIC_KEY_SIZE = 65 // uncompressed SEC1 point: 0x04 || X || Y
    private const val COORD_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private val DOMAIN = "SecureTap v1".toByteArray()

    // NIST P-256 curve constants, used to validate peer points before ECDH.
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val A = P - BigInteger.valueOf(3)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    class SessionKeys(val aesKey: ByteArray, val transcript: ByteArray, val sas: String)

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }

    fun encodePublicKey(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(0x04) + toFixed(w.affineX) + toFixed(w.affineY)
    }

    fun decodePublicKey(encoded: ByteArray): PublicKey {
        if (encoded.size != PUBLIC_KEY_SIZE || encoded[0] != 0x04.toByte()) {
            throw GeneralSecurityException("Malformed public key")
        }
        val x = BigInteger(1, encoded.copyOfRange(1, 1 + COORD_SIZE))
        val y = BigInteger(1, encoded.copyOfRange(1 + COORD_SIZE, PUBLIC_KEY_SIZE))
        if (x >= P || y >= P) throw GeneralSecurityException("Public key out of range")
        val lhs = y.modPow(BigInteger.valueOf(2), P)
        val rhs = (x.modPow(BigInteger.valueOf(3), P) + A * x + B).mod(P)
        if (lhs != rhs) throw GeneralSecurityException("Public key not on curve")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    /**
     * Derives the session key and a 6-digit short authentication string (SAS). Both phones
     * show the SAS under Details; if they match, nobody relayed or swapped keys in the middle.
     */
    fun deriveKeys(
        ownPrivate: PrivateKey,
        peerPublic: ByteArray,
        senderPublic: ByteArray,
        readerPublic: ByteArray,
        sessionId: ByteArray,
    ): SessionKeys {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(ownPrivate)
            doPhase(decodePublicKey(peerPublic), true)
            generateSecret()
        }
        val transcript = MessageDigest.getInstance("SHA-256").run {
            update(DOMAIN)
            update(senderPublic)
            update(readerPublic)
            update(sessionId)
            digest()
        }
        val prk = hmac(sessionId, shared)
        shared.fill(0)
        val aesKey = hkdfExpand(prk, "key".toByteArray() + transcript, 32)
        val sasBytes = hkdfExpand(prk, "sas".toByteArray() + transcript, 4)
        prk.fill(0)
        val sasNum = BigInteger(1, sasBytes).mod(BigInteger.valueOf(1_000_000)).toInt()
        return SessionKeys(aesKey, transcript, "%03d %03d".format(sasNum / 1000, sasNum % 1000))
    }

    /** Returns nonce || ciphertext || tag. */
    fun encrypt(keys: SessionKeys, plaintext: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.aesKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(keys.transcript)
        }
        return nonce + cipher.doFinal(plaintext)
    }

    /** Throws [javax.crypto.AEADBadTagException] if the data was tampered with. */
    fun decrypt(keys: SessionKeys, blob: ByteArray): ByteArray {
        if (blob.size < NONCE_SIZE + TAG_BITS / 8) throw GeneralSecurityException("Ciphertext too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keys.aesKey, "AES"),
                GCMParameterSpec(TAG_BITS, blob, 0, NONCE_SIZE),
            )
            updateAAD(keys.transcript)
        }
        return cipher.doFinal(blob, NONCE_SIZE, blob.size - NONCE_SIZE)
    }

    fun overhead(): Int = NONCE_SIZE + TAG_BITS / 8

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun toFixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == COORD_SIZE -> raw
            raw.size > COORD_SIZE -> raw.copyOfRange(raw.size - COORD_SIZE, raw.size)
            else -> ByteArray(COORD_SIZE - raw.size) + raw
        }
    }
}
