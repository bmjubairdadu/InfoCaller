package com.infocaller.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.infocaller.app.ui.theme.Background
import com.infocaller.app.ui.theme.Primary
import com.infocaller.app.ui.theme.Surface
import com.infocaller.app.util.OSINTManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

@Composable
fun WhatsAppLookupScreen() {
    var phoneNumber by remember { mutableStateOf("") }
    val context = LocalContext.current
    
    val dorks = remember(phoneNumber) {
        if (phoneNumber.length >= 5) OSINTManager.generateDorkLinks(phoneNumber)
        else emptyList()
    }

    Scaffold(
        containerColor = Background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "No-API OSINT Lookup",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 24.dp)
                )

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Phone Number", color = Color.White.copy(alpha = 0.6f)) },
                    placeholder = { Text("e.g. 88017...", color = Color.White.copy(alpha = 0.4f)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                    ),
                    singleLine = true
                )
            }

            if (phoneNumber.length >= 5) {
                item {
                    Text(
                        text = "Deep Discovery Search Links",
                        style = MaterialTheme.typography.titleMedium,
                        color = Primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                items(dorks) { dork ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { OSINTManager.openLink(context, dork.url) },
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dork.title,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else {
                item {
                    Text(
                        text = "Enter at least 5 digits to generate discovery links",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

