package com.infocaller.app.ui.dialogs

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.infocaller.app.data.repository.ContactEnrichmentService
import com.infocaller.app.ui.theme.Background
import com.infocaller.app.ui.theme.GradientEnd
import com.infocaller.app.ui.theme.GradientStart
import com.infocaller.app.ui.theme.glassy
import com.infocaller.app.ui.dialogs.SaveAccount
import com.infocaller.app.ui.dialogs.SaveLocationDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun AddContactDialog(
    phoneNumber: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onContactSaved: () -> Unit
) {
    val context = LocalContext.current
    var inputNumber by remember { mutableStateOf(phoneNumber) }
    var displayName by remember { mutableStateOf(initialName) }
    var isEnriching by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var showProgress by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var showLocationSelector by remember { mutableStateOf(false) }
    
    val enrichmentService = remember { ContactEnrichmentService(context) }
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    val onSaveWithAccount: (SaveAccount) -> Unit = { account ->
        isEnriching = true
        showProgress = true
        progressText = "Starting..."
        errorMessage = null
        showLocationSelector = false

        coroutineScope.launch {
            val success = enrichmentService.enrichAndSaveContact(
                phoneNumber = inputNumber,
                displayName = displayName,
                accountName = if (account.name.isEmpty()) null else account.name,
                accountType = if (account.type.isEmpty()) null else account.type,
                onProgress = { text ->
                    progressText = text
                }
            )

            isEnriching = false
            showProgress = false

            if (success) {
                onContactSaved()
                onDismiss()
            } else {
                errorMessage = "Failed to save contact. ${progressText}"
            }
        }
    }

    val onSaveClick: () -> Unit = {
        if (displayName.isBlank()) {
            errorMessage = "Please enter a name"
        } else {
            showLocationSelector = true
        }
    }

    val onBasicSaveClick: () -> Unit = {
        if (displayName.isBlank()) {
            errorMessage = "Please enter a name"
        } else {
            showLocationSelector = true
        }
    }
    
    if (showLocationSelector) {
        SaveLocationDialog(
            onDismiss = { showLocationSelector = false },
            onAccountSelected = { onSaveWithAccount(it) }
        )
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Save Contact",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.7f))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (phoneNumber.isEmpty()) {
                    OutlinedTextField(
                        value = inputNumber,
                        onValueChange = { inputNumber = it; errorMessage = null },
                        label = { Text("Phone Number", color = Color.White.copy(alpha = 0.6f)) },
                        placeholder = { Text("Enter number", color = Color.White.copy(alpha = 0.4f)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .glassy(radius = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = GradientStart,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            cursorColor = Color.White
                        )
                    )
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; errorMessage = null },
                    label = { Text("Contact Name", color = Color.White.copy(alpha = 0.6f)) },
                    placeholder = { Text("Enter name", color = Color.White.copy(alpha = 0.4f)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .glassy(radius = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = GradientStart,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        disabledBorderColor = Color.White.copy(alpha = 0.1f),
                        cursorColor = Color.White,
                        focusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
                        unfocusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
                        focusedLabelColor = Color.White.copy(alpha = 0.6f),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                    )
                )
                
                errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = Color(0xFFEF4444),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp).padding(top = 4.dp)
                    )
                }
                
                if (showProgress) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(vertical = 12.dp)
                                .glassy(radius = 12.dp, blur = 4.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = GradientStart,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = progressText,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isEnriching) {
                Button(
                    onClick = onSaveClick,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Save with Enrichment",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        dismissButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!isEnriching) {
                    OutlinedButton(
                        onClick = onBasicSaveClick,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Basic Save", color = Color.White)
                    }
                }
                
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White.copy(alpha = 0.7f)
                        ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    )
}