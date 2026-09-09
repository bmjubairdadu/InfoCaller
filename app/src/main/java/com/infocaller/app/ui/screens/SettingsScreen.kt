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
import com.infocaller.app.ui.theme.contentPrimary
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
                title = { Text("Settings", color = contentPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = contentPrimary)
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
                                viewModel.cancelAllSearches()
                                viewModel.clearSearch()
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
                        placeholder = { Text("Enter email address") },
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
                            viewModel.cancelAllSearches()
                            viewModel.clearSearch()
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
                            viewModel.cancelAllSearches()
                            viewModel.clearSearch()
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
                var blockAnonymous by remember {
                    mutableStateOf(com.infocaller.app.data.local.CallScreeningRules.isBlockAnonymousEnabled(context))
                }
                var blockUnknown by remember {
                    mutableStateOf(com.infocaller.app.data.local.CallScreeningRules.isBlockUnknownEnabled(context))
                }

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

            SettingsSection("Eyecon Caller ID (captured auth)") {
                EyeconAuthSettingsContent()
            }

            SettingsSection("About") {
                SettingsInfoRow("Version", com.infocaller.app.util.AppUpdateManager.currentVersion(), Icons.Default.Info)
                AppUpdateRow()
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
private fun AppUpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by com.infocaller.app.util.AppUpdateManager.state.collectAsState()
    var checking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            com.infocaller.app.util.AppUpdateManager.checkForUpdate(context, force = false)
        } catch (_: Exception) { }
    }

    when (val s = state) {
        is com.infocaller.app.util.AppUpdateManager.UpdateState.Idle -> {
            SettingsClickRow(
                title = "Check for updates",
                subtitle = if (checking) "Checking…" else "You are on v${com.infocaller.app.util.AppUpdateManager.currentVersion()}",
                icon = Icons.Default.SystemUpdate,
                onClick = {
                    if (checking) return@SettingsClickRow
                    checking = true
                    scope.launch {
                        try {
                            val info = com.infocaller.app.util.AppUpdateManager.checkForUpdate(context, force = true)
                            if (info == null) {
                                android.widget.Toast.makeText(context, "Already on the latest version", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } finally { checking = false }
                    }
                }
            )
        }
        is com.infocaller.app.util.AppUpdateManager.UpdateState.Checking -> {
            ListItem(
                headlineContent = { Text("Checking for updates…") },
                leadingContent = {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        is com.infocaller.app.util.AppUpdateManager.UpdateState.Available -> {
            val mb = if (s.sizeBytes > 0) " • ${(s.sizeBytes / 1048576)} MB" else ""
            SettingsClickRow(
                title = "Update to v${s.version}",
                subtitle = "New version available$mb — tap to download",
                icon = Icons.Default.Download,
                onClick = {
                    scope.launch {
                        try {
                            com.infocaller.app.util.AppUpdateManager.downloadUpdate(
                                context,
                                com.infocaller.app.util.AppUpdateManager.ReleaseInfo(s.version, s.notes, s.url, s.sizeBytes)
                            )
                        } catch (_: Exception) { }
                    }
                }
            )
            if (s.notes.isNotBlank()) {
                Text(
                    s.notes.take(400),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }
        }
        is com.infocaller.app.util.AppUpdateManager.UpdateState.Downloading -> {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Downloading update… ${s.progress}%", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { s.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is com.infocaller.app.util.AppUpdateManager.UpdateState.Ready -> {
            SettingsClickRow(
                title = "Install update",
                subtitle = "Download finished — open installer",
                icon = Icons.Default.InstallMobile,
                onClick = {
                    try {
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                        val id = context.getSharedPreferences("app_update", Context.MODE_PRIVATE).getLong("download_id", -1L)
                        if (id != -1L) {
                            com.infocaller.app.util.AppUpdateManager.openInstaller(context, dm.getUriForDownloadedFile(id))
                        }
                    } catch (_: Exception) { }
                }
            )
        }
        is com.infocaller.app.util.AppUpdateManager.UpdateState.Failed -> {
            SettingsClickRow(
                title = "Update check failed",
                subtitle = "${s.reason} — tap to retry",
                icon = Icons.Default.Refresh,
                onClick = { com.infocaller.app.util.AppUpdateManager.reset() }
            )
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
private fun EyeconAuthSettingsContent() {
    val context = LocalContext.current
    val store = remember { com.infocaller.app.data.remote.EyeconAuthStore(context.applicationContext) }
    var cid by remember { mutableStateOf(store.cid() ?: "") }
    var c by remember { mutableStateOf(store.c() ?: "") }
    var k by remember { mutableStateOf(store.k() ?: "") }
    var savedTick by remember { mutableStateOf(0) }
    val connected = remember(savedTick) { store.hasAuth() }
    val usingBuiltIn = remember(savedTick) { store.isUsingBuiltIn() }
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Built-in key verified live (getnames + photo return 200). Paste your own values from Reqable eyecon.har (join.jsp response + getnames.jsp headers) only if you want to override it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(value = cid, onValueChange = { cid = it.trim() }, label = { Text("e-auth (client id)") },
            placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = c, onValueChange = { c = it.trim() }, label = { Text("e-auth-c") },
                modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(value = k, onValueChange = { k = it.trim() }, label = { Text("e-auth-k") },
                modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (cid.isNotBlank()) {
                        store.save(cid, c.ifBlank { "37" }, k.ifBlank { "" })
                        savedTick++
                    }
                },
                enabled = cid.isNotBlank(),
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
            ) { Text("SAVE") }
            OutlinedButton(
                onClick = { store.clear(); cid = store.cid() ?: ""; c = store.c() ?: ""; k = store.k() ?: ""; savedTick++ },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
            ) { Text("Reset to built-in") }
        }
        Text(
            when {
                !connected -> "Status: anonymous — Eyecon lookups still try without auth."
                usingBuiltIn -> "Status: connected (built-in key) — Eyecon name + photo lookups active, no login needed."
                else -> "Status: connected (your key) — Eyecon name + photo lookups active."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SoundPreviewChips(
    entries: List<com.infocaller.app.util.SoundboardEntry>,
    playingId: String?,
    onPlay: (com.infocaller.app.util.SoundboardEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            "Nothing here yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        entries.forEach { entry ->
            val isPlaying = playingId == entry.id
            FilterChip(
                selected = isPlaying,
                onClick = { onPlay(entry) },
                label = { Text("${entry.emoji} ${entry.name}") },
                trailingIcon = {
                    if (isPlaying) Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                }
            )
        }
    }
}

@Composable
private fun EmojiPickerGrid(
    selected: String,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        com.infocaller.app.util.SoundboardStore.EMOJI_CHOICES.chunked(8).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { emoji ->
                    val isSel = emoji == selected
                    Surface(
                        onClick = { onPick(emoji) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSel) Primary.copy(alpha = 0.25f) else Color.Transparent,
                        border = if (isSel) androidx.compose.foundation.BorderStroke(1.dp, Primary) else null,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                }
            }
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
    var newFileUri by remember { mutableStateOf<String?>(null) }
    var newFileLabel by remember { mutableStateOf<String?>(null) }
    var newIsVideo by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<com.infocaller.app.util.SoundboardEntry?>(null) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
            newIsVideo = (mime ?: "").startsWith("video")
            newFileUri = uri.toString()
            val title = com.infocaller.app.util.SoundboardStore.mediaTitle(context, uri.toString())
            newFileLabel = title
            if (newName.isBlank() && !title.isNullOrBlank()) newName = title
            if (newEmoji.isBlank()) newEmoji = "\uD83C\uDFAC"
        }
    }

    fun persist(next: List<com.infocaller.app.util.SoundboardEntry>) {
        entries = next
        com.infocaller.app.util.SoundboardStore.save(context, next)
    }

    DisposableEffect(Unit) {
        onDispose { com.infocaller.app.util.SoundboardPlayer.stop() }
    }

    var showEmojiPicker by remember { mutableStateOf(false) }
    var emojiTargetIsRename by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "Discord-style soundboard for calls. Built-ins are real funny sounds; or pick audio/video files — the sound plays through the speaker so the other side hears it. Tap the smiley on the name field to pick an emoji.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("Funny", style = MaterialTheme.typography.labelMedium, color = Primary)
        Spacer(modifier = Modifier.height(6.dp))
        SoundPreviewChips(
            entries = entries.filter { it.kind == com.infocaller.app.util.SoundboardStore.KIND_TONE },
            playingId = playingId,
            onPlay = { entry ->
                try {
                    com.infocaller.app.util.SoundboardPlayer.play(context, entry) {
                        playingId = com.infocaller.app.util.SoundboardPlayer.playingId
                    }
                    playingId = com.infocaller.app.util.SoundboardPlayer.playingId
                } catch (_: Exception) { }
            }
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text("My clips", style = MaterialTheme.typography.labelMedium, color = Primary)
        Spacer(modifier = Modifier.height(6.dp))
        SoundPreviewChips(
            entries = entries.filter { it.kind == com.infocaller.app.util.SoundboardStore.KIND_FILE },
            playingId = playingId,
            onPlay = { entry ->
                try {
                    com.infocaller.app.util.SoundboardPlayer.play(context, entry) {
                        playingId = com.infocaller.app.util.SoundboardPlayer.playingId
                    }
                    playingId = com.infocaller.app.util.SoundboardPlayer.playingId
                } catch (_: Exception) { }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it; error = null },
            label = { Text("Button name") },
            placeholder = { Text("e.g. Laugh, Airhorn, Intro") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { emojiTargetIsRename = false; showEmojiPicker = true }) {
                    Text(newEmoji.ifBlank { "\uD83D\uDE03" }, fontSize = 22.sp)
                }
            }
        )
        if (showEmojiPicker && !emojiTargetIsRename) {
            Spacer(modifier = Modifier.height(8.dp))
            EmojiPickerGrid(
                selected = newEmoji,
                onPick = { newEmoji = it; showEmojiPicker = false }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = newFileUri != null && !newIsVideo,
                onClick = { mediaPicker.launch("audio/*") },
                label = { Text("Pick audio") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = newFileUri != null && newIsVideo,
                onClick = { mediaPicker.launch("video/*") },
                label = { Text("Pick video") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                newFileLabel?.take(28) ?: newFileUri?.takeLast(24)?.let { "…$it" } ?: "No file picked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Video plays its audio track only — no picture on the call.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Button(onClick = {
                val name = newName.trim().ifBlank { newFileLabel?.trim().orEmpty() }
                if (name.isEmpty()) {
                    error = "Give the button a name or pick a file first"
                    return@Button
                }
                val uri = newFileUri
                if (uri.isNullOrBlank()) {
                    error = "Pick an audio or video file first"
                    return@Button
                }
                val emoji = newEmoji.ifBlank {
                    com.infocaller.app.util.SoundboardStore.emojiForName(name)
                }
                persist(
                    entries + com.infocaller.app.util.SoundboardEntry(
                        name = name, emoji = emoji,
                        kind = com.infocaller.app.util.SoundboardStore.KIND_FILE,
                        payload = uri
                    )
                )
                newName = ""
                newEmoji = ""
                newFileUri = null
                newFileLabel = null
                newIsVideo = false
                showEmojiPicker = false
                error = null
            }) { Text("Add sound") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                try { com.infocaller.app.util.SoundboardPlayer.stop() } catch (_: Exception) { }
                playingId = null
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
                            com.infocaller.app.util.SoundboardStore.KIND_FILE -> {
                                val title = com.infocaller.app.util.SoundboardStore.mediaTitle(context, entry.payload)
                                if (!title.isNullOrBlank() && !title.equals(entry.name, ignoreCase = true)) "Media · $title".take(80)
                                else "Media clip"
                            }
                            else -> "Built-in funny clip"
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
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { emojiTargetIsRename = true; showEmojiPicker = true }) {
                                Text(newEmoji.ifBlank { "\uD83D\uDE03" }, fontSize = 22.sp)
                            }
                        }
                    )
                    if (showEmojiPicker && emojiTargetIsRename) {
                        Spacer(modifier = Modifier.height(8.dp))
                        EmojiPickerGrid(
                            selected = newEmoji,
                            onPick = { newEmoji = it; showEmojiPicker = false }
                        )
                    }
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
                TextButton(onClick = { editing = null; showEmojiPicker = false }) { Text("Cancel") }
            }
        )
    }
    LaunchedEffect(editing) {
        if (editing == null) showEmojiPicker = false
    }
}
