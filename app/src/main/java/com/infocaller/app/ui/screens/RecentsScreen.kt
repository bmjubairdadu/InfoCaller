package com.infocaller.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.infocaller.app.domain.model.CallLogEntry
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.components.PermissionEmptyState
import com.infocaller.app.util.ifNullOrBlank
import com.infocaller.app.util.PhoneNumberUtils
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(
    viewModel: CallerViewModel,
    onNavigateToDetails: (String) -> Unit,
    innerPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    var hasPermission by remember { 
        mutableStateOf(PermissionManager.hasPermissions(context, PermissionManager.CALL_LOG_PERMISSIONS)) 
    }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results -> hasPermission = results.values.all { it } }
    // One-shot: never re-fire on rotation/recomposition.
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasPermission) {
        if (!hasPermission && !permissionRequested) {
            permissionRequested = true
            launcher.launch(PermissionManager.CALL_LOG_PERMISSIONS)
        }
    }

    val recentCalls by viewModel.recentCalls.collectAsState()
    val simInfos by viewModel.simInfos.collectAsState()
    
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val numbers = remember(recentCalls) { recentCalls.map { it.number }.distinct() }
    val enrichments by viewModel.getEnrichments(numbers).collectAsState(initial = emptyList())
    val enrichmentMap = remember(enrichments) { enrichments.associateBy { it.normalizedPhoneNumber } }

    val filteredCalls = remember(recentCalls, selectedTab, searchQuery) {
        val tabFiltered = when (selectedTab) {
            1 -> recentCalls.filter { it.type == 3 }
            2 -> recentCalls.filter { it.type == 1 }
            3 -> recentCalls.filter { it.type == 2 }
            else -> recentCalls
        }
        if (searchQuery.isEmpty()) tabFiltered
        else tabFiltered.filter { it.number.contains(searchQuery) || (it.name?.contains(searchQuery, ignoreCase = true) == true) }
    }

    GlassyBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Surface(color = Color.Black.copy(alpha = 0.3f), modifier = Modifier.glassy(blur = 20.dp, radius = 0.dp)) {
                    TopAppBar(
                        title = { Text("Activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White) },
                        actions = {
                            IconButton(onClick = { viewModel.clearAllCallLogs() }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All", tint = Color.White.copy(alpha = 0.6f))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        windowInsets = WindowInsets.statusBars
                    )
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { screenPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (!hasPermission) {
                    PermissionEmptyState(title = "Recents Permission", description = "To show your call history, InfoCaller needs access to your call logs.", onGrant = { launcher.launch(PermissionManager.CALL_LOG_PERMISSIONS) })
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = screenPadding.calculateTopPadding() + 16.dp, bottom = innerPadding.calculateBottomPadding() + 32.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            OutlinedTextField(
                                value = searchQuery, onValueChange = { searchQuery = it },
                                placeholder = { Text("Search activity...", color = Color.White.copy(alpha = 0.5f)) },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f)) },
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color.White.copy(alpha = 0.05f), unfocusedContainerColor = Color.White.copy(alpha = 0.05f), focusedBorderColor = Primary.copy(alpha = 0.5f), unfocusedBorderColor = Color.White.copy(alpha = 0.1f)),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            CallStatsHeader(recentCalls)
                            Spacer(modifier = Modifier.height(24.dp))
                            ScrollableTabRow(
                                selectedTabIndex = selectedTab, containerColor = Color.Transparent, contentColor = Primary, edgePadding = 0.dp, divider = {},
                                indicator = { tabPositions -> TabRowDefaults.SecondaryIndicator(modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]), color = Primary) }
                            ) {
                                listOf("All", "Missed", "Incoming", "Outgoing").forEachIndexed { index, title ->
                                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(text = title, color = if (selectedTab == index) Color.White else Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleSmall) })
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        itemsIndexed(filteredCalls, key = { _, it -> "${it.number}_${it.date}" }) { _, entry ->
                            val enrichment = enrichmentMap[entry.number]
                            val sim = simInfos.find { it.subscriptionId.toString() == entry.subscriptionId }
                            CallLogItem(entry, enrichment = enrichment, operatorLogoPath = sim?.localLogoPath, onClick = { onNavigateToDetails(entry.number) })
                        }
                        if (filteredCalls.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        if (searchQuery.isEmpty()) "No recent calls" else "No matches for \"$searchQuery\"",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        if (searchQuery.isEmpty()) "Calls you make or receive will appear here."
                                        else "Try a different name or number.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CallStatsHeader(calls: List<CallLogEntry>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        item { StatCard("Total", calls.size.toString(), Icons.Default.Call, Primary) }
        item { StatCard("Incoming", calls.count { it.type == 1 }.toString(), Icons.AutoMirrored.Filled.CallReceived, Success) }
        item { StatCard("Outgoing", calls.count { it.type == 2 }.toString(), Icons.AutoMirrored.Filled.CallMade, Secondary) }
        item { StatCard("Missed", calls.count { it.type == 3 }.toString(), Icons.AutoMirrored.Filled.CallMissed, Error) }
    }
}

@Composable
fun StatCard(label: String, value: String, icon: ImageVector, color: Color) {
    Box(modifier = Modifier.width(110.dp).height(90.dp).glassy(radius = 20.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Column {
                Text(text = value, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun CallLogItem(entry: CallLogEntry, enrichment: com.infocaller.app.data.local.entity.ContactEnrichmentEntity?, operatorLogoPath: String?, onClick: () -> Unit) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val dateString = dateFormat.format(Date(entry.date))
    val durationText = if (entry.duration > 0) "${entry.duration}s" else "Missed"

    Box(modifier = Modifier.fillMaxWidth().glassy(radius = 16.dp).clickable { onClick() }) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                AsyncImage(model = enrichment?.profileImageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, error = rememberVectorPainter(when(entry.type) { 1 -> Icons.AutoMirrored.Filled.CallReceived; 2 -> Icons.AutoMirrored.Filled.CallMade; 3 -> Icons.AutoMirrored.Filled.CallMissed; else -> Icons.Default.Call }))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = entry.name.ifNullOrBlank { enrichment?.publicName.ifNullOrBlank { PhoneNumberUtils.formatAsYouType(entry.number) } }, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = Color.White, modifier = Modifier.weight(1f, fill = false))
                    if (operatorLogoPath != null) {
                        Spacer(Modifier.width(8.dp))
                        AsyncImage(model = operatorLogoPath, contentDescription = null, modifier = Modifier.size(14.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = dateString, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "• $durationText", style = MaterialTheme.typography.bodySmall, color = if (entry.duration > 0) Success.copy(alpha = 0.7f) else Error.copy(alpha = 0.7f))
                }
            }
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = "Details", tint = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(14.dp))
        }
    }
}
