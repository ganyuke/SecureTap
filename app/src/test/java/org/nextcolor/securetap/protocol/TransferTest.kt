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
    private class Recorder : SenderSession.Events {
        var sas: String? = null
        var finished = false
        override fun onReceiverConnected(sas: String) { this.sas = sas }
        override fun onProgress(sent: Int, total: Int) {}
        override fun onFinished() { finished = true }
    }

    private fun transfer(payload: Payload, chunk: Int = ReceiverSession.SHORT_CHUNK): Pair<Payload, Boolean> {
        val events = Recorder()
        val sender = SenderSession(payload.encode(), events)
        val result = ReceiverSession().run(sender::process, chunk)
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
    fun imageRoundTripWithLargeChunks() {
        val bytes = ByteArray(150_000) { (it * 31).toByte() }
        val got = transfer(Payload.Image("image/jpeg", bytes), chunk = 4096).first as Payload.Image
        assertEquals("image/jpeg", got.mimeType)
        assertArrayEquals(bytes, got.bytes)
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
            receiver.run(flaky)
            fail("expected link loss")
        } catch (_: IOException) {}

        var resumedFrom = -1
        val result = receiver.run(sender::process, onProgress = { r, _ -> if (resumedFrom < 0) resumedFrom = r })
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
            ReceiverSession().run(evil)
            fail("tampering not detected")
        } catch (_: GeneralSecurityException) {}
    }

    @Test
    fun senderIsSingleUse() {
        val sender = SenderSession(Payload.Text("once").encode(), Recorder())
        ReceiverSession().run(sender::process)
        try {
            ReceiverSession().run(sender::process)
            fail("second receiver got the payload")
        } catch (_: ProtocolException) {}
    }

    @Test
    fun relayAttackerProducesDifferentCodes() {
        // An attacker in the middle runs one session with each side; codes can't match.
        val events = Recorder()
        val sender = SenderSession(Payload.Secret("pw").encode(), events)
        val attackerAsReader = ReceiverSession().run(sender::process)
        val attackerAsSender = SenderSession(attackerAsReader.payload.encode(), Recorder())
        val victim = ReceiverSession().run(attackerAsSender::process)
        assertNotEquals(events.sas, victim.sas)
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
