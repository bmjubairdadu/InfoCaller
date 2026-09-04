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

            PolicySection("Community Contribution (Optional, Consent-Gated)",
                "When you open the Contacts tab for the first time, InfoCaller asks for your permission to contribute caller-ID information in the background. " +
                "If you tap Accept, the app processes your contacts one-by-one using WorkManager: each number is identified with the normal caller-ID engine, cached on your device, " +
                "and only permitted caller-ID fields are uploaded to our server so other users can benefit. " +
                "If you tap Decline, nothing is uploaded and no background contribution ever starts. The question is asked only once.")

            PolicySection("What May Be Shared (Only After Accept)",
                "Only two fields may leave your device: (1) a SHA-256 fingerprint of the phone number — never the number itself; " +
                "(2) the public caller name found by caller-ID lookup (for example a business or public-listing name). " +
                "Spam/report counters are computed on the server. Duplicate submissions are skipped: a number is re-uploaded only when its permitted data changes.")

            PolicySection("What Is Never Shared",
                "Your private contact names, private notes, contact IDs / lookup keys, photos, messages, call history, plain phone numbers, " +
                "and any unrelated local metadata are NEVER uploaded. The app also never holds database write keys: all shared-database " +
                "writes happen on the InfoCaller server (POST /api/v1/community/contribute), which rejects any field outside the permitted set.")

            PolicySection("Withdrawing Consent",
                "You can stop contributing at any time: clear the app's contribution consent in Settings (re-install resets it) or simply " +
                "force-stop background work — declined/revoked state means zero uploads. Background retries and resume-after-restart only " +
                "run while consent is accepted.")
            
            PolicySection("Data Enrichment", 
                "We use public providers (like Truecaller, WhatsApp public data) and a shared registry to enrich caller information. This data is merged to provide the best possible identification.")
            
            PolicySection("Owner Consent (Strict)",
                "Contacts permission only lets the app read your address book on this device. It is NEVER treated as permission to publish someone else's identity. Only a phone-number owner who verifies the number by OTP and gives explicit consent can publish that number's profile. Anyone can hide (unlisted/private), revoke consent, or delete their profile at any time from My Caller Profile.")

            PolicySection("Shared Registry",
                "Publicly available caller information may be shared with our registry to help other users identify the same numbers. Personal data such as your local contact names, notes, or messages are NEVER uploaded.")
            
            PolicySection("Permissions", 
                "InfoCaller requires access to Contacts, Phone, and Call Logs to function as a dialer and caller ID service. These permissions are used strictly for app features.")
            
            Spacer(modifier = Modifier.height(32.dp))
            Text("Last Updated: September 2026", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
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
