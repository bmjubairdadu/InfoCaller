package com.infocaller.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.infocaller.app.data.local.entity.LocalContactEntity
import com.infocaller.app.domain.model.Contact
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.dialogs.AddContactDialog
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.components.PermissionEmptyState
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

@Composable
fun ContactsScreen(
    viewModel: CallerViewModel,
    onNavigateToDetails: (String) -> Unit,
    innerPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val activity = context as Activity
    var hasPermission by remember { 
        mutableStateOf(PermissionManager.hasPermissions(context, PermissionManager.CONTACTS_PERMISSIONS)) 
    }
    var showRationale by remember { mutableStateOf(value = false) }
    
    // STAGE 3: Contextual Contacts Permission (READ/WRITE)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermission = results.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            if (PermissionManager.shouldShowRationale(activity, PermissionManager.CONTACTS_PERMISSIONS)) {
                showRationale = true
            } else {
                launcher.launch(PermissionManager.CONTACTS_PERMISSIONS)
            }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Contacts Permission", color = Color.White) },
            text = { Text("InfoCaller needs access to your contacts to show and manage them.", color = Color.White.copy(alpha = 0.7f)) },
            containerColor = Surface,
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        launcher.launch(PermissionManager.CONTACTS_PERMISSIONS)
                    }
                ) {
                    Text("Grant", color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.5f))
                }
            }
        )
    }

    val localContacts by viewModel.localContacts.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    val syncWorkInfo by androidx.work.WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkLiveData("ThrottledSync")
        .observeAsState()
    
    val isSyncing = remember(syncWorkInfo) {
        syncWorkInfo?.any { it.state == androidx.work.WorkInfo.State.RUNNING } == true
    }
    
    val filteredContacts = if (searchQuery.isEmpty()) {
        localContacts
    } else {
        localContacts.filter { (it.displayName.contains(searchQuery, ignoreCase = true)) || (it.phoneNumber.contains(searchQuery) == true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        if (!hasPermission) {
            PermissionEmptyState(
                title = "Contacts Permission",
                description = "To show and manage your contacts, InfoCaller needs access to your contacts list."
            ) {
                launcher.launch(PermissionManager.CONTACTS_PERMISSIONS)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 16.dp + innerPadding.calculateTopPadding(),
                    bottom = 16.dp + innerPadding.calculateBottomPadding() + 80.dp, // Extra for FAB
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Contacts",
                            style = MaterialTheme.typography.displayMedium,
                            color = Color.White
                        )
                        
                        IconButton(onClick = { viewModel.triggerThrottledSync(context) }) {
                            Icon(Icons.Default.Sync, contentDescription = "Sync", tint = Primary)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    if (isSyncing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            color = Primary,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search contacts...", color = Color.White.copy(alpha = 0.5f)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.1f),
                                        Color.White.copy(alpha = 0.05f)
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.White) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                }
                items(filteredContacts) { contact ->
                    ContactItem(contact, onClick = { onNavigateToDetails(contact.phoneNumber) })
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = innerPadding.calculateBottomPadding() + 24.dp, end = 24.dp)
                .size(64.dp)
                .shadow(12.dp, CircleShape),
            containerColor = Color.Transparent,
            elevation = FloatingActionButtonDefaults.elevation(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(colors = listOf(GradientStart, GradientEnd)),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Contact", tint = Color.White)
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            phoneNumber = "",
            onDismiss = { showAddDialog = false },
            onContactSaved = { 
                showAddDialog = false
                // Contacts will auto-refresh via ContentObserver
            }
        )
    }
}

@Composable
fun ContactItem(contact: LocalContactEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .glassy(radius = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(GradientStart.copy(alpha = 0.4f), GradientEnd.copy(alpha = 0.4f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = contact.whatsappProfilePic ?: com.infocaller.app.util.PhoneNumberUtils.getImageUrl(contact.phoneNumber),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = rememberVectorPainter(Icons.Default.Person),
                    error = rememberVectorPainter(Icons.Default.Person)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    if (contact.isBusiness) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(Primary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Business", fontSize = 10.sp, color = Primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    text = contact.phoneNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
                if (!contact.about.isNullOrBlank()) {
                    Text(
                        text = contact.about,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.4f),
                        maxLines = 1
                    )
                }
            }
            
            if (contact.isSynced) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Synced",
                    tint = Success,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
