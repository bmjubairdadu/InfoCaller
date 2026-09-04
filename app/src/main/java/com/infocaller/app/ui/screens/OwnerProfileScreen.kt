package com.infocaller.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infocaller.app.ui.theme.Background
import com.infocaller.app.ui.theme.Primary
import com.infocaller.app.ui.viewmodel.OwnerProfileViewModel
import com.infocaller.app.ui.viewmodel.OwnerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: OwnerProfileViewModel = viewModel(factory = OwnerProfileViewModel.Factory(context))
    val state by vm.state.collectAsState()
    val backend by vm.backendUrl.collectAsState()
    val verified by vm.isVerified.collectAsState()
    val verifiedPhone by vm.verifiedPhone.collectAsState()
    val profileJson by vm.myProfileJson.collectAsState()

    var backendEdit by remember(backend) { mutableStateOf(backend) }
    val phone by vm.phone.collectAsState()
    val code by vm.code.collectAsState()
    val displayName by vm.displayName.collectAsState()
    val photoUrl by vm.photoUrl.collectAsState()
    val businessName by vm.businessName.collectAsState()
    val businessCategory by vm.businessCategory.collectAsState()
    val country by vm.country.collectAsState()
    val isBusiness by vm.isBusiness.collectAsState()
    val visibility by vm.visibility.collectAsState()
    val consent by vm.consentChecked.collectAsState()

    LaunchedEffect(Unit) { vm.refresh(); vm.loadProfile() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Caller Profile", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background,
        snackbarHost = {
            SnackbarHost(hostState = remember {
                SnackbarHostState().also { host ->
                    // surface state changes via LaunchedEffect below
                }
            })
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (val s = state) {
                is OwnerUiState.Message -> {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = if (s.error) Color(0xFF4A1414) else Color(0xFF12351F)
                    )) {
                        Text(s.text, color = Color.White, modifier = Modifier.padding(12.dp))
                    }
                }
                OwnerUiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Primary)
                else -> {}
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backend (your server)", fontWeight = FontWeight.Bold, color = Color.White)
                    OutlinedTextField(
                        value = backendEdit, onValueChange = { backendEdit = it },
                        label = { Text("https://your-backend.example") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Button(onClick = { vm.setBackend(backendEdit) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Save Backend URL")
                    }
                    Text(
                        "Deploy backend/ on your server first. Never use a public demo URL for real users.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, null, tint = Primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (verified) "Verified: ${verifiedPhone ?: ""}" else "Step 1: Verify your number (OTP)",
                            fontWeight = FontWeight.Bold, color = Color.White
                        )
                    }
                    OutlinedTextField(
                        value = phone, onValueChange = { vm.phone.value = it },
                        label = { Text("Your phone (+880...)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Button(onClick = { vm.requestOtp() }, modifier = Modifier.fillMaxWidth(), enabled = phone.length >= 7) {
                        Text("Send OTP")
                    }
                    OutlinedTextField(
                        value = code, onValueChange = { vm.code.value = it.filter { c -> c.isDigit() } },
                        label = { Text("OTP code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Button(onClick = { vm.verify() }, modifier = Modifier.fillMaxWidth(), enabled = code.length >= 4) {
                        Text("Verify")
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Step 2: Publish only your own profile", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Contacts permission is never treated as consent to publish someone else. Only the OTP-verified owner can publish this number.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    OutlinedTextField(value = displayName, onValueChange = { vm.displayName.value = it },
                        label = { Text("Display name (2-80)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = photoUrl, onValueChange = { vm.photoUrl.value = it },
                        label = { Text("Photo URL (https, optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = businessName, onValueChange = { vm.businessName.value = it },
                        label = { Text("Business name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = businessCategory, onValueChange = { vm.businessCategory.value = it },
                        label = { Text("Business category (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = country, onValueChange = { vm.country.value = it },
                        label = { Text("Country (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = isBusiness, onCheckedChange = { vm.isBusiness.value = it })
                        Text("This is a business profile", color = Color.White)
                    }
                    Text("Visibility", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("public", "unlisted", "private").forEach { v ->
                            FilterChip(
                                selected = visibility == v,
                                onClick = {
                                    vm.visibility.value = v
                                    if (verified) vm.setVisibility(v)
                                },
                                label = { Text(v) }
                            )
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = consent, onCheckedChange = { vm.consentChecked.value = it })
                        Text(
                            "I am the owner of this number and I consent to show this info to other users.",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = { vm.publish() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = verified && consent && displayName.trim().length in 2..80,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Publish My Profile") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { vm.revoke() }, modifier = Modifier.weight(1f)) { Text("Revoke") }
                        OutlinedButton(onClick = { vm.delete() }, modifier = Modifier.weight(1f)) { Text("Delete") }
                    }
                    if (!profileJson.isNullOrBlank()) {
                        Text("Current server profile:", color = Primary, fontWeight = FontWeight.Bold)
                        Text(profileJson ?: "", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
