package org.nextcolor.securetap.nfc

import android.nfc.NfcAdapter
import android.os.Build
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import org.nextcolor.securetap.protocol.Payload
import org.nextcolor.securetap.protocol.ProtocolException
import org.nextcolor.securetap.protocol.ReceiverSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.security.GeneralSecurityException

/** Reader-mode callback for the receiving phone. Runs on an NFC binder thread. */
object Inbox : NfcAdapter.ReaderCallback {
    sealed interface State {
        data object Waiting : State
        data class Receiving(val received: Int, val total: Int) : State
        data class Interrupted(val received: Int, val total: Int) : State
        data class Received(val payload: Payload, val sas: String) : State
        data class Failed(val reason: Failure) : State
    }

    enum class Failure { SENDER_NOT_READY, INCOMPATIBLE, CORRUPTED, CONNECTION, UNKNOWN }

    private val _state = MutableStateFlow<State>(State.Waiting)
    val state: StateFlow<State> = _state

    private var session = ReceiverSession()

    /** This phone's name, shown on the sending phone after a transfer. Set by the Receive screen. */
    @Volatile
    var deviceName: String = Build.MODEL

    const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    @Synchronized
    fun reset() {
        session = ReceiverSession()
        _state.value = State.Waiting
    }

    @Synchronized
    override fun onTagDiscovered(tag: Tag) {
        // Once something's been received, ignore taps until the user clears it.
        if (_state.value is State.Received) return
        val iso = IsoDep.get(tag) ?: return
        try {
            iso.connect()
            iso.timeout = 5_000
            val result = session.run(
                transport = { iso.transceive(it) },
                deviceName = deviceName,
                onConnected = { _state.value = State.Receiving(0, 1) },
                onProgress = { r, t -> _state.value = State.Receiving(r, t) },
            )
            session = ReceiverSession()
            _state.value = State.Received(result.payload, result.sas)
        } catch (e: IOException) {
            // Phones moved apart. Keep the session so the next tap resumes.
            val s = _state.value
            _state.value = when {
                s is State.Receiving -> State.Interrupted(s.received, s.total)
                s is State.Interrupted -> s
                e is TagLostException -> State.Waiting
                else -> State.Failed(Failure.CONNECTION)
            }
        } catch (e: Exception) {
            session = ReceiverSession()
            _state.value = State.Failed(
                when {
                    e is GeneralSecurityException -> Failure.CORRUPTED
                    e is ProtocolException && e.reason == ProtocolException.Reason.SENDER_NOT_READY -> Failure.SENDER_NOT_READY
                    e is ProtocolException && e.reason == ProtocolException.Reason.INCOMPATIBLE -> Failure.INCOMPATIBLE
                    else -> Failure.UNKNOWN
                },
            )
        } finally {
            runCatching { iso.close() }
        }
    }
}
