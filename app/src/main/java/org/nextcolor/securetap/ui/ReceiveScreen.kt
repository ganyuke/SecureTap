package org.nextcolor.securetap.ui

import android.nfc.NfcAdapter
import android.widget.Toast
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@Composable
fun ReceiveScreen(activity: ComponentActivity, adapter: NfcAdapter) {
    val state by Inbox.state.collectAsState()

    // Reader mode turns off this phone's own card emulation, so only enable it on this screen.
    OnResume(
        onPause = { adapter.disableReaderMode(activity) },
        onResume = { adapter.enableReaderMode(activity, Inbox, Inbox.READER_FLAGS, null) },
    )

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            Inbox.State.Waiting -> {
                Text("Ready to receive", style = MaterialTheme.typography.headlineSmall)
                Text("Hold this phone back to back with the sending phone.")
            }
            is Inbox.State.Receiving -> {
                Text("Receiving…", style = MaterialTheme.typography.headlineSmall)
                SasCode(s.sas)
                LinearProgressIndicator(progress = { s.received.toFloat() / s.total }, Modifier.fillMaxWidth())
                Text("${s.received / 1024} / ${s.total / 1024} KB")
            }
            is Inbox.State.Interrupted -> {
                Text("Connection lost", style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(progress = { s.received.toFloat() / s.total }, Modifier.fillMaxWidth())
                Text("Tap the phones together again to pick up where it stopped.")
                OutlinedButton(onClick = Inbox::reset) { Text("Give up") }
            }
            is Inbox.State.Received -> Received(s)
            is Inbox.State.Failed -> {
                Text("Transfer failed", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                Text(s.message)
                Button(onClick = Inbox::reset) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun Received(s: Inbox.State.Received) {
    val context = LocalContext.current
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Text("Received ✓", style = MaterialTheme.typography.headlineSmall)
    SasCode(s.sas)
    Text(
        "Make sure the sender sees the same code. If it's different, don't trust this data.",
        style = MaterialTheme.typography.bodySmall,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val p = s.payload) {
                is Payload.Text -> {
                    SelectionContainer { Text(p.text) }
                    Button(onClick = { Actions.copy(context, p.text, sensitive = false); toast("Copied") }) {
                        Icon(Icons.Filled.ContentCopy, null); Text("  Copy")
                    }
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
                            Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                            Text(if (reveal) "  Hide" else "  Show")
                        }
                        Button(onClick = { Actions.copy(context, p.secret, sensitive = true); toast("Copied") }) {
                            Icon(Icons.Filled.ContentCopy, null); Text("  Copy")
                        }
                    }
                }
                is Payload.Link -> {
                    // Always show the full URL so the receiver can see where it goes before opening it.
                    SelectionContainer { Text(p.url, fontFamily = FontFamily.Monospace) }
                    val uri = remember(p.url) { Actions.safeWebUri(p.url) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (uri != null) {
                            Button(onClick = { Actions.openLink(context, uri) }) {
                                Icon(Icons.Filled.OpenInBrowser, null); Text("  Open")
                            }
                        }
                        OutlinedButton(onClick = { Actions.copy(context, p.url, sensitive = false); toast("Copied") }) {
                            Icon(Icons.Filled.ContentCopy, null); Text("  Copy")
                        }
                    }
                    if (uri == null) Text("Not a web link, so it can't be opened directly.", style = MaterialTheme.typography.bodySmall)
                }
                is Payload.Image -> {
                    val bmp = remember(p) { ImageCompressor.decodeForDisplay(p.bytes) }
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 400.dp), contentScale = ContentScale.Fit)
                        Button(onClick = {
                            toast(if (Actions.saveImage(context, p.mimeType, p.bytes) != null) "Saved to Pictures/SecureTap" else "Couldn't save")
                        }) { Icon(Icons.Filled.Save, null); Text("  Save to gallery") }
                    } else {
                        Text("The picture couldn't be decoded.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    Button(onClick = Inbox::reset, Modifier.fillMaxWidth()) { Text("Done — receive another") }
}
