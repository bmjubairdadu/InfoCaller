package com.infocaller.app.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.infocaller.app.ui.theme.Primary

/**
 * First-open consent for background caller-ID contribution.
 * Exactly two actions: Accept / Decline. Shown once (UNASKED only).
 */
@Composable
fun ContributionConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* must choose Accept or Decline */ },
        title = { Text("Help improve caller ID for everyone?") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "InfoCaller can process your contacts one-by-one in the background and " +
                        "contribute permitted caller-ID information to the shared InfoCaller " +
                        "database so other users can benefit.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text("May be shared:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "• Phone-number fingerprint (SHA-256 hash, never the number itself)\n" +
                        "• Public caller name found by caller-ID lookup (e.g. business / public listing name)",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text("Never shared:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "• Your private contact names, notes, contact IDs / lookup keys\n" +
                        "• Photos, messages, call history, or any unrelated local metadata\n" +
                        "• Plain phone numbers",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Uploads run one-by-one in the background, skip duplicates, and retry " +
                        "automatically. You can withdraw anytime in Settings → Privacy Policy. " +
                        "All database writes happen on our server — the app never holds write keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("Accept", color = Primary, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Decline") }
        }
    )
}
