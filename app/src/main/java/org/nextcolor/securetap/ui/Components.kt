package org.nextcolor.securetap.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

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
    if (total < 2048) null else "${done / 1024} KB of ${total / 1024} KB"
