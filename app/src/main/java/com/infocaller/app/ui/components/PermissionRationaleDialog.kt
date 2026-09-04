package com.infocaller.app.ui.components

import android.Manifest
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.ui.theme.Primary

/**
 * Permission explanation mapping - maps each permission to a user-friendly title and description
 */
object PermissionExplanations {
    private val explanations = mapOf(
        Manifest.permission.READ_PHONE_STATE to PermissionInfo(
            title = "Phone State Access",
            description = "Allows InfoCaller to detect incoming/outgoing calls and show caller ID in real-time.",
            icon = Icons.Default.Phone
        ),
        Manifest.permission.READ_PHONE_NUMBERS to PermissionInfo(
            title = "Phone Number Access",
            description = "Allows InfoCaller to read your phone number for identification and verification.",
            icon = Icons.Default.Phone
        ),
        Manifest.permission.ANSWER_PHONE_CALLS to PermissionInfo(
            title = "Answer Calls",
            description = "Allows InfoCaller to answer incoming calls on your behalf (e.g., for call recording or screening).",
            icon = Icons.Default.Call
        ),
        Manifest.permission.MANAGE_OWN_CALLS to PermissionInfo(
            title = "Manage Calls",
            description = "Allows InfoCaller to manage call states, block spam, and provide caller ID during calls.",
            icon = Icons.Default.Call
        ),
        Manifest.permission.CALL_PHONE to PermissionInfo(
            title = "Make Calls",
            description = "Allows InfoCaller to initiate phone calls directly from the app (e.g., tap-to-call from contacts).",
            icon = Icons.Default.Call
        ),
        Manifest.permission.READ_CONTACTS to PermissionInfo(
            title = "Contacts Access",
            description = "Allows InfoCaller to read your contacts to show names for incoming/outgoing calls and enrich contact info.",
            icon = Icons.Default.Contacts
        ),
        Manifest.permission.WRITE_CONTACTS to PermissionInfo(
            title = "Modify Contacts",
            description = "Allows InfoCaller to save enriched caller information (names, photos, tags) back to your contacts.",
            icon = Icons.Default.Contacts
        ),
        Manifest.permission.READ_CALL_LOG to PermissionInfo(
            title = "Call History Access",
            description = "Allows InfoCaller to read your call history to identify missed/unknown callers and show recent activity.",
            icon = Icons.Default.History
        ),
        Manifest.permission.WRITE_CALL_LOG to PermissionInfo(
            title = "Write Call Log",
            description = "Allows InfoCaller to save call records with enriched caller information to your call history.",
            icon = Icons.Default.History
        ),
        Manifest.permission.POST_NOTIFICATIONS to PermissionInfo(
            title = "Notifications",
            description = "Allows InfoCaller to send call alerts, spam warnings, and identification results as notifications.",
            icon = Icons.Default.Notifications
        ),
        Manifest.permission.SYSTEM_ALERT_WINDOW to PermissionInfo(
            title = "Display Over Apps",
            description = "Allows InfoCaller to show caller ID overlay on top of other apps during incoming calls.",
            icon = Icons.Default.Layers
        ),
        Manifest.permission.RECORD_AUDIO to PermissionInfo(
            title = "Audio Recording",
            description = "Allows InfoCaller to record phone calls for later reference or transcription.",
            icon = Icons.Default.Mic
        ),
        Manifest.permission.RECEIVE_SMS to PermissionInfo(
            title = "SMS Access",
            description = "Allows InfoCaller to automatically read OTP/verification codes for Truecaller login.",
            icon = Icons.Default.Message
        ),
        Manifest.permission.READ_SMS to PermissionInfo(
            title = "SMS History",
            description = "Allows InfoCaller to read SMS history for verification codes and spam detection.",
            icon = Icons.Default.Message
        ),
        Manifest.permission.BLUETOOTH_CONNECT to PermissionInfo(
            title = "Bluetooth Connect",
            description = "Allows InfoCaller to connect to Bluetooth devices for call audio routing.",
            icon = Icons.Default.Bluetooth
        ),
        Manifest.permission.ACCESS_FINE_LOCATION to PermissionInfo(
            title = "Precise Location",
            description = "Allows InfoCaller to provide location-based caller identification and spam reporting.",
            icon = Icons.Default.LocationOn
        ),
        Manifest.permission.ACCESS_COARSE_LOCATION to PermissionInfo(
            title = "Approximate Location",
            description = "Allows InfoCaller to provide general location-based caller identification.",
            icon = Icons.Default.LocationOn
        )
    )

    fun get(permission: String): PermissionInfo {
        return explanations[permission] ?: PermissionInfo(
            title = "Permission Required",
            description = "This permission is needed for InfoCaller to function properly.",
            icon = Icons.Default.Info
        )
    }

    fun getAll(permissions: Array<String>): List<PermissionInfo> {
        return permissions.map { get(it) }.distinctBy { it.title }
    }
}

data class PermissionInfo(
    val title: String,
    val description: String,
    val icon: ImageVector
)

/**
 * Reusable permission rationale dialog that shows explanation before requesting permission
 */
@Composable
fun PermissionRationaleDialog(
    permissions: Array<String>,
    onAccept: () -> Unit,
    onDeny: () -> Unit,
    isShowing: Boolean,
    onDismiss: () -> Unit
) {
    if (!isShowing) return

    val permissionInfos = PermissionExplanations.getAll(permissions)
    val primaryPermission = permissionInfos.firstOrNull()

    AlertDialog(
        onDismissRequest = {
            onDismiss()
            onDeny()
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                if (primaryPermission != null) {
                    Icon(
                        imageVector = primaryPermission.icon,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (permissionInfos.size == 1) {
                    Text(
                        primaryPermission?.title ?: "Permission Required",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        primaryPermission?.description ?: "This permission is needed for InfoCaller to function properly.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                } else {
                    Text(
                        "Multiple Permissions Required",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "InfoCaller needs the following permissions to work properly:",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        permissionInfos.forEach { info ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = info.icon,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        info.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White
                                    )
                                    Text(
                                        info.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 24.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onAccept()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = Primary)
            ) {
                Text("Accept", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onDeny()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.7f))
            ) {
                Text("Not Now")
            }
        }
    )
}
