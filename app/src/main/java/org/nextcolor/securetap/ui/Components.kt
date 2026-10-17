package org.nextcolor.securetap.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.nextcolor.securetap.protocol.Payload

/** Button content with a leading icon, spaced the way Material buttons expect. */
@Composable
fun RowScope.IconLabel(icon: ImageVector, text: String) {
    Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text)
}

/**
 * The verification code, folded away under "Details". It only matters to someone checking
 * that nobody relayed the connection, so it stays out of the way of everyone else.
 */
@Composable
fun TransferDetails(sas: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }) {
            IconLabel(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, "Details")
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text("Verification code", style = MaterialTheme.typography.labelLarge)
                Text(sas, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "The other phone shows the same code if nobody intercepted the transfer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "48 KB of 120 KB", or nothing for payloads too small for the numbers to mean anything. */
fun progressLabel(done: Int, total: Int): String? =
    if (total < 2048) null else "${formatSize(done)} of ${formatSize(total)}"

/** "120 KB" or "3.2 MB". */
fun formatSize(bytes: Int): String =
    if (bytes < 1024 * 1024) "${(bytes / 1024).coerceAtLeast(1)} KB" else "%.1f MB".format(bytes / (1024.0 * 1024))

/** How long the phones have to stay together, as a range since NFC speed varies a lot between phones. */
fun transferTime(bytes: Int): String {
    val fast = (bytes / (20 * 1024)).coerceAtLeast(1)
    val slow = (bytes / (5 * 1024)).coerceAtLeast(2)
    return if (slow < 120) "$fast–$slow seconds" else "${(fast + 30) / 60}–${(slow + 30) / 60} minutes"
}

/**
 * Size and sending time for a photo or file. Past [Payload.SLOW_SIZE] the time becomes a warning,
 * since the phones have to stay together the whole time.
 */
@Composable
fun SizeNote(bytes: Int, extra: String? = null) {
    val details = listOfNotNull(formatSize(bytes), extra).joinToString(" · ")
    if (bytes <= Payload.SLOW_SIZE) {
        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = warningColor())
        Text(
            "$details · Takes about ${transferTime(bytes)}. Keep the phones together the whole time.",
            style = MaterialTheme.typography.bodySmall,
            color = warningTextColor(),
        )
    }
}

/** Material 3 has no warning role; a yellow that stays visible on both light and dark surfaces. */
@Composable
fun warningColor(): Color = if (isSystemInDarkTheme()) Color(0xFFFFD54F) else Color(0xFFC79100)

/** [warningColor] for text: darker in light mode, since that yellow is too faint for small type. */
@Composable
fun warningTextColor(): Color = if (isSystemInDarkTheme()) Color(0xFFFFD54F) else Color(0xFF8A5A00)
