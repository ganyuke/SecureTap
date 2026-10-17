package org.nextcolor.securetap.ui

import android.nfc.NfcAdapter
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.nextcolor.securetap.nfc.Inbox
import org.nextcolor.securetap.protocol.Payload
import org.nextcolor.securetap.util.Actions
import org.nextcolor.securetap.util.ImageCompressor
import kotlinx.coroutines.launch

@Composable
fun ReceiveScreen(activity: ComponentActivity, adapter: NfcAdapter, snackbar: SnackbarHostState) {
    val state by Inbox.state.collectAsState()

    // Reader mode turns off this phone's own card emulation, so only enable it on this screen.
    OnResume(
        onPause = { adapter.disableReaderMode(activity) },
        onResume = {
            Inbox.deviceName = Actions.deviceName(activity)
            adapter.enableReaderMode(activity, Inbox, Inbox.READER_FLAGS, null)
        },
    )

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            Inbox.State.Waiting -> {
                Text("Ready to receive", style = MaterialTheme.typography.headlineSmall)
                Text("Hold this phone back to back with the sending phone")
            }
            is Inbox.State.Receiving -> {
                Text("Receiving…", style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(progress = { s.received.toFloat() / s.total }, Modifier.fillMaxWidth())
                progressLabel(s.received, s.total)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Keep the phones together")
            }
            is Inbox.State.Interrupted -> {
                Text("Paused", style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(progress = { s.received.toFloat() / s.total }, Modifier.fillMaxWidth())
                progressLabel(s.received, s.total)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Hold the phones together again to continue")
                OutlinedButton(onClick = Inbox::reset) { Text("Cancel") }
            }
            is Inbox.State.Received -> Received(s, snackbar)
            is Inbox.State.Failed -> {
                Text("Couldn't receive", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when (s.reason) {
                        Inbox.Failure.SENDER_NOT_READY ->
                            "The other phone has nothing ready to send. Choose what to send on it first, then try again."
                        Inbox.Failure.INCOMPATIBLE ->
                            "The other phone has a different version of SecureTap. Update the app on both phones, then try again."
                        Inbox.Failure.CORRUPTED -> "The data was damaged on the way, so it was discarded. Try again."
                        Inbox.Failure.CONNECTION -> "The connection dropped. Try again."
                        Inbox.Failure.UNKNOWN -> "Something went wrong. Try again."
                    },
                )
                Button(onClick = Inbox::reset) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun Received(s: Inbox.State.Received, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun show(message: String) = scope.launch { snackbar.showSnackbar(message) }
    fun copy(text: String, sensitive: Boolean) {
        Actions.copy(context, text, sensitive)
        // Android 13 and later confirm copies themselves.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) show("Copied")
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        Text("Received", style = MaterialTheme.typography.headlineSmall)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val p = s.payload) {
                is Payload.Text -> {
                    SelectionContainer { Text(p.text) }
                    Button(onClick = { copy(p.text, sensitive = false) }) { IconLabel(Icons.Filled.ContentCopy, "Copy") }
                }
                is Payload.Secret -> {
                    var reveal by remember { mutableStateOf(false) }
                    Text(
                        if (reveal) p.secret else "•".repeat(p.secret.length.coerceAtMost(24)),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { reveal = !reveal }) {
                            IconLabel(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (reveal) "Hide" else "Show")
                        }
                        Button(onClick = { copy(p.secret, sensitive = true) }) { IconLabel(Icons.Filled.ContentCopy, "Copy") }
                    }
                }
                is Payload.Link -> {
                    // Always show the full URL so the receiver can see where it goes before opening it.
                    SelectionContainer { Text(p.url, fontFamily = FontFamily.Monospace) }
                    val uri = remember(p.url) { Actions.safeWebUri(p.url) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (uri != null) {
                            Button(onClick = { Actions.openLink(context, uri) }) { IconLabel(Icons.Filled.OpenInBrowser, "Open") }
                        }
                        OutlinedButton(onClick = { copy(p.url, sensitive = false) }) { IconLabel(Icons.Filled.ContentCopy, "Copy") }
                    }
                    if (uri == null) {
                        Text(
                            "This isn't a web link, so it can't be opened",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is Payload.Image -> {
                    val bmp = remember(p) { ImageCompressor.decodeForDisplay(p.bytes) }
                    var saved by remember(p) { mutableStateOf(false) }
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 400.dp), contentScale = ContentScale.Fit)
                        Button(
                            onClick = {
                                saved = Actions.saveImage(context, p.mimeType, p.bytes) != null
                                show(if (saved) "Saved to Pictures" else "Couldn't save photo")
                            },
                            enabled = !saved,
                        ) { IconLabel(Icons.Filled.Save, if (saved) "Saved" else "Save") }
                    } else {
                        Text("Couldn't show this photo", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    TransferDetails(s.sas)
    Button(onClick = Inbox::reset, Modifier.fillMaxWidth()) { Text("Done") }
}
