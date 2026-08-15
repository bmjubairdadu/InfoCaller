package com.infocaller.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.infocaller.app.domain.model.CallLogEntry
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.viewmodel.SyncState
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.components.PermissionEmptyState
import com.infocaller.app.util.ifNullOrBlank
import com.infocaller.app.util.PhoneNumberUtils
import java.text.SimpleDateFormat
import java.util.*
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

@Composable
fun RecentsScreen(
    viewModel: CallerViewModel,
    onNavigateToDetails: (String) -> Unit,
    innerPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val activity = context as Activity
    var hasPermission by remember { 
        mutableStateOf(PermissionManager.hasPermissions(context, PermissionManager.CALL_LOG_PERMISSIONS)) 
    }
    var showRationale by remember { mutableStateOf(false) }
    
    // STAGE 4: Contextual Call Log Permission (READ_CALL_LOG)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermission = results.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            if (PermissionManager.shouldShowRationale(activity, PermissionManager.CALL_LOG_PERMISSIONS)) {
                showRationale = true
            } else {
                launcher.launch(PermissionManager.CALL_LOG_PERMISSIONS)
            }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Recents Permission", color = Color.White) },
            text = { Text("InfoCaller needs access to your call history to show recent activity.", color = Color.White.copy(alpha = 0.7f)) },
            containerColor = Surface,
            confirmButton = {
                TextButton(onClick = { 
                    showRationale = false
                    launcher.launch(PermissionManager.CALL_LOG_PERMISSIONS) 
                }) {
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

    val recentCalls by viewModel.recentCalls.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: All, 1: Missed, 2: Incoming, 3: Outgoing
    var searchQuery by remember { mutableStateOf("") }

    val filteredCalls = remember(recentCalls, selectedTab, searchQuery) {
        val tabFiltered = when (selectedTab) {
            1 -> recentCalls.filter { it.type == 3 } // MISSED_TYPE
            2 -> recentCalls.filter { it.type == 1 } // INCOMING_TYPE
            3 -> recentCalls.filter { it.type == 2 } // OUTGOING_TYPE
            else -> recentCalls
        }
        
        if (searchQuery.isEmpty()) {
            tabFiltered
        } else {
            tabFiltered.filter { 
                it.number.contains(searchQuery) || (it.name?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        if (!hasPermission) {
            PermissionEmptyState(
                title = "Recents Permission",
                description = "To show your call history, InfoCaller needs access to your call logs.",
                onGrant = {
                    launcher.launch(PermissionManager.CALL_LOG_PERMISSIONS)
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 16.dp + innerPadding.calculateTopPadding(),
                    bottom = 16.dp + innerPadding.calculateBottomPadding(),
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Activity",
                            style = MaterialTheme.typography.displayMedium,
                            color = Color.White
                        )
                        
                        TextButton(onClick = { viewModel.clearAllCallLogs() }) {
                            Text("Clear All", color = Primary)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    CallStatsHeader(recentCalls)
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search activity...", color = Color.White.copy(alpha = 0.5f)) },
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

                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = Primary,
                        edgePadding = 0.dp,
                        divider = {},
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = Primary
                            )
                        }
                    ) {
                        listOf("All", "Missed", "Incoming", "Outgoing").forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(
                                        text = title,
                                        color = if (selectedTab == index) Color.White else Color.White.copy(alpha = 0.5f),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                            )
                        }
                    }

                    if (syncState is SyncState.Syncing) {
                        val progress = (syncState as SyncState.Syncing).progress
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                                .glassy(radius = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Enriching Contacts...", color = Color.White, style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Primary,
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "Recent History",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                items(filteredCalls) { entry ->
                    CallLogItem(entry, onClick = { onNavigateToDetails(entry.number) })
                }
            }
        }
    }
}

@Composable
fun CallStatsHeader(calls: List<CallLogEntry>) {
    val totalCalls = calls.size
    val incoming = calls.count { it.type == 1 } // CallLog.Calls.INCOMING_TYPE
    val outgoing = calls.count { it.type == 2 } // CallLog.Calls.OUTGOING_TYPE

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        item { StatCard("Total", totalCalls.toString(), Icons.Default.Call, Primary) }
        item { StatCard("Incoming", incoming.toString(), Icons.AutoMirrored.Filled.CallReceived, Success) }
        item { StatCard("Outgoing", outgoing.toString(), Icons.AutoMirrored.Filled.CallMade, Secondary) }
    }
}

@Composable
fun StatCard(label: String, value: String, icon: ImageVector, color: Color) {
    Card(
        modifier = Modifier
            .width(130.dp)
            .height(110.dp)
            .glassy(radius = 24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Column {
                Text(text = value, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun CallLogItem(entry: CallLogEntry, onClick: () -> Unit) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val dateString = dateFormat.format(Date(entry.date))
    val durationText = if (entry.duration > 0) "${entry.duration}s" else "Missed"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .glassy(radius = 20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(color = Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                val photoUrl = remember(entry.number) {
                    PhoneNumberUtils.getImageUrl(entry.number)
                }

                AsyncImage(
                    model = photoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = rememberVectorPainter(
                        when(entry.type) {
                            1 -> Icons.AutoMirrored.Filled.CallReceived
                            2 -> Icons.AutoMirrored.Filled.CallMade
                            3 -> Icons.AutoMirrored.Filled.CallMissed
                            else -> Icons.Default.Call
                        }
                    ),
                    error = rememberVectorPainter(
                        when(entry.type) {
                            1 -> Icons.AutoMirrored.Filled.CallReceived
                            2 -> Icons.AutoMirrored.Filled.CallMade
                            3 -> Icons.AutoMirrored.Filled.CallMissed
                            else -> Icons.Default.Call
                        }
                    )
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name.ifNullOrBlank { entry.number.ifBlank { "Unknown" } },
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateString,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "• $durationText",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.duration > 0) Success else Error
                    )
                }
            }
            
            IconButton(
                onClick = onClick,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = "Details", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}
