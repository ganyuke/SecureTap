package org.nextcolor.securetap.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException

class TransferTest {
    private companion object {
        const val NAME = "Test phone"
    }

    private class Recorder : SenderSession.Events {
        var sas: String? = null
        var finished = false
        var receiverName: String? = null
        override fun onReceiverConnected(sas: String) { this.sas = sas }
        override fun onProgress(sent: Int, total: Int) {}
        override fun onFinished(receiverName: String) { finished = true; this.receiverName = receiverName }
    }

    private fun transfer(payload: Payload): Pair<Payload, Boolean> {
        val events = Recorder()
        val sender = SenderSession(payload.encode(), events)
        // Like card emulation on a real phone: responses longer than a short APDU get cut off.
        val hce = ApduTransport { apdu -> sender.process(apdu).let { if (it.size > 256) it.copyOf(256) else it } }
        val result = ReceiverSession().run(hce, NAME)
        assertEquals(events.sas, result.sas)
        return result.payload to events.finished
    }

    @Test
    fun textRoundTrip() {
        val (got, finished) = transfer(Payload.Text("hello, wörld 👋"))
        assertEquals(Payload.Text("hello, wörld 👋"), got)
        assertTrue(finished)
    }

    @Test
    fun secretAndLinkRoundTrip() {
        assertEquals(Payload.Secret("hunter2!"), transfer(Payload.Secret("hunter2!")).first)
        assertEquals(Payload.Link("https://example.com/a?b=c"), transfer(Payload.Link("https://example.com/a?b=c")).first)
    }

    @Test
    fun imageRoundTrip() {
        val bytes = ByteArray(150_000) { (it * 31).toByte() }
        val got = transfer(Payload.Image("image/jpeg", bytes)).first as Payload.Image
        assertEquals("image/jpeg", got.mimeType)
        assertArrayEquals(bytes, got.bytes)
    }

    @Test
    fun fileRoundTrip() {
        val bytes = ByteArray(40_000) { (it * 7).toByte() }
        val got = transfer(Payload.File("notes.pdf", bytes)).first as Payload.File
        assertEquals("notes.pdf", got.name)
        assertArrayEquals(bytes, got.bytes)
    }

    @Test
    fun payloadAboveConfirmSizeStillSends() {
        val bytes = ByteArray(Payload.CONFIRM_SIZE + 10_000) { it.toByte() }
        assertArrayEquals(bytes, (transfer(Payload.File("big.bin", bytes)).first as Payload.File).bytes)
    }

    @Test
    fun payloadAboveHardLimitIsRefused() {
        try {
            Payload.File("huge.bin", ByteArray(Payload.MAX_ENCODED_SIZE)).encode()
            fail("oversized payload was encoded")
        } catch (_: ProtocolException) {}
    }

    @Test
    fun receivedFileNamesCantActAsPaths() {
        assertEquals("evil.sh", Payload.safeName("../../evil.sh"))
        assertEquals("etcpasswd", Payload.safeName("/etc/passwd"))
        assertEquals("file", Payload.safeName("..."))
        assertEquals("invoicefdp.exe", Payload.safeName("invoice\u202Efdp.exe"))
    }

    @Test
    fun longFileNamesKeepTheirExtension() {
        val name = Payload.fitName("ä".repeat(300) + ".tar.gz")
        assertTrue(name.endsWith(".gz"))
        assertTrue(name.toByteArray().size <= 255)
    }

    @Test
    fun resumesAfterLinkDrops() {
        val bytes = ByteArray(20_000) { it.toByte() }
        val sender = SenderSession(Payload.Image("image/png", bytes).encode(), Recorder())
        val receiver = ReceiverSession()
        var reads = 0
        val flaky = ApduTransport { apdu ->
            if (apdu[1] == Protocol.INS_READ && ++reads == 30) {
                sender.onDeactivated()
                throw IOException("Tag was lost")
            }
            sender.process(apdu)
        }
        try {
            receiver.run(flaky, NAME)
            fail("expected link loss")
        } catch (_: IOException) {}

        var resumedFrom = -1
        val result = receiver.run(sender::process, NAME, onProgress = { r, _ -> if (resumedFrom < 0) resumedFrom = r })
        assertTrue("should resume, not restart", resumedFrom > 29 * ReceiverSession.SHORT_CHUNK)
        assertArrayEquals(bytes, (result.payload as Payload.Image).bytes)
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val sender = SenderSession(Payload.Secret("top secret").encode(), Recorder())
        val evil = ApduTransport { apdu ->
            val resp = sender.process(apdu)
            if (apdu[1] == Protocol.INS_READ) resp.also { it[3] = (it[3].toInt() xor 1).toByte() } else resp
        }
        try {
            ReceiverSession().run(evil, NAME)
            fail("tampering not detected")
        } catch (_: GeneralSecurityException) {}
    }

