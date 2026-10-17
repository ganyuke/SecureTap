package org.nextcolor.securetap.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import org.nextcolor.securetap.protocol.Payload
import org.nextcolor.securetap.protocol.ReceiverSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** Reader-mode callback for the receiving phone. Runs on an NFC binder thread. */
object Inbox : NfcAdapter.ReaderCallback {
    sealed interface State {
        data object Waiting : State
        data class Receiving(val sas: String, val received: Int, val total: Int) : State
        data class Interrupted(val sas: String, val received: Int, val total: Int) : State
        data class Received(val payload: Payload, val sas: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Waiting)
    val state: StateFlow<State> = _state

    private var session = ReceiverSession()

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
        var sas = ""
        try {
            iso.connect()
            iso.timeout = 5_000
            val chunk = if (iso.isExtendedLengthApduSupported) {
                (iso.maxTransceiveLength - 2).coerceIn(ReceiverSession.SHORT_CHUNK, 4096)
            } else ReceiverSession.SHORT_CHUNK
            val result = session.run(
                transport = { iso.transceive(it) },
                chunkSize = chunk,
                onConnected = { sas = it; _state.value = State.Receiving(it, 0, 1) },
                onProgress = { r, t -> _state.value = State.Receiving(sas, r, t) },
            )
            session = ReceiverSession()
            _state.value = State.Received(result.payload, result.sas)
        } catch (e: IOException) {
            // Phones moved apart. Keep the session so the next tap resumes.
            val s = _state.value
            _state.value = when {
                s is State.Receiving -> State.Interrupted(s.sas, s.received, s.total)
                s is State.Interrupted -> s
                e is TagLostException -> State.Waiting
                else -> State.Failed("Connection error: ${e.message ?: "unknown"}")
            }
        } catch (e: Exception) {
            session = ReceiverSession()
            _state.value = State.Failed(
                if (e is java.security.GeneralSecurityException) "Integrity check failed — data was corrupted or tampered with"
                else e.message ?: "Transfer failed"
            )
        } finally {
            runCatching { iso.close() }
        }
    }
}
