package com.infocaller.app.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.infocaller.app.data.local.entity.LocalContactEntity
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.components.InfoCallerLoading
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.dialogs.AddContactBottomSheet
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.components.PermissionEmptyState
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.PhoneNumberUtils

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
            title = { Text("Contacts Permission") },
            text = { Text("InfoCaller needs access to your contacts to show and manage them.") },
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                TextButton(onClick = { 
                    showRationale = false
                    launcher.launch(PermissionManager.CONTACTS_PERMISSIONS) 
                }) {
                    Text("Grant", color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text("Cancel")
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
        localContacts.filter { (it.displayName.contains(searchQuery, ignoreCase = true)) || (it.phoneNumber.contains(searchQuery)) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                shadowElevation = 4.dp
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = {
                            Text(
                                "Contacts",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        actions = {
                            IconButton(
                                onClick = { viewModel.triggerThrottledSync(context) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                if (isSyncing) {
                                    InfoCallerLoading(size = 24.dp)
                                } else {
                                    Icon(Icons.Default.Sync, contentDescription = "Sync", tint = Primary)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent
                        )
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search by name or number") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        } else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary
                        ),
                        singleLine = true
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp)
                    .size(56.dp)
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
                    Icon(Icons.Default.Add, contentDescription = "Add Contact", tint = Color.Black)
                }
            }
        }
    ) { screenPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = screenPadding.calculateTopPadding())
                .padding(bottom = innerPadding.calculateBottomPadding())
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
                        top = 16.dp,
                        bottom = 100.dp, 
                        start = 16.dp,
                        end = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredContacts, key = { it.id }) { contact ->
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            ContactItem(
                                contact = contact,
                                viewModel = viewModel,
                                modifier = Modifier.combinedClickable(
                                    onClick = { onNavigateToDetails(contact.phoneNumber) },
                                    onLongClick = { showMenu = true }
                                )
                            )
                            
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Call") },
                                    onClick = { viewModel.searchNumber(contact.phoneNumber); onNavigateToDetails(contact.phoneNumber); showMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Call, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Message") },
                                    onClick = { PhoneNumberUtils.sendSms(context, contact.phoneNumber); showMenu = false },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Message, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy Number") },
                                    onClick = { 
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Phone Number", contact.phoneNumber))
                                        showMenu = false 
                                    },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactBottomSheet(
            viewModel = viewModel,
            phoneNumber = "",
            onDismiss = { showAddDialog = false },
            onContactSaved = { 
                showAddDialog = false
            }
        )
    }
}

@Composable
fun ContactItem(contact: LocalContactEntity, viewModel: CallerViewModel, modifier: Modifier = Modifier) {
    val enrichment by viewModel.getEnrichment(contact.phoneNumber).collectAsState(initial = null)
    
    val displayName = remember(contact.displayName, enrichment?.publicName) {
        if (ContactUtils.isPlaceholderName(contact.displayName)) {
            enrichment?.publicName ?: contact.displayName
        } else {
            contact.displayName
        }
    }
    
    val photoUrl = contact.photoUri ?: enrichment?.profileImageUrl

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 10.dp, horizontal = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (photoUrl != null) {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = rememberVectorPainter(Icons.Default.Person),
                        placeholder = rememberVectorPainter(Icons.Default.Person)
                    )
                } else {
                    val initials = ContactUtils.getInitials(displayName)
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = Primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = contact.phoneNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (contact.isBusiness) {
                Icon(
                    Icons.Default.Business,
                    contentDescription = "Business",
                    tint = Primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp).padding(end = 8.dp)
                )
            }

            if (contact.isSynced) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Synced",
                    tint = Success.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
