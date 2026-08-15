package com.infocaller.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.viewmodel.SearchUiState
import com.infocaller.app.util.OSINTManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    viewModel: CallerViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.searchResult.collectAsState()
    val blocklist by viewModel.blocklist.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    var isEditing by remember { mutableStateOf(value = false) }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Caller Details", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (uiState is SearchUiState.Success) {
                        val caller = (uiState as SearchUiState.Success).caller
                        val isBlocked = blocklist.contains(caller.phoneNumber)
                        
                        IconButton(onClick = { 
                            if (isBlocked) viewModel.unblockNumber(caller.phoneNumber)
                            else viewModel.blockNumber(caller.phoneNumber)
                        }) {
                            Icon(
                                imageVector = if (isBlocked) Icons.Default.Block else Icons.Default.VerifiedUser,
                                contentDescription = if (isBlocked) "Unblock" else "Block",
                                tint = if (isBlocked) Error else Success
                            )
                        }
                        
                        IconButton(onClick = { isEditing = !isEditing }) {
                            Icon(
                                imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (isEditing) "Save" else "Edit",
                                tint = Primary
                            )
                        }

                        if (contacts.any { it.phoneNumber == caller.phoneNumber }) {
                            IconButton(onClick = { 
                                viewModel.deleteContact(caller.phoneNumber)
                                onBack()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Contact",
                                    tint = Error
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxSize()
        ) {
            if (uiState is SearchUiState.Success) {
                val caller = (uiState as SearchUiState.Success).caller
                
                if (isEditing) {
                    EditCallerForm(caller) { updatedCaller ->
                        viewModel.updateCallerInfo(updatedCaller)
                        isEditing = false
                    }
                } else {
                    DisplayCallerDetails(caller)
                }
            } else {
                Text("No data available", color = Color.White)
            }
        }
    }
}

@Composable
private fun EditCallerForm(caller: Caller, onSave: (Caller) -> Unit) {
    var name by remember { mutableStateOf(caller.displayName ?: "") }
    var org by remember { mutableStateOf(caller.organization ?: "") }
    var country by remember { mutableStateOf(caller.country ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name", color = Color.White.copy(alpha = 0.6f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )
        OutlinedTextField(
            value = org,
            onValueChange = { org = it },
            label = { Text("Organization", color = Color.White.copy(alpha = 0.6f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )
        OutlinedTextField(
            value = country,
            onValueChange = { country = it },
            label = { Text("Country", color = Color.White.copy(alpha = 0.6f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )
        
        Button(
            onClick = { onSave(caller.copy(displayName = name, organization = org, country = country)) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text("Save Changes", color = Color.White)
        }
    }
}

@Composable
private fun DisplayCallerDetails(caller: Caller) {
    Text(text = caller.displayName ?: "No Name", style = MaterialTheme.typography.displayMedium, color = Color.White)
    Text(text = caller.phoneNumber, style = MaterialTheme.typography.titleLarge, color = Primary)
    
    Spacer(modifier = Modifier.height(32.dp))
    
    Card(
        modifier = Modifier.fillMaxWidth().glassy(radius = 24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            DetailItem(label = "Alias", value = caller.alias ?: "None")
            DetailItem(label = "Organization", value = caller.organization ?: "None")
            DetailItem(label = "Country", value = caller.country ?: "Unknown")
            DetailItem(label = "Carrier", value = caller.carrier ?: "Unknown")
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("Digital Footprint (No-API OSINT)", style = MaterialTheme.typography.titleSmall, color = Primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            val dorks = OSINTManager.generateDorkLinks(caller.phoneNumber)
            val context = LocalContext.current
            
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                dorks.forEach { dork ->
                    AssistChip(
                        onClick = { OSINTManager.openLink(context, dork.url) },
                        label = { Text(dork.title) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = Color.White.copy(alpha = 0.8f),
                            containerColor = Color.White.copy(alpha = 0.05f)
                        ),
                        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Color.White.copy(alpha = 0.1f))
                    )
                }
            }
        }
    }
    
    Spacer(modifier = Modifier.height(24.dp))
    
    Card(
        modifier = Modifier.fillMaxWidth().glassy(radius = 24.dp),
        colors = CardDefaults.cardColors(containerColor = Error.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
            Text(text = "Reputation Analysis", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            ReputationRow("Spam Score", "${caller.spamScore}/100", if (caller.spamScore < 30) Success else Error)
            ReputationRow("Community Reports", caller.reportCount.toString(), Warning)
            ReputationRow("Overall Status", "${caller.spamStatus}", if (caller.spamStatus.name == "SAFE") Success else Error)
        }
    }
}

@Composable
fun ReputationRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.6f))
        Text(text = value, color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
fun DetailItem(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = "$label: ", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
        Text(text = value, color = Color.White)
    }
}
