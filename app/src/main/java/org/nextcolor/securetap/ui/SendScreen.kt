package org.nextcolor.securetap.ui

import android.content.ComponentName
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.nextcolor.securetap.nfc.Outbox
import org.nextcolor.securetap.nfc.TransferHceService
import org.nextcolor.securetap.protocol.Payload
import org.nextcolor.securetap.util.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Kind(val label: String) { Text("Text"), Link("Link"), Secret("Password"), Image("Photo") }

@Composable
fun SendScreen(activity: ComponentActivity) {
    val state by Outbox.state.collectAsState()

    // While this screen is visible, make sure our HCE service wins over e.g. payment apps.
    val component = remember { ComponentName(activity, TransferHceService::class.java) }
    val cardEmulation = remember { CardEmulation.getInstance(NfcAdapter.getDefaultAdapter(activity)) }
    OnResume(
        onPause = { runCatching { cardEmulation.unsetPreferredService(activity) } },
        onResume = { runCatching { cardEmulation.setPreferredService(activity, component) } },
    )

    // Drops the payload if nobody picks it up, including when the phones were pulled apart mid-transfer.
    LaunchedEffect(Unit) {
        while (true) {
            Outbox.expireIfDue()
            delay(500)
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            Outbox.State.Idle -> Composer()
            is Outbox.State.Armed -> Armed(s)
            is Outbox.State.Sending -> {
                Text(if (s.connected) "Sending…" else "Paused", style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(progress = { s.sent.toFloat() / s.total }, Modifier.fillMaxWidth())
                progressLabel(s.sent, s.total)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(if (s.connected) "Keep the phones together" else "Hold the phones together again to continue")
                OutlinedButton(onClick = Outbox::disarm) { Text("Cancel") }
            }
            is Outbox.State.Sent -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Sent", style = MaterialTheme.typography.headlineSmall)
                }
                Text(s.summary)
                Text(
                    "Tap Done to remove it from this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TransferDetails(s.sas)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = Outbox::sendAgain, Modifier.weight(1f)) { Text("Send again") }
                    Button(onClick = Outbox::disarm, Modifier.weight(1f)) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun Composer() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(Kind.Text) }
    var text by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        scope.launch {
            imageBytes = try {
                withContext(Dispatchers.Default) { ImageCompressor.compress(context, uri) }
            } catch (e: Exception) {
                error = "Couldn't open this photo. Try another one."
                null
            }
            busy = false
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Kind.entries.forEach { k ->
            FilterChip(
                selected = kind == k,
                onClick = { kind = k; error = null },
                label = { Text(k.label) },
                leadingIcon = {
                    Icon(
                        when (k) {
                            Kind.Text -> Icons.Filled.TextFields
                            Kind.Link -> Icons.Filled.Link
                            Kind.Secret -> Icons.Filled.Password
                            Kind.Image -> Icons.Filled.Image
                        },
                        null,
                    )
                },
            )
        }
    }

    when (kind) {
        Kind.Text -> OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp),
            label = { Text("Message") },
        )
        Kind.Link -> OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(),
            label = { Text("Link") }, placeholder = { Text("example.com") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Kind.Secret -> OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(),
            label = { Text("Password") }, singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        if (reveal) "Hide password" else "Show password",
                    )
                }
            },
        )
        Kind.Image -> {
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy,
            ) { Text(if (imageBytes == null) "Choose photo" else "Change photo") }
            if (busy) Text("Preparing photo…", style = MaterialTheme.typography.bodySmall)
            imageBytes?.let { bytes ->
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(), null,
                        Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    "${bytes.size / 1024} KB · About ${estimateSeconds(bytes.size)} seconds to send · Location info removed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    val payload: Payload? = when (kind) {
        Kind.Text -> text.takeIf { it.isNotBlank() }?.let { Payload.Text(it) }
        Kind.Link -> text.trim().takeIf { it.isNotEmpty() }?.let { Payload.Link(if ("://" in it) it else "https://$it") }
        Kind.Secret -> text.takeIf { it.isNotEmpty() }?.let { Payload.Secret(it) }
        Kind.Image -> imageBytes?.let { Payload.Image("image/jpeg", it) }
    }

    Button(
        onClick = {
            val p = payload ?: return@Button
            val summary = when (p) {
                is Payload.Secret -> "Password · ${text.length} characters"
                is Payload.Image -> "Photo · ${p.bytes.size / 1024} KB"
                is Payload.Link -> p.url
                is Payload.Text -> "Message · ${text.length} characters"
            }
            try {
                Outbox.arm(p, summary)
                text = ""
                imageBytes = null
            } catch (e: Exception) {
                error = "Too big to send. Try something smaller."
            }
        },
        enabled = payload != null && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Send") }

    Text(
        "Encrypted end to end. Each send reaches one phone and expires after 2 minutes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Armed(s: Outbox.State.Armed) {
    var remaining by remember { mutableLongStateOf(s.expiresAt - SystemClock.elapsedRealtime()) }
    LaunchedEffect(s) {
        while (remaining > 0) {
            remaining = s.expiresAt - SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val seconds = (remaining / 1000).coerceAtLeast(0)
    Text("Ready to send", style = MaterialTheme.typography.headlineSmall)
    Text(s.summary)
    Text(
        "On the other phone, open SecureTap and go to Receive. Then hold the phones back to back.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "Expires in %d:%02d".format(seconds / 60, seconds % 60),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = Outbox::disarm) { Text("Cancel") }
}

private fun estimateSeconds(bytes: Int): Int = (bytes / (8 * 1024)).coerceAtLeast(1)
