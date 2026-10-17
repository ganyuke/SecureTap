package org.nextcolor.securetap.nfc

import android.os.SystemClock
import org.nextcolor.securetap.protocol.Payload
import org.nextcolor.securetap.protocol.Protocol
import org.nextcolor.securetap.protocol.SenderSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds the single payload the HCE service is allowed to hand out. */
object Outbox {
    sealed interface State {
        data object Idle : State
        data class Armed(val summary: String, val expiresAt: Long) : State
        data class Sending(val summary: String, val sas: String, val sent: Int, val total: Int) : State
        data class Sent(val summary: String, val sas: String) : State
    }

    const val ARM_TTL_MS = 120_000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var session: SenderSession? = null
    private var expiresAt = 0L

    @Synchronized
    fun arm(payload: Payload, summary: String) {
        session?.wipe()
        val plaintext = payload.encode()
        expiresAt = SystemClock.elapsedRealtime() + ARM_TTL_MS
        session = SenderSession(plaintext, object : SenderSession.Events {
            override fun onReceiverConnected(sas: String) {
                _state.value = State.Sending(summary, sas, 0, 1)
            }

            override fun onProgress(sent: Int, total: Int) {
                (_state.value as? State.Sending)?.let { _state.value = it.copy(sent = sent, total = total) }
            }

            override fun onFinished() {
                // FINISH is only accepted after KEY, so we're always in Sending here.
                _state.value = State.Sent(summary, (_state.value as? State.Sending)?.sas.orEmpty())
                session = null
            }
        })
        _state.value = State.Armed(summary, expiresAt)
    }

    @Synchronized
    fun disarm() {
        session?.wipe()
        session = null
        _state.value = State.Idle
    }

    @Synchronized
    fun handleApdu(apdu: ByteArray): ByteArray {
        val s = session ?: return Protocol.SW_NOT_READY
        // Only expire while nobody is mid-transfer.
        if (_state.value is State.Armed && SystemClock.elapsedRealtime() > expiresAt) {
            disarm()
            return Protocol.SW_NOT_READY
        }
        return s.process(apdu)
    }

    @Synchronized
    fun onLinkLost() {
        session?.onDeactivated()
    }
}
