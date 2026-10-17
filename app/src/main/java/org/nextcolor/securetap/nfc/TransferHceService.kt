package org.nextcolor.securetap.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/** Lets this phone act as an NFC card that the receiving phone reads from. */
class TransferHceService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray =
        Outbox.handleApdu(commandApdu)

    override fun onDeactivated(reason: Int) {
        Outbox.onLinkLost()
    }
}
