package com.infocaller.app.ui.dialogs

import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.infocaller.app.data.repository.ContactEnrichmentService
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.LocationUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactBottomSheet(
    viewModel: CallerViewModel,
    phoneNumber: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onContactSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    var inputNumber by remember { mutableStateOf(phoneNumber) }
    var displayName by remember { mutableStateOf(initialName) }
    var suggestedPhotoUrl by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var isNameManuallyEdited by remember { mutableStateOf(false) }
    
    val enrichmentService = remember { 
        ContactEnrichmentService(context, database = (context.applicationContext as com.infocaller.app.InfoCallerApplication).database) 
    }
    
    val normalized = remember(inputNumber) { com.infocaller.app.util.PhoneNumberUtils.normalize(inputNumber) }
    val enrichment by viewModel.getEnrichment(normalized).collectAsState(initial = null)
    val localContacts by viewModel.localContacts.collectAsState()
    val existingContact = remember(normalized, localContacts) { 
        localContacts.find { com.infocaller.app.util.PhoneNumberUtils.normalize(it.phoneNumber) == normalized } 
    }
    
    val lookupResult by viewModel.fullLookupResult.collectAsState()
    
    LaunchedEffect(normalized) {
        if (normalized.length >= 7) {
            viewModel.performFullLookup(normalized)
        }
    }

    LaunchedEffect(existingContact, enrichment, lookupResult) {
        if (!isNameManuallyEdited) {
            val contactName = existingContact?.displayName
            val enrichedName = enrichment?.publicName
            val providerName = lookupResult?.name
            
            if (contactName != null && !ContactUtils.isPlaceholderName(contactName)) {
                displayName = contactName
            } else if (enrichedName != null && !ContactUtils.isPlaceholderName(enrichedName)) {
                displayName = enrichedName
            } else if (providerName != null && !ContactUtils.isPlaceholderName(providerName)) {
                displayName = providerName
            }
        }
        
        if (suggestedPhotoUrl == null) {
            suggestedPhotoUrl = existingContact?.photoUri ?: enrichment?.profileImageUrl ?: lookupResult?.imageUrl
        }
    }

    val accounts = remember(context) { ContactUtils.getContactAccounts(context) }
    var selectedAccount by remember { mutableStateOf(accounts.firstOrNull()) }
    var showAccountPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (existingContact != null) "Update Contact" else "Save Contact",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (suggestedPhotoUrl != null) {
                    AsyncImage(
                        model = suggestedPhotoUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = ContactUtils.getInitials(displayName),
                        style = MaterialTheme.typography.displayMedium,
                        color = Primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = displayName,
                onValueChange = { 
                    displayName = it
                    isNameManuallyEdited = true
                    errorMessage = null 
                },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = inputNumber,
                onValueChange = { 
                    inputNumber = it
                    errorMessage = null 
                },
                label = { Text("Phone Number") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                onClick = { showAccountPicker = true },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AccountBalanceWallet, null, tint = Primary)
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Save to Account", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(selectedAccount?.name ?: "Local Phone", style = MaterialTheme.typography.bodyLarge)
                    }
                    Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val res = lookupResult
                val enr = enrichment
                InfoBadge("WhatsApp", enr?.whatsappStatus == "CONFIRMED" || res?.socialProfiles?.any { it.platform == "WhatsApp" } == true)
                InfoBadge("Location", enr?.city != null || res?.city != null)
                InfoBadge("Carrier", enr?.carrier != null || res?.carrier != null)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (displayName.isBlank()) {
                        errorMessage = "Please enter a name"
                        return@Button
                    }
                    isSaving = true
                    scope.launch {
                        val success = enrichmentService.saveContactFast(
                            phoneNumber = inputNumber,
                            displayName = displayName,
                            photoUrl = suggestedPhotoUrl,
                            accountName = selectedAccount?.accountName,
                            accountType = selectedAccount?.accountType,
                            lookupResult = lookupResult
                        )
                        if (success) {
                            onContactSaved()
                            onDismiss()
                        } else {
                            errorMessage = "Failed to save contact"
                            isSaving = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (existingContact != null) "UPDATE CONTACT" else "SAVE CONTACT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }

            if (errorMessage != null) {
                Text(errorMessage!!, color = Error, modifier = Modifier.padding(top = 8.dp))
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showAccountPicker) {
        AlertDialog(
            onDismissRequest = { showAccountPicker = false },
            title = { Text("Select Account") },
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                Column {
                    accounts.forEach { account ->
                        ListItem(
                            headlineContent = { Text(account.name) },
                            supportingContent = { Text(account.typeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            leadingContent = { 
                                val icon = when {
                                    account.typeLabel.contains("Google") -> Icons.Default.AccountCircle
                                    account.typeLabel.contains("SIM") -> Icons.Default.SimCard
                                    else -> Icons.Default.PhoneAndroid
                                }
                                Icon(icon, null, tint = Primary) 
                            },
                            modifier = Modifier.clickable { 
                                selectedAccount = account
                                showAccountPicker = false
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountPicker = false }) {
                    Text("Close", color = Primary)
                }
            }
        )
    }
}

@Composable
fun InfoBadge(label: String, available: Boolean) {
    Surface(
        color = if (available) Success.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (available) Success.copy(alpha = 0.3f) else Color.Transparent)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (available) Icon(Icons.Default.Check, null, tint = Success, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (available) Success else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
