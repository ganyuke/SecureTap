package org.nextcolor.securetap.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.MediaStore
import android.content.ClipboardManager

object Actions {
    fun copy(context: Context, text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText("SecureTap", text)
        if (sensitive) {
            // Hides the value from the clipboard preview/overlay on Android 13+.
            clip.description.extras = PersistableBundle().apply {
                val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
                else "android.content.extra.IS_SENSITIVE"
                putBoolean(key, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    /** Only http(s) links are opened, so a sender can't push intent:, file: or javascript: URIs. */
    fun safeWebUri(url: String): Uri? {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return null
        return uri.takeIf { (it.scheme == "https" || it.scheme == "http") && !it.host.isNullOrBlank() }
    }

    fun openLink(context: Context, uri: Uri) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun saveImage(context: Context, mimeType: String, bytes: ByteArray): Uri? {
        val ext = when (mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "securetap_${System.currentTimeMillis()}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SecureTap")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("No output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
