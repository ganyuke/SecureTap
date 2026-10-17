package org.nextcolor.securetap.nfc

import android.os.SystemClock
import org.nextcolor.securetap.protocol.Payload
import org.nextcolor.securetap.protocol.Protocol
import org.nextcolor.securetap.protocol.SenderSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the single payload the HCE service is allowed to hand out. Each send goes to exactly one
 * receiver with fresh keys; after it's received the payload stays here so it can be sent again,
 * until the user is done with it or it times out.
 */
object Outbox {
    sealed interface State {
        data object Idle : State
        data class Armed(val summary: String, val expiresAt: Long) : State
        /** [connected] is false while the phones are apart mid-transfer. */
        data class Sending(val summary: String, val sas: String, val sent: Int, val total: Int, val connected: Boolean) : State
        data class Sent(val summary: String, val sas: String) : State
    }

    /** How long a payload is kept, counted from arming, the last contact, or the last send. */
    const val TTL_MS = 120_000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var plaintext: ByteArray? = null
    private var summary = ""
    private var session: SenderSession? = null
    private var expiresAt = 0L

    /**
     * Card emulation keeps running in the background, so the payload is only handed out while
     * the Send screen is in front. Leaving the app pauses it; the expiry keeps counting.
     */
    @Volatile
    var sendScreenVisible = false

    @Synchronized
    fun arm(payload: Payload, summary: String) {
        wipe()
        plaintext = payload.encode()
        this.summary = summary
        startSession()
    }

    /** Offers the same payload to another receiver, with fresh keys. */
    @Synchronized
    fun sendAgain() {
        if (_state.value is State.Sent && plaintext != null) startSession()
    }

    @Synchronized
    fun disarm() {
        wipe()
        _state.value = State.Idle
    }

    /** Wipes the payload once nobody has picked it up in time. Safe to call often. */
    @Synchronized
    fun expireIfDue() {
        if (_state.value !is State.Idle && SystemClock.elapsedRealtime() > expiresAt) disarm()
    }

    @Synchronized
    fun handleApdu(apdu: ByteArray): ByteArray {
        expireIfDue()
        if (!sendScreenVisible) return Protocol.SW_NOT_READY
        val s = session ?: return Protocol.SW_NOT_READY
        if (_state.value is State.Sending) expiresAt = SystemClock.elapsedRealtime() + TTL_MS
        return s.process(apdu)
    }

    @Synchronized
    fun onLinkLost() {
        session?.onDeactivated()
        (_state.value as? State.Sending)?.let { _state.value = it.copy(connected = false) }
    }

    private fun startSession() {
        val summary = summary
        expiresAt = SystemClock.elapsedRealtime() + TTL_MS
        // The session zeroes its copy when it finishes, so it gets one of its own.
        session = SenderSession(plaintext!!.copyOf(), object : SenderSession.Events {
            override fun onReceiverConnected(sas: String) {
                _state.value = State.Sending(summary, sas, 0, 1, connected = true)
            }

            override fun onProgress(sent: Int, total: Int) {
                (_state.value as? State.Sending)?.let { _state.value = it.copy(sent = sent, total = total, connected = true) }
            }

            override fun onFinished() {
                // FINISH is only accepted after KEY, so we're always in Sending here.
                _state.value = State.Sent(summary, (_state.value as? State.Sending)?.sas.orEmpty())
                session = null
                expiresAt = SystemClock.elapsedRealtime() + TTL_MS
            }
        })
        _state.value = State.Armed(summary, expiresAt)
    }

    private fun wipe() {
        session?.wipe()
        session = null
        plaintext?.fill(0)
        plaintext = null
    }
}
