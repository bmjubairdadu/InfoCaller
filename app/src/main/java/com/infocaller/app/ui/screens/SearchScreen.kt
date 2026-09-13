package com.infocaller.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.infocaller.app.ui.components.InfoCallerLoading
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.viewmodel.SearchUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: CallerViewModel,
    onNavigateToDetails: (String) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.searchResult.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var numberInput by remember { mutableStateOf("") }
    var nidInput by remember { mutableStateOf("") }
    var dobInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Identify", color = contentPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = contentPrimary)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Number / Email") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("NID / DOB") })
        }
        if (tab == 1) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = nidInput,
                    onValueChange = { nidInput = it.filter { c -> c.isDigit() }.take(17) },
                    label = { Text("NID number (10-17 digits)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = dobInput,
                    onValueChange = { dobInput = it.take(12) },
                    label = { Text("DOB optional (YYYY-MM-DD)") },
                    placeholder = { Text("1969-04-19") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Button(
                    onClick = {
                        val q = if (dobInput.isNotBlank()) nidInput.trim() + "|" + dobInput.trim() else nidInput.trim()
                        if (q.isNotBlank()) viewModel.searchNidManual(q)
                    },
                    enabled = nidInput.filter { it.isDigit() }.length in 10..17,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Search NID database") }
                Text(
                    "On-device NID database (offline). NID alone, or NID|DOB to verify.",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentSecondary(0.6f),
                )
            }
        } else {
            OutlinedTextField(
                value = numberInput,
                onValueChange = { numberInput = it.take(120) },
                label = { Text("Number, email or @username") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            Button(
                onClick = { if (numberInput.isNotBlank()) viewModel.searchNumber(numberInput.trim()) },
                enabled = numberInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Search") }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when (uiState) {
                is SearchUiState.Idle -> Text("Type a number to begin...", color = contentSecondary(0.5f))
                is SearchUiState.Loading -> InfoCallerLoading(isFullScreen = true, text = "Searching...")
                is SearchUiState.Success -> {
                    val caller = (uiState as SearchUiState.Success).caller
                    Card(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth()
                            .glassy(radius = 24.dp)
                            .clickable {
                                val number = caller.phoneNumber
                                if (number.isNotBlank()) onNavigateToDetails(number)
                            },
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = caller.displayName ?: "Unknown Caller", style = MaterialTheme.typography.titleLarge, color = contentPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = caller.phoneNumber, style = MaterialTheme.typography.bodyMedium, color = Primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Verified Result",
                                color = Success,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }
                }
                is SearchUiState.NotFound -> Text("No data found for this number", color = contentPrimary)
                is SearchUiState.Error -> Text("Error: ${(uiState as SearchUiState.Error).message}", color = Error)
            }
        }
        }
        }
}