    @Test
    fun senderIsSingleUse() {
        val sender = SenderSession(Payload.Text("once").encode(), Recorder())
        ReceiverSession().run(sender::process, NAME)
        try {
            ReceiverSession().run(sender::process, NAME)
            fail("second receiver got the payload")
        } catch (_: ProtocolException) {}
    }

    @Test
    fun relayAttackerProducesDifferentCodes() {
        // An attacker in the middle runs one session with each side; codes can't match.
        val events = Recorder()
        val sender = SenderSession(Payload.Secret("pw").encode(), events)
        val attackerAsReader = ReceiverSession().run(sender::process, NAME)
        val attackerAsSender = SenderSession(attackerAsReader.payload.encode(), Recorder())
        val victim = ReceiverSession().run(attackerAsSender::process, NAME)
        assertNotEquals(events.sas, victim.sas)
    }

    @Test
    fun senderLearnsReceiverName() {
        val events = Recorder()
        val sender = SenderSession(Payload.Text("hi").encode(), events)
        ReceiverSession().run(sender::process, "Pixel 8")
        assertEquals("Pixel 8", events.receiverName)
    }

    @Test
    fun longReceiverNameIsShortenedWithoutBreakingCharacters() {
        val events = Recorder()
        val sender = SenderSession(Payload.Text("hi").encode(), events)
        ReceiverSession().run(sender::process, "📱".repeat(40))
        assertEquals("📱".repeat(Protocol.MAX_RECEIVER_NAME_BYTES / 4), events.receiverName)
    }

    @Test
    fun finishWithoutNameIsRejected() {
        val events = Recorder()
        val sender = SenderSession(Payload.Text("hi").encode(), events)
        val stripName = ApduTransport { apdu ->
            sender.process(if (apdu[1] == Protocol.INS_FINISH) Protocol.command(Protocol.INS_FINISH) else apdu)
        }
        try {
            ReceiverSession().run(stripName, NAME)
            fail("finish without a name was accepted")
        } catch (_: ProtocolException) {}
        assertTrue(!events.finished)
    }

    @Test
    fun tamperedReceiverNameIsRejected() {
        val events = Recorder()
        val sender = SenderSession(Payload.Text("hi").encode(), events)
        val evil = ApduTransport { apdu ->
            if (apdu[1] == Protocol.INS_FINISH) apdu[apdu.size - 3] = (apdu[apdu.size - 3].toInt() xor 1).toByte()
            sender.process(apdu)
        }
        try {
            ReceiverSession().run(evil, "Pixel 8")
            fail("tampered name was accepted")
        } catch (_: ProtocolException) {}
        assertTrue(!events.finished)
    }

    @Test
    fun rejectsInvalidCurvePoint() {
        val sender = SenderSession(Payload.Text("x").encode(), Recorder())
        sender.process(Protocol.selectApdu())
        val bogus = ByteArray(65).also { it[0] = 4; it[64] = 1 }
        assertArrayEquals(Protocol.SW_BAD_DATA, sender.process(Protocol.command(Protocol.INS_KEY, bogus)))
    }

    @Test
    fun readBeforeKeyExchangeIsRefused() {
        val sender = SenderSession(Payload.Text("x").encode(), Recorder())
        sender.process(Protocol.selectApdu())
        val read = Protocol.command(Protocol.INS_READ, Protocol.intBytes(0) + Protocol.sw(100))
        assertArrayEquals(Protocol.SW_BAD_STATE, sender.process(read))
    }
}
