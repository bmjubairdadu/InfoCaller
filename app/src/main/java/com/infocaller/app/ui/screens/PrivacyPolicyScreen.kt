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

            PolicySection("Contacts and the Shared Database",
                "Contacts permission is requested once, at login. With it, InfoCaller reads your address book, matches each contact, and looks up the public details already available for that number. Those public details (name, profile photo, city, carrier and similar fields) are then sent to our shared database, keyed by phone number, so that another InfoCaller user searching the same number can see them immediately instead of running a fresh scan. This happens automatically while Contacts permission is granted. The shared database is private and readable only through this app. Revoking Contacts permission in Android settings stops the lookups and stops any further contributions; it does not retract what has already been sent. Your own number is verified by OTP at login and is used for lookups only.")

            PolicySection("What Is Never Shared",
                "Contact entries, dates of birth, national ID numbers, call recordings, SMS messages and your contact list itself are never uploaded. Only the public profile fields listed above are.")

            PolicySection("NID Database",
                "The NID/DOB database is not included in the app. It lives on our backend and is queried only when you search a NID or date of birth, using your device's API key. Nothing from it is stored on your device. Any previously downloaded copy is deleted when the app updates.")

            PolicySection("Permissions",
                "InfoCaller requires Contacts, Phone and Call Logs. Phone and Call Logs are used to work as a dialer and caller ID service. Contacts is used to match, enrich and display caller details, and to contribute those public details to the shared database as described above.")

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
