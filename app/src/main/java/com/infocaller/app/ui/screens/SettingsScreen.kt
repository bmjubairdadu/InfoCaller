package com.infocaller.app.ui.screens

import android.content.Context
import android.content.Intent
import android.app.Activity
import androidx.core.content.edit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.data.remote.CommunityConsent
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.ui.theme.Primary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: com.infocaller.app.ui.viewmodel.CallerViewModel,
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToDetails: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var recordingEnabled by remember { mutableStateOf(PermissionManager.hasPermissions(context, PermissionManager.RECORD_AUDIO_PERMISSION)) }
    val recordingLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        recordingEnabled = results.values.all { it }
    }
    val contactsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            SettingsSection("Identity Lookup") {
                var searchNumber by remember { mutableStateOf("") }

                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = searchNumber,
                        onValueChange = { searchNumber = it },
                        label = { Text("Search Phone Number") },
                        placeholder = { Text("+880...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        trailingIcon = {
                            if (searchNumber.isNotBlank()) {
                                IconButton(onClick = { searchNumber = "" }) {
                                    Icon(Icons.Default.Clear, null)
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (searchNumber.isNotBlank()) {
                                // Manual search: pause background work and focus
                                // exclusively on this number (CRITICAL priority).
                                viewModel.searchNumberManual(searchNumber)
                                viewModel.triggerThrottledSync(context)
                                onNavigateToDetails(searchNumber)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = searchNumber.length >= 7
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text("IDENTIFY CALLER")
                    }
                }
            }

            SettingsSection("Email Lookup") {
                var searchEmail by remember { mutableStateOf("") }
                var emailError by remember { mutableStateOf<String?>(null) }

                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = searchEmail,
                        onValueChange = { searchEmail = it; emailError = null },
                        label = { Text("Search Email Address") },
                        placeholder = { Text("name@example.com") },
                        leadingIcon = { Icon(Icons.Default.Email, null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        trailingIcon = {
                            if (searchEmail.isNotBlank()) {
                                IconButton(onClick = { searchEmail = "" }) {
                                    Icon(Icons.Default.Clear, null)
                                }
                            }
                        }
                    )
                    emailError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val cleaned = searchEmail.trim().lowercase()
                            if (!cleaned.contains("@") || !cleaned.contains(".")) { emailError = "Enter a valid email address"; return@Button }
                            emailError = null
                            // CRITICAL scan over EMAIL providers (Gravatar, GitHub,
                            // breach check, presence). Same focus semantics as NID.
                            viewModel.searchEmailManual(cleaned)
                            onNavigateToDetails(cleaned)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = searchEmail.contains("@")
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text("LOOKUP EMAIL")
                    }
                }
            }

            SettingsSection("Username Lookup") {
                var searchUsername by remember { mutableStateOf("") }
                var usernameError by remember { mutableStateOf<String?>(null) }

                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = searchUsername,
                        onValueChange = { searchUsername = it; usernameError = null },
                        label = { Text("Search Username") },
                        placeholder = { Text("username123") },
                        leadingIcon = { Icon(Icons.Default.AlternateEmail, null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        trailingIcon = {
                            if (searchUsername.isNotBlank()) {
                                IconButton(onClick = { searchUsername = "" }) {
                                    Icon(Icons.Default.Clear, null)
                                }
                            }
                        }
                    )
                    usernameError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val cleaned = searchUsername.trim().lowercase().removePrefix("@")
                            if (cleaned.length < 2 || cleaned.contains(" ") || cleaned.contains("@")) { usernameError = "Enter a valid username (letters, digits, . _ -)"; return@Button }
                            usernameError = null
                            // CRITICAL scan over USERNAME providers (Sherlock 40-site
                            // sweep, WhatsMyName, GitHub, profile extractors).
                            // Same focus semantics as NID/email.
                            viewModel.searchUsernameManual(cleaned)
                            onNavigateToDetails(cleaned)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = searchUsername.trim().length >= 2
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text("LOOKUP USERNAME")
                    }
                }
            }

            SettingsSection("Calls") {
                val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
                val screeningDao = remember(app) { app.database.screeningDao() }
                var blockAnonymous by remember {
                    mutableStateOf(com.infocaller.app.data.local.CallScreeningRules.isBlockAnonymousEnabled(context))
                }
                var blockUnknown by remember {
                    mutableStateOf(com.infocaller.app.data.local.CallScreeningRules.isBlockUnknownEnabled(context))
                }
                val blockedPrefixes by com.infocaller.app.data.local.CallScreeningRules
                    .getPrefixes(screeningDao).collectAsState(initial = emptyList())
                val blockedEvents by com.infocaller.app.data.local.CallScreeningRules
                    .getRecentEvents(screeningDao, 20).collectAsState(initial = emptyList())
                var newPrefix by remember { mutableStateOf("") }
                var prefixError by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                SettingsToggleRow(
                    title = "Block anonymous callers",
                    subtitle = "Block hidden/withheld/private numbers",
                    icon = Icons.Default.VisibilityOff,
                    checked = blockAnonymous,
                    onCheckedChange = {
                        blockAnonymous = it
                        com.infocaller.app.data.local.CallScreeningRules.setBlockAnonymousEnabled(context, it)
                    }
                )
                SettingsToggleRow(
                    title = "Block unknown numbers",
                    subtitle = "Block callers not in your contacts (needs contacts permission)",
                    icon = Icons.Default.PersonOff,
                    checked = blockUnknown,
                    onCheckedChange = {
                        if (it && !PermissionManager.hasPermissions(context, PermissionManager.CONTACTS_PERMISSIONS)) {
                            try {
                                contactsPermissionLauncher.launch(PermissionManager.CONTACTS_PERMISSIONS)
                            } catch (_: Exception) { }
                        }
                        blockUnknown = it
                        com.infocaller.app.data.local.CallScreeningRules.setBlockUnknownEnabled(context, it)
                    }
                )

                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Blocked prefixes", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newPrefix,
                            onValueChange = { newPrefix = it; prefixError = null },
                            label = { Text("Prefix, e.g. +1800") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            scope.launch {
                                val ok = com.infocaller.app.data.local.CallScreeningRules.addPrefix(screeningDao, newPrefix)
                                if (ok) { newPrefix = ""; prefixError = null }
                                else prefixError = "Enter 2–15 digits, optional leading +"
                            }
                        }) { Text("Add") }
                    }
                    prefixError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    blockedPrefixes.forEach { row ->
                        ListItem(
                            headlineContent = { Text(row.prefix) },
                            trailingContent = {
                                IconButton(onClick = {
                                    scope.launch {
                                        com.infocaller.app.data.local.CallScreeningRules.removePrefix(screeningDao, row.prefix)
                                    }
                                }) { Icon(Icons.Default.Delete, null) }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    if (blockedPrefixes.isEmpty()) {
                        Text(
                            "No prefixes blocked. Add one to reject whole ranges.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Blocked calls",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            scope.launch {
                                try { screeningDao.clearEvents() } catch (_: Exception) { }
                            }
                        }) { Text("Clear") }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (blockedEvents.isEmpty()) {
                        Text(
                            "Nothing blocked yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        blockedEvents.take(20).forEach { ev ->
                            ListItem(
                                headlineContent = { Text(ev.phoneNumber) },
                                supportingContent = {
                                    Text(
                                        "${ev.reason} • ${
                                            java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                                                .format(java.util.Date(ev.timestamp))
                                        }"
                                    )
                                },
                                leadingContent = { Icon(Icons.Default.Block, null, tint = Primary) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }

                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                var currentRingtoneUri by remember { mutableStateOf(prefs.getString("custom_ringtone_uri", null)) }
                
                val ringtoneLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        val uri = result.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                        if (uri != null) {
                            currentRingtoneUri = uri.toString()
                            prefs.edit { putString("custom_ringtone_uri", uri.toString()) }
                        }
                    }
                }

                SettingsClickRow(
                    title = "Incoming Call Ringtone",
                    subtitle = currentRingtoneUri?.let { android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(it)).getTitle(context) } ?: "Default System Ringtone",
                    icon = Icons.Default.MusicNote,
                    onClick = {
                        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Select InfoCaller Ringtone")
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentRingtoneUri?.let { android.net.Uri.parse(it) })
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        }
                        ringtoneLauncher.launch(intent)
                    }
                )

                SettingsToggleRow(
                    title = "Call Recording",
                    subtitle = "Automatically record calls",
                    icon = Icons.Default.FiberManualRecord,
                    checked = recordingEnabled,
                    onCheckedChange = { 
                        if (it) recordingLauncher.launch(PermissionManager.RECORD_AUDIO_PERMISSION)
                        else recordingEnabled = false
                    }
                )
            }

            SettingsSection("USSD Codes") {
                UssdSettingsContent()
            }

            SettingsSection("Soundboard") {
                SoundboardSettingsContent()
            }

            SettingsSection("Appearance") {
                val darkTheme by viewModel.themeMode.collectAsState()
                
                ListItem(
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text("Select your preferred visual style") },
                    leadingContent = { Icon(Icons.Default.Palette, null, tint = Primary) },
                    trailingContent = {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(if (darkTheme == null) "System" else if (darkTheme == true) "Dark" else "Light", color = Primary)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text("Light") }, onClick = { viewModel.setThemeMode(false, context); expanded = false })
                                DropdownMenuItem(text = { Text("Dark") }, onClick = { viewModel.setThemeMode(true, context); expanded = false })
                                DropdownMenuItem(text = { Text("System Default") }, onClick = { viewModel.setThemeMode(null, context); expanded = false })
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            SettingsSection("My Caller ID") {
                Text(
                    "Your number is verified by Truecaller OTP at login. No profile publishing: there is no backend server, so nothing is ever uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            SettingsSection("About") {
                SettingsInfoRow("Version", "2.1.0", Icons.Default.Info)
                SettingsClickRow(
                    title = "Privacy Policy",
                    subtitle = "Read our data policy",
                    icon = Icons.Default.Security,
                    onClick = onNavigateToPrivacy
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Primary, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(20.dp)) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsToggleRow(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
        leadingContent = { Icon(icon, null, tint = Primary) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = Primary))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun SettingsClickRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
        leadingContent = { Icon(icon, null, tint = Primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
        modifier = Modifier.clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun SettingsInfoRow(title: String, value: String, icon: ImageVector) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value, color = Primary, fontWeight = FontWeight.Bold) },
        leadingContent = { Icon(icon, null, tint = Primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun UssdSettingsContent() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(com.infocaller.app.util.UssdStore.load(context)) }
    var newCode by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun persist(next: List<com.infocaller.app.util.UssdEntry>) {
        entries = next
        com.infocaller.app.util.UssdStore.save(context, next)
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "USSD shortcuts appear as chips in the dial pad and run with one tap. Use the call button in the dial pad to run a * or # code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = newCode,
            onValueChange = { newCode = it; error = null },
            label = { Text("USSD code, e.g. *566#") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = newLabel,
            onValueChange = { newLabel = it },
            label = { Text("Name (optional)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Button(onClick = {
                val code = newCode.trim()
                if (!com.infocaller.app.util.UssdStore.isUssd(code)) {
                    error = "Enter a valid USSD code starting with * or #"
                    return@Button
                }
                if (entries.any { it.code.equals(code, ignoreCase = true) }) {
                    error = "That code is already saved"
                    return@Button
                }
                persist(entries + com.infocaller.app.util.UssdEntry(code = code, label = newLabel.trim()))
                newCode = ""
                newLabel = ""
                error = null
            }) { Text("Add") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                persist(com.infocaller.app.util.UssdStore.defaultEntries())
                error = null
            }) { Text("Reset defaults") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        entries.forEach { entry ->
            ListItem(
                headlineContent = { Text(if (entry.label.isNotBlank()) entry.label else entry.code) },
                supportingContent = { if (entry.label.isNotBlank()) Text(entry.code) else null },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.SendToMobile, null, tint = Primary) },
                trailingContent = {
                    Row {
                        IconButton(onClick = {
                            try { com.infocaller.app.util.UssdStore.run(context, entry.code) } catch (_: Exception) { }
                        }) { Icon(Icons.Default.PlayArrow, "Run", tint = Primary) }
                        IconButton(onClick = { persist(entries.filter { it.id != entry.id }) }) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        if (entries.isEmpty()) {
            Text(
                "No USSD codes saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SoundboardSettingsContent() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(com.infocaller.app.util.SoundboardStore.load(context)) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newEmoji by remember { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(com.infocaller.app.util.SoundboardStore.KIND_TTS) }
    var newTone by remember { mutableStateOf("beep") }
    var newFileUri by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<com.infocaller.app.util.SoundboardEntry?>(null) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            newFileUri = uri.toString()
            newKind = com.infocaller.app.util.SoundboardStore.KIND_FILE
        }
    }

    fun persist(next: List<com.infocaller.app.util.SoundboardEntry>) {
        entries = next
        com.infocaller.app.util.SoundboardStore.save(context, next)
    }

    DisposableEffect(Unit) {
        onDispose { com.infocaller.app.util.SoundboardPlayer.stop() }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "Discord-style soundboard for calls. Sounds play through the speaker so the other side hears them. Customize each button with a name and emoji.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            entries.forEach { entry ->
                val isPlaying = playingId == entry.id
                FilterChip(
                    selected = isPlaying,
                    onClick = {
                        try {
                            com.infocaller.app.util.SoundboardPlayer.play(context, entry) {
                                playingId = com.infocaller.app.util.SoundboardPlayer.playingId
                            }
                            playingId = com.infocaller.app.util.SoundboardPlayer.playingId
                        } catch (_: Exception) { }
                    },
                    label = { Text("${entry.emoji} ${entry.name}") },
                    trailingIcon = {
                        if (isPlaying) Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it; error = null },
            label = { Text("Button name") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = newEmoji,
            onValueChange = { if (it.length <= 4) newEmoji = it },
            label = { Text("Emoji") },
            placeholder = { Text("\uD83D\uDD0A") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = newKind == com.infocaller.app.util.SoundboardStore.KIND_TTS,
                onClick = { newKind = com.infocaller.app.util.SoundboardStore.KIND_TTS },
                label = { Text("Voice") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = newKind == com.infocaller.app.util.SoundboardStore.KIND_TONE,
                onClick = { newKind = com.infocaller.app.util.SoundboardStore.KIND_TONE },
                label = { Text("Tone") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = newKind == com.infocaller.app.util.SoundboardStore.KIND_FILE,
                onClick = {
                    newKind = com.infocaller.app.util.SoundboardStore.KIND_FILE
                    audioPicker.launch("audio/*")
                },
                label = { Text("Audio file") }
            )
        }
        if (newKind == com.infocaller.app.util.SoundboardStore.KIND_TTS) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = newText,
                onValueChange = { newText = it },
                label = { Text("What the voice says") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        }
        if (newKind == com.infocaller.app.util.SoundboardStore.KIND_TONE) {
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                listOf("beep", "chime", "airhorn").forEach { tone ->
                    FilterChip(
                        selected = newTone == tone,
                        onClick = { newTone = tone },
                        label = { Text(tone.replaceFirstChar { c -> c.uppercase() }) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
        if (newKind == com.infocaller.app.util.SoundboardStore.KIND_FILE) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    newFileUri?.takeLast(32)?.let { "…$it" } ?: "No file picked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { audioPicker.launch("audio/*") }) { Text("Pick audio") }
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Button(onClick = {
                val name = newName.trim()
                if (name.isEmpty()) {
                    error = "Give the button a name"
                    return@Button
                }
                val emoji = newEmoji.ifBlank { "\uD83D\uDD0A" }
                val payload = when (newKind) {
                    com.infocaller.app.util.SoundboardStore.KIND_TTS -> newText.trim().ifBlank { name }
                    com.infocaller.app.util.SoundboardStore.KIND_TONE -> newTone
                    else -> newFileUri ?: ""
                }
                if (newKind == com.infocaller.app.util.SoundboardStore.KIND_FILE && payload.isBlank()) {
                    error = "Pick an audio file first"
                    return@Button
                }
                persist(
                    entries + com.infocaller.app.util.SoundboardEntry(
                        name = name, emoji = emoji, kind = newKind, payload = payload
                    )
                )
                newName = ""
                newEmoji = ""
                newText = ""
                newFileUri = null
                error = null
            }) { Text("Add sound") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                persist(com.infocaller.app.util.SoundboardStore.defaultEntries())
                error = null
            }) { Text("Reset defaults") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        entries.forEach { entry ->
            ListItem(
                headlineContent = { Text("${entry.emoji} ${entry.name}") },
                supportingContent = {
                    Text(
                        when (entry.kind) {
                            com.infocaller.app.util.SoundboardStore.KIND_TTS -> "Voice: ${(entry.payload.ifBlank { entry.name }).take(60)}"
                            com.infocaller.app.util.SoundboardStore.KIND_FILE -> "Audio file"
                            else -> "Tone: ${entry.payload}"
                        }
                    )
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { editing = entry; newName = entry.name; newEmoji = entry.emoji }) {
                            Icon(Icons.Default.Edit, "Rename")
                        }
                        IconButton(onClick = {
                            com.infocaller.app.util.SoundboardPlayer.stop()
                            persist(entries.filter { it.id != entry.id })
                        }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }

    val editEntry = editing
    if (editEntry != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Rename sound") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Button name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newEmoji,
                        onValueChange = { v -> if (v.length <= 4) newEmoji = v },
                        label = { Text("Emoji") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty()) {
                        persist(entries.map {
                            if (it.id == editEntry.id) it.copy(name = name, emoji = newEmoji.ifBlank { it.emoji })
                            else it
                        })
                    }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            }
        )
    }
}
