package com.infocaller.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.infocaller.app.ui.dialogs.ContributionConsentDialog
import com.infocaller.app.data.local.ContributionConsentStore
import com.infocaller.app.data.local.ContributionPolicy
import com.infocaller.app.worker.ContributionWorker
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.components.PermissionEmptyState
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.findActivity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    viewModel: CallerViewModel,
    onNavigateToDetails: (String) -> Unit,
    onMakeCall: (String) -> Unit = {},
    innerPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    // Never hard-cast: previews/dialogs/wrapped contexts are not Activities.
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()

    // First-open contribution consent: show once while decision is UNASKED.
    var showConsent by remember {
        mutableStateOf(
            ContributionConsentStore.getDecision(context) == ContributionPolicy.Decision.UNASKED
        )
    }

    if (showConsent) {
        ContributionConsentDialog(
            onAccept = {
                ContributionConsentStore.setAccepted(context)
                showConsent = false
                ContributionWorker.scheduleOnConsent(context)
            },
            onDecline = {
                ContributionConsentStore.setDeclined(context)
                showConsent = false
                ContributionWorker.cancel(context)
            }
        )
    }

    var hasPermission by remember {
        mutableStateOf(PermissionManager.hasPermissions(context, PermissionManager.CONTACTS_PERMISSIONS))
    }
    var showRationale by remember { mutableStateOf(value = false) }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermission = results.values.all { it }
        // The contacts flow closes itself when permission is missing; restart it
        // now so the list populates immediately instead of staying empty.
        if (hasPermission) viewModel.refreshDeviceData()
    }
    // One-shot, contextual, minimal: request ONLY the still-missing contact
    // permissions when this tab is first opened — never a bulk set. The
    // contribution (GitHub-share) decision is the separate consent dialog above.
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    fun requestMissingContacts() {
        val missing = PermissionManager.missingPermissions(context, PermissionManager.CONTACTS_PERMISSIONS)
        if (missing.isEmpty()) { hasPermission = true; return }
        val act = activity
        if (act != null && PermissionManager.shouldShowRationale(act, missing)) showRationale = true
        else launcher.launch(missing)
    }
    LaunchedEffect(hasPermission) {
        if (!hasPermission && !permissionRequested) {
            permissionRequested = true
            requestMissingContacts()
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
                    requestMissingContacts() 
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

    val enrichedContacts by viewModel.enrichedContacts.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var contactToDelete by remember { mutableStateOf<LocalContactEntity?>(null) }
    // Dial Pad lives here (bottom-right FAB). Add-contact flows through the
    // dial pad's own auto-populated sheet, so no separate "+" button is needed.
    var showDialPad by rememberSaveable { mutableStateOf(false) }

    // WorkManager may be uninitialized on some ROMs — getInstance() throws and
    // would crash composition. Fall back to an empty flow (no sync indicator).
    val workInfos by remember {
        try {
            androidx.work.WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow("ThrottledSync")
        } catch (_: Exception) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())
    
    val isSyncing = remember(workInfos) {
        workInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING }
    }
    
    val filteredContacts = remember(enrichedContacts, searchQuery) {
        if (searchQuery.isEmpty()) {
            enrichedContacts
        } else {
            enrichedContacts.filter { 
                it.contact.displayName.contains(searchQuery, ignoreCase = true) || 
                it.contact.phoneNumber.contains(searchQuery) 
            }
        }
    }

    GlassyBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Surface(
                    color = Color.Black.copy(alpha = 0.3f),
                    modifier = Modifier.glassy(blur = 20.dp, radius = 0.dp)
                ) {
                    Column {
                        TopAppBar(
                            title = {
                                Text(
                                    "Contacts",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
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
                            ),
                            windowInsets = WindowInsets.statusBars
                        )
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search by name or number", color = Color.White.copy(alpha = 0.4f)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f)) },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White)
                                    }
                                }
                            } else null,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Primary.copy(alpha = 0.6f),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                            ),
                            singleLine = true
                        )
                    }
                }
            },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showDialPad = true },
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
                    Icon(Icons.Default.Dialpad, contentDescription = "Dial Pad", tint = Color.Black)
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
                    requestMissingContacts()
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (filteredContacts.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    if (searchQuery.isEmpty()) "No contacts yet" else "No matches for \"$searchQuery\"",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (searchQuery.isEmpty()) "Contacts you add will appear here."
                                    else "Try a different name or number.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    itemsIndexed(filteredContacts, key = { _, it -> it.contact.id }) { index, enriched ->
                        var showMenu by remember { mutableStateOf(false) }
                        val contact = enriched.contact
                        
                        val itemVisible = remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { itemVisible.value = true }
                        
                        AnimatedVisibility(
                            visible = itemVisible.value,
                            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 }
                        ) {
                            Box {
                                ContactItem(
                                    enriched = enriched,
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            viewModel.searchNumber(contact.phoneNumber)
                                            viewModel.triggerThrottledSync(context)
                                            onNavigateToDetails(contact.phoneNumber)
                                        },
                                        onLongClick = { showMenu = true }
                                    )
                                )
                                
                                DropdownMenu(
                                    expanded = showMenu, 
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.background(Surface).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("View Details") },
                                        onClick = { onNavigateToDetails(contact.phoneNumber); showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Info, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Call") },
                                        onClick = { viewModel.showSimSelection(contact.phoneNumber); showMenu = false },
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
                                    DropdownMenuItem(
                                        text = { Text("Edit Contact") },
                                        onClick = { 
                                            ContactUtils.editContact(context, contact.phoneNumber)
                                            showMenu = false 
                                        },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share") },
                                        onClick = { 
                                            val sendIntent: android.content.Intent = android.content.Intent().apply {
                                                action = android.content.Intent.ACTION_SEND
                                                putExtra(android.content.Intent.EXTRA_TEXT, "Contact: ${contact.displayName}\nPhone: ${contact.phoneNumber}")
                                                type = "text/plain"
                                            }
                                            context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                                            showMenu = false 
                                        },
                                        leadingIcon = { Icon(Icons.Default.Share, null) }
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.White.copy(alpha = 0.1f))
                                    DropdownMenuItem(
                                        text = { Text("Delete Contact", color = Error) },
                                        onClick = { 
                                            contactToDelete = contact
                                            showMenu = false 
                                        },
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Error) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (contactToDelete != null) {
        AlertDialog(
            onDismissRequest = { contactToDelete = null },
            title = { Text("Delete Contact") },
            text = { Text("Are you sure you want to delete ${contactToDelete!!.displayName}?") },
            confirmButton = {
                TextButton(onClick = { 
                    val number = contactToDelete!!.phoneNumber
                    scope.launch {
                        viewModel.deleteContact(number)
                    }
                    contactToDelete = null
                }) {
                    Text("Delete", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDialPad) {
        DialPadBottomSheet(
            viewModel = viewModel,
            onCall = { number ->
                showDialPad = false
                onMakeCall(number)
            },
            onDismiss = { showDialPad = false }
        )
    }
}
}

@Composable
fun ContactItem(enriched: com.infocaller.app.data.local.model.EnrichedContact, modifier: Modifier = Modifier) {
    val contact = enriched.contact
    val enrichment = enriched.enrichment
    
    val displayName = remember(contact.displayName, enrichment?.publicName) {
        if (ContactUtils.isPlaceholderName(contact.displayName)) {
            enrichment?.publicName ?: contact.displayName
        } else {
            contact.displayName
        }
    }
    
    val photoUrl = contact.photoUri ?: enrichment?.profileImageUrl

    Box(
        modifier = modifier
            .fillMaxWidth()
            .glassy(radius = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f)),
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
                            fontSize = 18.sp
                        ),
                        color = Primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color.White)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = contact.phoneNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
            
            if (contact.isBusiness) {
                Icon(
                    Icons.Default.Business,
                    contentDescription = "Business",
                    tint = Primary.copy(alpha = 0.8f),
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
