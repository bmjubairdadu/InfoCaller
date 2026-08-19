package com.infocaller.app.ui.dialogs

import android.accounts.AccountManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.infocaller.app.ui.theme.glassy
import com.infocaller.app.util.SimManager
import com.infocaller.app.ui.components.InfoCallerLoading

data class SaveAccount(
    val name: String,
    val type: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun SaveLocationDialog(
    onDismiss: () -> Unit,
    onAccountSelected: (SaveAccount) -> Unit
) {
    val context = LocalContext.current
    var accounts by remember { mutableStateOf<List<SaveAccount>>(emptyList()) }
    
    LaunchedEffect(Unit) {
        accounts = getAvailableAccounts(context)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .glassy(radius = 24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Save Contact To",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(accounts) { account ->
                        AccountItem(account) {
                            onAccountSelected(account)
                        }
                    }
                }
                
                if (accounts.isEmpty()) {
                    InfoCallerLoading(size = 48.dp, text = "Loading Accounts...")
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun AccountItem(account: SaveAccount, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(account.icon, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(account.label, color = Color.White, fontWeight = FontWeight.Medium)
                if (account.name.isNotEmpty() && account.name != account.label) {
                    Text(account.name, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private suspend fun getAvailableAccounts(context: Context): List<SaveAccount> {
    val list = mutableListOf<SaveAccount>()
    
    // Phone storage
    list.add(SaveAccount("", "", "Phone Storage", Icons.Default.Smartphone))
    
    // SIM accounts
    try {
        val simInfos = SimManager.getSimInfos(context)
        simInfos.forEach { sim ->
            list.add(SaveAccount(sim.displayName, "com.android.sim", "SIM ${sim.slotIndex + 1}", Icons.Default.SdCard))
        }
    } catch (e: Exception) {
        // Fallback
        list.add(SaveAccount("SIM 1", "com.android.sim", "SIM 1", Icons.Default.SdCard))
    }
    
    // Google/System accounts
    try {
        val am = AccountManager.get(context)
        val accounts = am.getAccountsByType("com.google")
        accounts.forEach { account ->
            list.add(SaveAccount(account.name, account.type, "Google Account", Icons.Default.AccountCircle))
        }
    } catch (e: Exception) {}
    
    return list
}
