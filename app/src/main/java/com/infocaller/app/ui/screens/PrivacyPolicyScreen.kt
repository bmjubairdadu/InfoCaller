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
                "InfoCaller reads contact information, call logs (read-only), and phone state on this device to provide caller identification features. Everything stays on your device except anonymous lookups you trigger.")

            PolicySection("Uploads",
                "InfoCaller never uploads your contacts or call history. The only optional upload is the Shared Caller Database contribution, which is off by default. When you turn it on in Settings, results from numbers you look up are sent to a private backend so other users can identify the same number without re-scanning. Lookups otherwise only query public sources for numbers, emails, or usernames you explicitly search.")

            PolicySection("SMS — OTP Only",
                "SMS permission (RECEIVE_SMS) is asked only on the Login screen for Truecaller OTP auto-read. The app never reads your SMS inbox, never stores message content, and never sends SMS. Incoming texts are scanned only for the verification code while a login is pending.")

            PolicySection("Call Log — Read Only",
                "Call history is listed on the Recents tab only. InfoCaller cannot delete or clear your call history — no write permission is requested.")

            PolicySection("Updates — Browser Only",
                "Update checks only read public GitHub release metadata. New versions open in your browser — the app never downloads or installs APK files itself.")

            PolicySection("Microphone — Only When You Tap Record",
                "Microphone access is requested only when you tap a Record button during a call, never during setup. Recording is always disclosed with an on-screen indicator.")

            PolicySection("Data Enrichment",
                "We use public providers (like Truecaller, WhatsApp public data) and a shared registry to enrich caller information. This data is merged to provide the best possible identification.")

            PolicySection("Owner Consent",
                "Contacts permission lets the app read your address book so it can match, enrich and show caller details. On its own it does nothing else. If — and only if — you turn on the \"Contribute scan results\" switch in Settings, the public details InfoCaller has already found for those contacts (name, photo, city, carrier, similar) are also sent to the shared database so other InfoCaller users can identify the same number later. Turn the switch off to stop this immediately, and past contributions stop being updated. Your number is verified by Truecaller OTP at login for lookups only.")

            PolicySection("Shared Caller Database",
                "The shared database is opt-in and off by default. When the Settings toggle is on, caller details found during your lookups and the contacts InfoCaller has enriched on your device (name, photo, city, carrier and similar public fields) are stored on a private backend keyed by phone number, so the same number can be identified later without a fresh scan. Reads happen automatically only when a backend is configured; contributions happen only while the toggle is on. Turning the toggle off stops all contributions. Caller information otherwise comes from offline metadata and the public lookups you trigger.")

            PolicySection("NID Database",
                "The NID/DOB database is NOT included in the app. It lives on our backend and is queried only when you search a NID/DOB, with your device's API key. Nothing from it is stored on your device. Any previously downloaded copy is deleted when the app updates.")

            PolicySection("Permissions",
                "InfoCaller requires Contacts (requested at login), Phone and Call Logs. Phone and Call Logs are used as a dialer and caller ID service. Contacts is used to match, enrich and display caller details on your device.")

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
