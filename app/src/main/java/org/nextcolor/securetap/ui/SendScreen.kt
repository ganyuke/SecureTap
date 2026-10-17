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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            Outbox.State.Idle -> Composer()
            is Outbox.State.Armed -> ArmedCard(s)
            is Outbox.State.Sending -> {
                Text("Sending…", style = MaterialTheme.typography.headlineSmall)
                SasCode(s.sas)
                LinearProgressIndicator(progress = { s.sent.toFloat() / s.total }, Modifier.fillMaxWidth())
                Text("Keep the phones together.")
            }
            is Outbox.State.Sent -> {
                Text("Sent ✓", style = MaterialTheme.typography.headlineSmall)
                Text(s.summary)
                SasCode(s.sas)
                Text("The data has been wiped from this phone.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = Outbox::disarm) { Text("Send something else") }
            }
        }
    }
}

@Composable
private fun Composer() {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                error = "Couldn't load that picture: ${e.message}"
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
            label = { Text("https://…") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Kind.Secret -> OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(),
            label = { Text("Password or secret") }, singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show")
                }
            },
        )
        Kind.Image -> {
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy,
            ) { Text(if (busy) "Compressing…" else "Choose a photo") }
            imageBytes?.let { bytes ->
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(), null,
                        Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    "${bytes.size / 1024} KB · about ${estimateSeconds(bytes.size)} s to transfer · location data removed",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    Button(
        onClick = {
            error = null
            val payload = when (kind) {
                Kind.Text -> text.takeIf { it.isNotBlank() }?.let { Payload.Text(it) }
                Kind.Link -> text.trim().takeIf { it.isNotEmpty() }?.let {
                    Payload.Link(if ("://" in it) it else "https://$it")
                }
                Kind.Secret -> text.takeIf { it.isNotEmpty() }?.let { Payload.Secret(it) }
                Kind.Image -> imageBytes?.let { Payload.Image("image/jpeg", it) }
            }
            if (payload == null) {
                error = "Nothing to send yet."
                return@Button
            }
            val summary = when (payload) {
                is Payload.Secret -> "A password (${text.length} characters)"
                is Payload.Image -> "A photo (${payload.bytes.size / 1024} KB)"
                is Payload.Link -> payload.url
                is Payload.Text -> "A message (${text.length} characters)"
            }
            try {
                Outbox.arm(payload, summary)
                text = ""
                imageBytes = null
            } catch (e: Exception) {
                error = e.message
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Encrypt & get ready to tap") }

    Text(
        "Each transfer uses fresh keys (ECDH P-256 → AES-256-GCM) and works once. " +
            "Both phones show a 6-digit code; check that they match.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ArmedCard(s: Outbox.State.Armed) {
    var remaining by remember { mutableLongStateOf(s.expiresAt - SystemClock.elapsedRealtime()) }
    LaunchedEffect(s) {
        while (true) {
            remaining = s.expiresAt - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                Outbox.disarm()
                break
            }
            delay(500)
        }
    }
    Text("Ready to send", style = MaterialTheme.typography.headlineSmall)
    Text(s.summary)
    Text(
        "Open Receive on the other phone, then hold the two phones back to back.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text("Expires in ${(remaining / 1000).coerceAtLeast(0)} s", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = Outbox::disarm) { Text("Cancel") }
}

@Composable
fun SasCode(sas: String) {
    Text("Verification code", style = MaterialTheme.typography.labelLarge)
    Text(sas, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
}

private fun estimateSeconds(bytes: Int): Int = (bytes / (8 * 1024)).coerceAtLeast(1)
