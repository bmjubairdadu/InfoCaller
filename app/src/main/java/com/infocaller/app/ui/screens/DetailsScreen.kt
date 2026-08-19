package com.infocaller.app.ui.screens

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.ui.components.InfoCallerLoading
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.viewmodel.SearchUiState
import com.infocaller.app.util.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    viewModel: CallerViewModel,
    onBack: () -> Unit,
    onMakeCall: (String) -> Unit
) {
    val uiState by viewModel.searchResult.collectAsState()
    val blocklist by viewModel.blocklist.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val caller = (uiState as? SearchUiState.Success)?.caller
    
    val phoneNumber = remember(uiState) {
        when (uiState) {
            is SearchUiState.Success -> (uiState as SearchUiState.Success).caller.phoneNumber
            else -> viewModel.dialerInput.value
        }
    }
    
    val enrichment by viewModel.getEnrichment(phoneNumber).collectAsState(initial = null)
    val isBlocked = blocklist.contains(phoneNumber)
    val contactsList by viewModel.contacts.collectAsState()
    
    val isContact = contactsList.any { it.phoneNumber == phoneNumber }
    val contact = contactsList.find { it.phoneNumber == phoneNumber }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isContact) "Contact Details" else "Caller Identity") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        if (isBlocked) viewModel.unblockNumber(phoneNumber)
                        else viewModel.blockNumber(phoneNumber)
                    }) {
                        Icon(
                            imageVector = if (isBlocked) Icons.Default.Block else Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = if (isBlocked) Error else Success
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Circular Call Button
                    Surface(
                        onClick = { onMakeCall(phoneNumber) },
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(12.dp, CircleShape),
                        shape = CircleShape,
                        color = Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.linearGradient(colors = listOf(GradientStart, GradientEnd))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Call, "Call", tint = Color.Black, modifier = Modifier.size(32.dp))
                        }
                    }
                    
                    OutlinedButton(
                        onClick = { 
                            com.infocaller.app.util.PhoneNumberUtils.sendSms(context, phoneNumber)
                        },
                        modifier = Modifier.height(56.dp).weight(1f).padding(start = 32.dp),
                        shape = RoundedCornerShape(28.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Primary.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Message, null, tint = Primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Message", color = Primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { innerPadding ->
        if (phoneNumber.isBlank() && caller == null) {
            InfoCallerLoading(text = "Identifying...")
        } else {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Profile Header
                val displayName = contact?.displayName ?: enrichment?.publicName ?: caller?.displayName ?: "Unknown"
                
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .shadow(12.dp, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val photoUrl = contact?.photoUri ?: enrichment?.profileImageUrl
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
                        Text(initials, style = MaterialTheme.typography.displayLarge, color = Primary)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                if (!enrichment?.alternateName.isNullOrBlank() && enrichment?.alternateName != displayName) {
                    Text(
                        text = "aka ${enrichment!!.alternateName}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                Text(
                    text = com.infocaller.app.util.PhoneNumberUtils.formatAsYouType(phoneNumber),
                    style = MaterialTheme.typography.titleLarge,
                    color = Primary,
                    fontWeight = FontWeight.SemiBold
                )

                if (!enrichment?.about.isNullOrBlank()) {
                    Text(
                        text = enrichment!!.about!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp).padding(horizontal = 48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Info Cards
                DetailSection("Public Information") {
                    val location = LocationUtils.formatCallerLocation(enrichment?.city, enrichment?.region, enrichment?.country)
                    DetailRow(Icons.Default.LocationOn, "Location", location.ifBlank { "Unknown" })
                    
                    enrichment?.timezone?.let {
                        DetailRow(Icons.Default.Schedule, "Timezone", it)
                    }
                    
                    DetailRow(Icons.Default.CellTower, "Carrier", enrichment?.carrier ?: caller?.carrier ?: "Unknown")
                    
                    if (enrichment?.isBusiness == true) {
                        DetailRow(Icons.Default.Business, "Type", "Verified Business")
                    }
                    
                    enrichment?.email?.let {
                        DetailRow(Icons.Default.Email, "Email", it)
                    }

                    DetailRow(Icons.Default.Info, "Source", enrichment?.source ?: "Direct Intelligence")
                    
                    if (enrichment?.lastChecked != null && enrichment?.lastChecked != 0L) {
                        val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(enrichment!!.lastChecked))
                        DetailRow(Icons.Default.Update, "Last Updated", date)
                    }
                }

                val socialProfiles = remember(enrichment?.socialProfilesJson) {
                    SocialUtils.fromJson(enrichment?.socialProfilesJson)
                }
                
                if (socialProfiles.isNotEmpty()) {
                    DetailSection("Social Presence") {
                        Column(modifier = Modifier.padding(8.dp)) {
                            socialProfiles.filter { !it.profileUrl.isNullOrBlank() }.forEach { profile ->
                                SocialRow(profile)
                            }
                        }
                    }
                }

                if ((enrichment?.spamScore ?: 0) > 0) {
                    DetailSection("Reputation") {
                        val score = enrichment?.spamScore ?: 0
                        val color = if (score < 30) Success else if (score < 70) Warning else Error
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Spam Score", style = MaterialTheme.typography.bodyLarge)
                                Text(enrichment?.spamStatus ?: "Analyzing...", style = MaterialTheme.typography.bodySmall, color = color)
                            }
                            Text(
                                text = "$score%",
                                style = MaterialTheme.typography.displaySmall,
                                color = color,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun SocialRow(profile: SocialProfile) {
    val context = LocalContext.current
    val isConfirmed = SocialUtils.isConfirmed(profile)
    
    val icon = when (profile.platform.lowercase()) {
        "whatsapp" -> Icons.AutoMirrored.Filled.Chat
        "telegram" -> Icons.AutoMirrored.Filled.Send
        "facebook" -> Icons.Default.Facebook
        "instagram" -> Icons.Default.CameraAlt
        "linkedin" -> Icons.Default.Link
        else -> Icons.Default.Link
    }

    if (!profile.profileUrl.isNullOrBlank()) {
        ListItem(
            headlineContent = { Text(profile.platform) },
            supportingContent = { Text(profile.profileUrl, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            leadingContent = { 
                Icon(
                    icon, 
                    null, 
                    tint = if (isConfirmed) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                ) 
            },
            trailingContent = {
                if (isConfirmed) {
                    Icon(Icons.Default.CheckCircle, null, tint = Success, modifier = Modifier.size(20.dp))
                }
            },
            modifier = Modifier.clickable { SocialUtils.openSocialProfile(context, profile) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
