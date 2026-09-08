package com.infocaller.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
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
