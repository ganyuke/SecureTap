package org.nextcolor.securetap.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nextcolor.securetap.nfc.Outbox
import org.nextcolor.securetap.nfc.TransferHceService
import org.nextcolor.securetap.protocol.Payload
import org.nextcolor.securetap.util.Actions
import org.nextcolor.securetap.util.ImageCompressor
import org.nextcolor.securetap.util.ImageCompressor.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Kind(val label: String) { Text("Text"), Link("Link"), Secret("Password"), Image("Photo"), File("File") }

@Composable
fun SendScreen(activity: ComponentActivity) {
    val state by Outbox.state.collectAsState()

    // Only hand the payload out while this screen is in front, and make sure our HCE service
    // wins over e.g. payment apps meanwhile.
    val component = remember { ComponentName(activity, TransferHceService::class.java) }
    val cardEmulation = remember { CardEmulation.getInstance(NfcAdapter.getDefaultAdapter(activity)) }
    OnResume(
        onPause = {
            Outbox.sendScreenVisible = false
            runCatching { cardEmulation.unsetPreferredService(activity) }
        },
        onResume = {
            Outbox.sendScreenVisible = true
            runCatching { cardEmulation.setPreferredService(activity, component) }
        },
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
                    Text("Sent to ${s.receiverName}", style = MaterialTheme.typography.headlineSmall)
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
    var photo by remember { mutableStateOf<PickedPhoto?>(null) }
    var compress by remember { mutableStateOf(true) }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var keepInfo by remember { mutableStateOf(prefs.getBoolean(KEEP_INFO, false)) }
    var file by remember { mutableStateOf<Payload.File?>(null) }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Payload?>(null) }

    fun loadPhoto(uri: Uri) {
        busy = true
        error = null
        scope.launch {
            photo = try {
                withContext(Dispatchers.IO) { pickPhoto(context, uri) }
            } catch (e: Exception) {
                error = "Couldn't open this photo. Try another one."
                null
            }
            compress = true
            busy = false
        }
    }

    val photoPicker = rememberLauncherForActivityResult(PickPhotoWithLocation()) { uri: Uri? ->
        if (uri != null) loadPhoto(uri)
    }

    fun loadFile(uri: Uri) {
        busy = true
        error = null
        fileUri = uri
        scope.launch {
            file = try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val picked = Actions.describe(context, uri)
                    // Skip reading when the provider already says it's too big.
                    picked.name to if ((picked.size ?: 0) > MAX_DATA) null else Actions.readUpTo(context, uri, MAX_DATA)
                }
                if (bytes == null) error = "This file is too big. Files can be up to ${formatSize(MAX_DATA)}."
                bytes?.let { Payload.File(name, it) }
            } catch (e: Exception) {
                error = "Couldn't open this file. Try another one."
                null
            }
            busy = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) loadFile(uri)
    }

    // Files opened without this have the location removed from photos. Once granted, read the
    // file again so the location comes along.
    var canSeeLocation by remember { mutableStateOf(hasMediaLocation(context)) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canSeeLocation = granted
        if (granted) fileUri?.let { loadFile(it) }
    }

    // Wraps instead of squeezing the last chip off narrow screens.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            Kind.File -> Icons.Filled.AttachFile
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
                onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy,
            ) { Text(if (photo == null) "Choose photo" else "Change photo") }
            if (busy) Text("Preparing photo…", style = MaterialTheme.typography.bodySmall)
            photo?.let { p ->
                val bmp = remember(p) { BitmapFactory.decodeByteArray(p.compressed, 0, p.compressed.size) }
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(), null,
                        Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit,
                    )
                }
                OptionSwitch(
                    title = "Compress",
                    detail = when {
                        p.original == null && p.originalTooBig -> "Too big to send uncompressed. Photos can be up to ${formatSize(MAX_DATA)}."
                        p.original == null -> "Only JPEG, PNG and WebP photos can be sent uncompressed. Try sending it as a File."
                        compress -> "On: smaller and faster to send"
                        else -> "Off: full quality, exactly as taken"
                    },
                    checked = compress || p.original == null,
                    enabled = p.original != null,
                    onChange = { compress = it },
                )
                OptionSwitch(
                    title = "Keep location and camera info",
                    detail = when {
                        !keepInfo -> "Off: the receiver won't see where or with what it was taken"
                        p.location == Location.Present -> "On: includes where it was taken"
                        p.location == Location.None -> "On: this photo has no location info"
                        pickerCanShareLocation() -> "On: location sharing is off in the photo picker. Turn it on there and choose the photo again."
                        else -> "On: Android removed the location when you chose this photo. To keep it, send the photo as a File."
                    },
                    checked = keepInfo,
                    onChange = {
                        keepInfo = it
                        prefs.edit().putBoolean(KEEP_INFO, it).apply()
                    },
                )
                SizeNote(p.bytes(compress, keepInfo).size)
            }
        }
        Kind.File -> {
            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                Text(if (file == null) "Choose file" else "Change file")
            }
            if (busy) Text("Opening file…", style = MaterialTheme.typography.bodySmall)
            file?.let { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!canSeeLocation && ImageCompressor.location(f.bytes) == Location.Removed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Android removed where this photo was taken",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }) {
                            Text("Allow")
                        }
                    }
                }
                SizeNote(f.bytes.size)
            }
        }
    }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    val payload: Payload? = when (kind) {
        Kind.Text -> text.takeIf { it.isNotBlank() }?.let { Payload.Text(it) }
        Kind.Link -> text.trim().takeIf { it.isNotEmpty() }?.let { Payload.Link(if ("://" in it) it else "https://$it") }
        Kind.Secret -> text.takeIf { it.isNotEmpty() }?.let { Payload.Secret(it) }
        Kind.Image -> photo?.let {
            Payload.Image(if (!compress && it.original != null) it.originalMime else "image/jpeg", it.bytes(compress, keepInfo))
        }
        Kind.File -> file
    }

    fun send(p: Payload) {
        val summary = when (p) {
            is Payload.Secret -> "Password · ${text.length} characters"
            is Payload.Image -> "Photo · ${formatSize(p.bytes.size)}"
            is Payload.File -> "${p.name} · ${formatSize(p.bytes.size)}"
            is Payload.Link -> p.url
            is Payload.Text -> "Message · ${text.length} characters"
        }
        try {
            Outbox.arm(p, summary)
            text = ""
            photo = null
            file = null
        } catch (e: Exception) {
            error = "Too big to send. Try something smaller."
        }
    }

    Button(
        onClick = {
            val p = payload ?: return@Button
            if (sizeOf(p) > Payload.CONFIRM_SIZE) confirm = p else send(p)
        },
        enabled = payload != null && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Send") }

    Text(
        "Encrypted end to end. Each send reaches one phone and expires after 2 minutes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    confirm?.let { p ->
        val size = sizeOf(p)
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = warningColor()) },
            title = { Text("This will take a while") },
            text = {
                Text(
                    "Sending ${formatSize(size)} takes about ${transferTime(size)}. You'll have to hold the phones " +
                        "together the whole time. If they slip apart, tap them together again to continue." +
                        if (p is Payload.Image && !compress) "\n\nThe compressed photo sends much faster." else "",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; send(p) }) { Text("Send anyway") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OptionSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.6f
        Column(Modifier.weight(1f).padding(end = 12.dp).alpha(alpha)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A picked photo, shrunk for a quick send with and without its metadata, plus the file as it is,
 * with and without its metadata, when it can be sent uncompressed.
 */
private class PickedPhoto(
    val compressed: ByteArray,
    val compressedWithInfo: ByteArray,
    val location: Location,
    val original: ByteArray?,
    val originalWithoutInfo: ByteArray?,
    val originalMime: String,
    val originalTooBig: Boolean,
) {
    fun bytes(compress: Boolean, keepInfo: Boolean) = when {
        !compress && original != null -> if (keepInfo) original else originalWithoutInfo ?: original
        keepInfo -> compressedWithInfo
        else -> compressed
    }
}

/**
 * The photo picker removes the location unless the app asks for it, and then the user decides.
 * Pickers from before Android 17.1 ignore the request.
 */
private class PickPhotoWithLocation : ActivityResultContracts.PickVisualMedia() {
    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent =
        // MediaStore.EXTRA_REQUEST_LOCATION_METADATA_ACCESS, added in 37.1.
        super.createIntent(context, input).putExtra("android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS", true)
}

/** Android 17.1 (VERSION_CODES_FULL.CINNAMON_BUN_1) and up: the picker can hand over the location. */
private fun pickerCanShareLocation() = Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= 3_700_001

private fun hasMediaLocation(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun pickPhoto(context: Context, uri: Uri): PickedPhoto {
    val compressed = ImageCompressor.compress(context, uri)
    val withInfo = runCatching { ImageCompressor.copyMetadata(context, uri, compressed) }.getOrDefault(compressed)
    val location = ImageCompressor.location(context, uri)
    val mime = context.contentResolver.getType(uri).orEmpty()
    if (mime !in Payload.ALLOWED_IMAGE_TYPES) {
        return PickedPhoto(compressed, withInfo, location, null, null, mime, originalTooBig = false)
    }
    // Skip reading when the provider already says it's too big.
    val original = if ((Actions.describe(context, uri).size ?: 0) > MAX_DATA) null else Actions.readUpTo(context, uri, MAX_DATA)
    val withoutInfo = original?.let { ImageCompressor.withoutMetadata(context, it) }
    return PickedPhoto(compressed, withInfo, location, original, withoutInfo, mime, originalTooBig = original == null)
}

private fun sizeOf(p: Payload): Int = when (p) {
    is Payload.Image -> p.bytes.size
    is Payload.File -> p.bytes.size
    is Payload.Text -> p.text.toByteArray().size
    is Payload.Link -> p.url.toByteArray().size
    is Payload.Secret -> p.secret.toByteArray().size
}

private const val KEEP_INFO = "keep_photo_info"

/** Leaves room for the kind and name in front of the data. */
private const val MAX_DATA = Payload.MAX_ENCODED_SIZE - 2 - 255

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
