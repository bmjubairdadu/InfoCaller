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
import com.infocaller.app.ui.theme.Primary
import com.infocaller.app.ui.theme.contentPrimary
import com.infocaller.app.ui.theme.contentSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy", color = contentPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = contentPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            PolicySection("Information We Collect",
                "InfoCaller reads contact information, call logs, and phone state on this device to provide caller identification features. Everything stays on your device except anonymous lookups you trigger.")

            PolicySection("No Uploads",
                "This version of InfoCaller never uploads your contacts, call history, or any personal data anywhere. There is no contribution queue, no shared registry, and no owner-profile publishing: the background contribution and profile-publish features were removed because their backend never existed. Lookups only query public sources for numbers, emails, or usernames you explicitly search.")
            
            PolicySection("Data Enrichment", 
                "We use public providers (like Truecaller, WhatsApp public data) and a shared registry to enrich caller information. This data is merged to provide the best possible identification.")
            
            PolicySection("Owner Consent (Strict)",
                "Contacts permission only lets the app read your address book on this device. It is NEVER treated as permission to publish someone else's identity. Your number is verified by Truecaller OTP at login for lookups only — nothing is published anywhere.")

            PolicySection("Shared Registry",
                "Removed: there is no shared registry in this version. Caller information comes from on-device data (your NID database file, offline metadata) and public lookups you trigger.")
            
            PolicySection("Permissions", 
                "InfoCaller requires access to Contacts, Phone, and Call Logs to function as a dialer and caller ID service. These permissions are used strictly for app features.")
            
            Spacer(modifier = Modifier.height(32.dp))
            Text("Last Updated: September 2026", color = contentSecondary(0.4f), fontSize = 12.sp)
        }
    }
}

@Composable
fun PolicySection(title: String, content: String) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Primary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(content, style = MaterialTheme.typography.bodyMedium, color = contentSecondary(0.7f), lineHeight = 22.sp)
    }
}
