package com.infocaller.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.ui.theme.Background
import com.infocaller.app.ui.theme.Primary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            PolicySection("Information We Collect", 
                "InfoCaller collects contact information, call logs, and phone state to provide caller identification features. We do not collect or store private contact names provided by you locally.")
            
            PolicySection("Data Enrichment", 
                "We use public providers (like Truecaller, WhatsApp public data) and a shared registry to enrich caller information. This data is merged to provide the best possible identification.")
            
            PolicySection("Shared Registry", 
                "Publicly available caller information may be shared with our registry to help other users identify the same numbers. Personal data such as your local contact names, notes, or messages are NEVER uploaded.")
            
            PolicySection("Permissions", 
                "InfoCaller requires access to Contacts, Phone, and Call Logs to function as a dialer and caller ID service. These permissions are used strictly for app features.")
            
            Spacer(modifier = Modifier.height(32.dp))
            Text("Last Updated: August 2026", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
        }
    }
}

@Composable
fun PolicySection(title: String, content: String) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Primary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(content, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), lineHeight = 22.sp)
    }
}
