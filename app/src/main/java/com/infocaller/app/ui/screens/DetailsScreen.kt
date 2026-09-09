package com.infocaller.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.ui.components.InfoCallerLoading
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.viewmodel.SearchUiState
import com.infocaller.app.util.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetailsScreen(
    viewModel: CallerViewModel,
    onBack: () -> Unit,
    onMakeCall: (String) -> Unit
) {
    val uiState by viewModel.searchResult.collectAsState()
    val blocklist by viewModel.blocklist.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val caller = (uiState as? SearchUiState.Success)?.caller
    val isLive = (uiState as? SearchUiState.Success)?.isLive ?: false
    val lastProvider = (uiState as? SearchUiState.Success)?.lastProvider
    val livePartial = (uiState as? SearchUiState.Success)?.livePartial
    val rawIdentifier = remember(uiState) {
        when (uiState) {
            is SearchUiState.Success -> (uiState as SearchUiState.Success).caller.phoneNumber
            else -> ""
        }
    }
    val isEmailScan = remember(rawIdentifier) { com.infocaller.app.util.IdentifierRouter.isEmail(rawIdentifier) }
    val isUsernameScan = remember(rawIdentifier) {
        !com.infocaller.app.util.IdentifierRouter.isEmail(rawIdentifier) && com.infocaller.app.util.IdentifierRouter.routeType(rawIdentifier) == "USERNAME"
    }
    val isNonPhoneScan = isEmailScan || isUsernameScan
    val phoneNumber = remember(uiState, isNonPhoneScan) {
        if (isNonPhoneScan) "" else {
            val raw = when (uiState) {
                is SearchUiState.Success -> (uiState as SearchUiState.Success).caller.phoneNumber
                else -> ""
            }
            PhoneNumberUtils.normalize(raw)
        }
    }
    val lookupKey = remember(rawIdentifier, phoneNumber, isNonPhoneScan) {
        if (isNonPhoneScan) rawIdentifier.trim().lowercase().removePrefix("@") else phoneNumber
    }
    val displayIdentifier = remember(rawIdentifier, phoneNumber, isNonPhoneScan) {
        if (isNonPhoneScan) rawIdentifier.trim() else phoneNumber
    }
    val enrichment by viewModel.getEnrichment(lookupKey).collectAsState(initial = null)
    val isBlocked = !isNonPhoneScan && blocklist.contains(phoneNumber)
    val contactsList by viewModel.contacts.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    fun copyField(label: String, value: String) {
        scope.launch {
            val ok = try {
                CopyHelper.copy(context, label, value)
            } catch (_: Exception) { false }
            snackbarHostState.showSnackbar(if (ok) "$label copied" else "Copy failed")
        }
    }
    val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
    val isOnline by app.enrichmentEngine.isOnline.collectAsState()

    val contact = remember(phoneNumber, contactsList, isNonPhoneScan) {
        if (isNonPhoneScan) null else contactsList.find { it.phoneNumber == phoneNumber }
    }
    val isContact = contact != null
    var showAddContactDialog by remember { mutableStateOf(false) }
    val scanSteps by viewModel.scanSteps.collectAsState()
    val scanActive by viewModel.scanActive.collectAsState()
    GlassyBackground {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent,
            topBar = {
                Surface(
                    color = topBarScrim(),
                    modifier = Modifier.glassy(blur = 20.dp, radius = 0.dp)
                ) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(if (isEmailScan) "Email Identity" else if (isUsernameScan) "Username Identity" else if (isContact) "Contact Details" else "Caller Identity", color = contentPrimary, style = MaterialTheme.typography.titleMedium)
                                if (isLive) {
                                    Text(text = "Live scan: ${lastProvider ?: "Searching..."}", style = MaterialTheme.typography.labelSmall, color = Primary)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        windowInsets = WindowInsets.statusBars,
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = contentPrimary)
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                scope.launch {
                                    val res = DetailsPngExporter.export(
                                        context, displayIdentifier,
                                        contact?.displayName, caller, enrichment,
                                        contact?.photoUri,
                                    )
                                    snackbarHostState.showSnackbar(
                                        res.fold(
                                            onSuccess = { "Saved PNG to $it" },
                                            onFailure = { it.message ?: "Download failed" }
                                        )
                                    )
                                }
                            }) {
                                Icon(Icons.Default.Download, "Download PNG", tint = Primary)
                            }
                            if (!isNonPhoneScan && !isContact && phoneNumber.isNotBlank()) {
                                IconButton(onClick = { showAddContactDialog = true }) {
                                    Icon(Icons.Default.PersonAdd, "Add Contact", tint = Primary)
                                }
                            }
                            if (!isOnline) {
                                Surface(
                                    color = faintTint(0.1f),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CloudOff, null, tint = contentSecondary(0.6f), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Offline", style = MaterialTheme.typography.labelSmall, color = contentSecondary(0.6f))
                                    }
                                }
                            }
                            if (!isNonPhoneScan) {
                                IconButton(onClick = {
                                    if (isBlocked) viewModel.unblockNumber(phoneNumber)
                                    else viewModel.blockNumber(phoneNumber)
                                }) {
                                    Icon(
                                        imageVector = if (isBlocked) Icons.Default.Block else Icons.Default.VerifiedUser,
                                        contentDescription = if (isBlocked) "Unblock Number" else "Block Number",
                                        tint = if (isBlocked) Error else Success
                                    )
                                }
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (!isNonPhoneScan) {
                Surface(
                    modifier = Modifier.fillMaxWidth().glassy(blur = 30.dp, radius = 0.dp).padding(bottom = 8.dp).navigationBarsPadding(),
                    color = topBarScrim(0.4f),
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { onMakeCall(phoneNumber) },
                            modifier = Modifier.size(72.dp).shadow(16.dp, CircleShape),
                            shape = CircleShape,
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Brush.linearGradient(colors = listOf(GradientStart, GradientEnd))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Call, "Call", tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }
                }
            }
        ) { innerPadding ->
            val enr = enrichment
            val hasPartial = caller != null || livePartial != null ||
                (enr != null && (!enr.publicName.isNullOrBlank() || !enr.profileImageUrl.isNullOrBlank()))
            if (displayIdentifier.isBlank() && caller == null) {
                var timedOut by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(20000)
                    timedOut = true
                }
                if (!timedOut) {
                    InfoCallerLoading(isFullScreen = true, text = "Identifying...")
                } else {
                    Column(
                        modifier = Modifier.padding(innerPadding).fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(if (isNonPhoneScan) "Couldn't identify this identifier" else "Couldn't identify this number", style = MaterialTheme.typography.titleMedium, color = contentPrimary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The lookup timed out or nothing was provided.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentSecondary(0.6f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            timedOut = false
                            if (displayIdentifier.isNotBlank()) viewModel.searchNumber(displayIdentifier)
                        }) { Text("Retry") }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onBack) { Text("Go back", color = Primary) }
                    }
                }
            } else if (!hasPartial && scanActive) {
                Column(
                    modifier = Modifier.padding(innerPadding).fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    InfoCallerLoading(isFullScreen = false, text = "Identifying...")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        displayIdentifier.ifBlank { rawIdentifier },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Primary,
                        textAlign = TextAlign.Center,
                    )
                    val runningStep = scanSteps.lastOrNull { it.status == "RUNNING" }
                    Text(
                        runningStep?.let { "Asking ${it.providerName}…" } ?: "Starting lookup tools…",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentSecondary(0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val live = livePartial
                    val liveSocials = remember(live) {
                        try {
                            (live?.socialProfiles.orEmpty())
                        } catch (_: Exception) { emptyList() }
                    }
                    val savedName = contact?.displayName
                    val callerIdName = live?.name?.takeIf { !ContactUtils.isPlaceholderName(it) }
                        ?: enrichment?.publicName ?: caller?.displayName
                    val displayName = savedName ?: callerIdName ?: displayIdentifier.ifBlank { "" }
                    val extraName = when {
                        !savedName.isNullOrBlank() && !callerIdName.isNullOrBlank() && !savedName.equals(callerIdName, ignoreCase = true) -> callerIdName
                        else -> null
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    val allPhotos = remember(
                        live?.imageUrl, live?.photoCandidates,
                        enrichment?.profileImageUrl, enrichment?.profileImageSource,
                        enrichment?.photoCandidatesJson, enrichment?.socialProfilesJson,
                        caller?.photoUrl
                    ) {
                        val seen = linkedSetOf<String>()
                        val out = mutableListOf<com.infocaller.app.domain.model.PhotoCandidate>()
                        fun add(url: String?, provider: String, priority: Int) {
                            val u = url?.trim().orEmpty()
                            if (!u.startsWith("http") || !seen.add(u)) return
                            out.add(com.infocaller.app.domain.model.PhotoCandidate(provider = provider, url = u, sourcePriority = priority))
                        }
                        live?.imageUrl?.let { add(it, "scan", 100) }
                        live?.photoCandidates?.forEach { add(it.url, it.provider, it.sourcePriority) }
                        add(enrichment?.profileImageUrl, enrichment?.profileImageSource ?: "cache", 90)
                        try {
                            SocialUtils.photosFromJson(enrichment?.photoCandidatesJson).forEach { add(it.url, it.provider, it.sourcePriority) }
                        } catch (_: Exception) { }
                        try {
                            SocialUtils.fromJson(enrichment?.socialProfilesJson).mapNotNull { it.avatarUrl }.forEach { add(it, "social", 10) }
                        } catch (_: Exception) { }
                        add(caller?.photoUrl, "scan", 5)
                        out
                    }
                    val primaryPhoto = contact?.photoUri
                        ?: allPhotos.firstOrNull()?.url
                    val headerPhoto = primaryPhoto ?: live?.imageUrl?.takeIf { it.startsWith("http") } ?: SocialUtils.bestHttpPhoto(
                        enrichment?.profileImageUrl,
                        enrichment?.photoCandidatesJson,
                        enrichment?.socialProfilesJson,
                        caller?.photoUrl
                    )
                    Box(modifier = Modifier.size(140.dp).glassy(radius = 70.dp).shadow(24.dp, CircleShape), contentAlignment = Alignment.Center) {
                        val photoUrl = headerPhoto
                        if (photoUrl != null) {
                            AsyncImage(
                                model = photoUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                error = rememberVectorPainter(Icons.Default.Person),
                                placeholder = rememberVectorPainter(Icons.Default.Person)
                            )
                        } else {
                            val initials = ContactUtils.getInitials(displayName)
                            Text(initials, style = MaterialTheme.typography.displayLarge, color = Primary)
                        }
                        if (enrichment?.profileImageSource != null) {
                            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                                SourceBadge(enrichment?.profileImageSource)
                            }
                        }
                    }
                    if (allPhotos.size > 1) {
                        Spacer(modifier = Modifier.height(12.dp))
                        DetailSection("Profile Photos (${allPhotos.size})") {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "Tap a photo to set it as the profile picture",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentSecondary(0.6f),
                                )
                                allPhotos.chunked(3).forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        row.forEach { candidate ->
                                            val isPrimary = candidate.url == primaryPhoto
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .border(
                                                        width = if (isPrimary) 3.dp else 1.dp,
                                                        color = if (isPrimary) Primary else contentSecondary(0.25f),
                                                        shape = RoundedCornerShape(14.dp)
                                                    )
                                                    .clickable {
                                                        if (!isPrimary) {
                                                            viewModel.setPrimaryPhoto(
                                                                lookupKey.ifBlank { displayIdentifier },
                                                                candidate.url,
                                                                candidate.provider
                                                            )
                                                            scope.launch {
                                                                snackbarHostState.showSnackbar("Profile photo updated")
                                                            }
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                AsyncImage(
                                                    model = candidate.url,
                                                    contentDescription = "${candidate.provider} photo",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop,
                                                    error = rememberVectorPainter(Icons.Default.Person),
                                                    placeholder = rememberVectorPainter(Icons.Default.Person)
                                                )
                                                if (isPrimary) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.TopStart)
                                                            .padding(6.dp)
                                                            .background(Primary, RoundedCornerShape(8.dp))
                                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                                    ) {
                                                        Text(
                                                            "PRIMARY",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Color.White,
                                                            fontWeight = FontWeight.Bold,
                                                        )
                                                    }
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomCenter)
                                                            .padding(6.dp)
                                                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                                    ) {
                                                        Text(
                                                            candidate.provider.take(14),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Color.White,
                                                            maxLines = 1,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        repeat(3 - row.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                                Text(
                                    "Other photos (${(allPhotos.size - 1).coerceAtLeast(0)}) stay here — switch anytime",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentSecondary(0.5f),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    CopyableText(
                        text = displayName,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        align = TextAlign.Center,
                        color = contentPrimary,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        onCopy = { copyField("Name", displayName) },
                    )
                    SourceBadge(enrichment?.publicNameSource)
                    extraName?.let {
                        CopyableText(
                            text = "Caller ID: $it",
                            style = MaterialTheme.typography.titleMedium,
                            color = contentSecondary(0.85f),
                            modifier = Modifier.padding(top = 6.dp),
                            align = TextAlign.Center,
                            onCopy = { copyField("Caller ID", it) },
                        )
                    }
                    val liveAlt = live?.alternateName
                    val altName = liveAlt?.takeIf { it != displayName && it != extraName }
                        ?: enrichment?.alternateName?.takeIf { it != displayName && it != extraName }
                    if (!altName.isNullOrBlank()) {
                        CopyableText(
                            text = "aka $altName",
                            style = MaterialTheme.typography.bodyLarge,
                            color = contentSecondary(0.6f),
                            modifier = Modifier.padding(top = 4.dp),
                            onCopy = { copyField("Also known as", altName) },
                        )
                    }
                    val allAltNames = remember(enrichment?.alternateNamesJson, live?.alternateNames) {
                        val merged = mutableMapOf<String, MutableList<String>>()
                        try {
                            SocialUtils.altNamesFromJson(enrichment?.alternateNamesJson).forEach { (k, v) ->
                                merged.getOrPut(k) { mutableListOf() }.addAll(v)
                            }
                        } catch (_: Exception) { }
                        try {
                            live?.alternateNames?.forEach { (k, v) ->
                                merged.getOrPut(k) { mutableListOf() }.addAll(v)
                            }
                        } catch (_: Exception) { }
                        merged.mapValues { it.value.distinct().take(4) }
                            .filter { (k, _) -> k != displayName && k != extraName && k != altName }
                            .toList().sortedByDescending { it.second.size }.take(6)
                    }
                    if (allAltNames.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(top = 8.dp).padding(horizontal = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            allAltNames.forEach { (name, sources) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.combinedClickable(
                                        onClick = { copyField("Name", name) },
                                        onLongClick = { copyField("Name", name) },
                                    ).padding(vertical = 2.dp),
                                ) {
                                    Text(
                                        "• $name",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentSecondary(0.75f),
                                        textAlign = TextAlign.Center,
                                    )
                                    if (sources.size > 1) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "×${sources.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Primary.copy(alpha = 0.8f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val numberText = if (isNonPhoneScan) displayIdentifier else com.infocaller.app.util.PhoneNumberUtils.formatAsYouType(phoneNumber)
                    CopyableText(
                        text = numberText,
                        style = MaterialTheme.typography.titleLarge,
                        color = Primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                        onCopy = { copyField(if (isNonPhoneScan) "Identifier" else "Number", numberText) },
                    )
                    val aboutText = live?.about?.takeIf { it.isNotBlank() } ?: enrichment?.about
                    if (!aboutText.isNullOrBlank()) {
                        val aboutParts = remember(aboutText) { splitAboutText(aboutText) }
                        Box(modifier = Modifier.padding(top = 24.dp).padding(horizontal = 32.dp).glassy(radius = 16.dp).padding(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("ABOUT", style = MaterialTheme.typography.labelSmall, color = contentSecondary(0.4f))
                                    SourceBadge(enrichment?.aboutSource)
                                }
                                aboutParts.texts.forEach { sentence ->
                                    CopyableText(
                                        text = sentence,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = contentPrimary,
                                        align = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                        onCopy = { copyField("About", sentence) },
                                    )
                                }
                                aboutParts.links.forEach { link ->
                                    EmbeddedLinkCard(
                                        label = link.label,
                                        url = link.url,
                                        onOpen = {
                                            try {
                                                context.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(link.url)
                                                    )
                                                )
                                            } catch (_: Exception) { }
                                        },
                                        onCopy = { copyField(link.label, link.url) },
                                    )
                                }
                            }
                        }
                    }
                    val placeCandidates = remember(callerIdName, displayName) {
                        VillageResolver.extractPlaceCandidates(callerIdName ?: displayName)
                    }
                    var villages by remember(placeCandidates) {
                        mutableStateOf<List<com.infocaller.app.util.VillageResolver.ResolvedVillage>>(emptyList())
                    }
                    var villagesLoading by remember(placeCandidates) { mutableStateOf(placeCandidates.isNotEmpty()) }
                    LaunchedEffect(placeCandidates, callerIdName, displayName) {
                        if (placeCandidates.isEmpty()) { villages = emptyList(); villagesLoading = false; return@LaunchedEffect }
                        villagesLoading = true
                        val out = mutableListOf<com.infocaller.app.util.VillageResolver.ResolvedVillage>()
                        for (c in placeCandidates.take(3)) {
                            try {
                                val cachedHit = VillageResolver.cachedResolved(context, c.repaired)
                                if (cachedHit != null) {
                                    if (out.none { it.query.equals(c.repaired, true) }) out.add(cachedHit)
                                } else if (out.none { it.query.equals(c.repaired, true) }) {
                                    val key = VillageResolver.cacheKey(callerIdName ?: displayName ?: "", c.raw)
                                    if (!VillageResolver.wasSeen(context, key)) {
                                        VillageResolver.markSeen(context, key)
                                        val hit = VillageResolver.resolve(context, c.repaired)
                                            ?: if (c.wasRepaired) null else VillageResolver.resolve(context, c.raw)
                                        if (hit != null) { VillageResolver.storeResolved(context, c.repaired, hit); out.add(hit) }
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                        villages = out
                        villagesLoading = false
                    }
                    if (villagesLoading && placeCandidates.isNotEmpty()) {
                        DetailSection("Locations From Name") {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Resolving place names…", style = MaterialTheme.typography.bodySmall, color = contentSecondary(0.6f))
                            }
                        }
                    }
                    if (villages.isNotEmpty()) {
                        DetailSection("Locations From Name (${villages.size})") {
                            villages.forEach { v ->
                                val repairedNote = if (!v.query.equals(placeCandidates.find { it.repaired == v.query }?.raw, ignoreCase = true)) " (repaired)" else ""
                                DetailRow(Icons.Default.Place, "Name place: \"${v.query}\"$repairedNote", v.display, "Maps (Nominatim)", onCopy = { copyField("Name location", v.display) })
                                val mapsUrl = "https://www.google.com/maps/search/?api=1&query=${java.net.URLEncoder.encode(v.display + ", Bangladesh", "UTF-8")}"
                                DetailRow(
                                    Icons.Default.Map, "Open in Maps", v.display,
                                    trailingContent = {
                                        TextButton(onClick = {
                                            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(mapsUrl))) } catch (_: Exception) { }
                                        }) { Text("View") }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    DetailSection("Public Information") {
                        val cachedLoc = remember { com.infocaller.app.util.UserLocationResolver.cached(context) }
                        val liveLoc = LocationUtils.formatCallerLocation(live?.city, live?.region, live?.country)
                        val location = liveLoc.ifBlank {
                            LocationUtils.formatCallerLocation(enrichment?.city, enrichment?.region, enrichment?.country)
                        }
                        val myLoc = cachedLoc?.display()?.takeIf { it.isNotBlank() }
                        if (!myLoc.isNullOrBlank()) DetailRow(Icons.Default.MyLocation, "Your location (on this device)", myLoc, "SIM / IP", onCopy = { copyField("Your location", myLoc) })
                        val locatedSources = remember(live?.city, live?.region, live?.country, live?.nid, enrichment?.city, enrichment?.region, enrichment?.country, enrichment?.nid, location) {
                            LocationUtils.allLocatedSources(
                                city = live?.city?.takeIf { it.isNotBlank() } ?: enrichment?.city,
                                region = live?.region?.takeIf { it.isNotBlank() } ?: enrichment?.region,
                                country = live?.country?.takeIf { it.isNotBlank() } ?: enrichment?.country,
                                nidAddress = live?.nid?.takeIf { it.isNotBlank() } ?: enrichment?.nid,
                                emailLocation = live?.email?.takeIf { it.isNotBlank() } ?: enrichment?.email,
                                simRegion = null,
                                displayFallback = location.ifBlank { null },
                            ).filterNot { it.label == "Caller location" && location.isBlank() }
                        }
                        if (locatedSources.isNotEmpty()) {
                            locatedSources.forEachIndexed { i, src ->
                                val label = if (i == 0) "Caller location" else src.label
                                DetailRow(Icons.Default.LocationOn, label, src.value, enrichment?.source ?: lastProvider, onCopy = { copyField(label, src.value) })
                            }
                        } else if (location.isNotBlank()) DetailRow(Icons.Default.LocationOn, "Location", location, onCopy = { copyField("Location", location) })
                        (live?.timezone ?: enrichment?.timezone)?.let { DetailRow(Icons.Default.Schedule, "Timezone", it, onCopy = { v -> copyField("Timezone", v) }) }

                        val carrierName = live?.carrier?.takeIf { it.isNotBlank() } ?: enrichment?.carrier ?: caller?.carrier
                        val simInfos by viewModel.simInfos.collectAsState()

                        if (!carrierName.isNullOrBlank()) DetailRow(
                            icon = Icons.Default.CellTower,
                            label = "Carrier",
                            value = carrierName,
                            onCopy = { v -> copyField("Carrier", v) },
                            trailingContent = {
                                val matchedSim = remember(carrierName, simInfos) {
                                    simInfos.find {
                                        it.carrierName.equals(carrierName, ignoreCase = true) ||
                                            it.carrierName.contains(carrierName, ignoreCase = true) ||
                                            carrierName.contains(it.carrierName, ignoreCase = true)
                                    }
                                }
                                if (matchedSim != null) {
                                    com.infocaller.app.ui.dialogs.SimLogo(
                                        sim = matchedSim,
                                        modifier = Modifier.size(24.dp).clip(CircleShape),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        )

                        (live?.lineType ?: enrichment?.lineType)?.let { DetailRow(Icons.Default.PhoneIphone, "Line Type", it, onCopy = { v -> copyField("Line type", v) }) }
                        if (live?.isBusiness == true || enrichment?.isBusiness == true) DetailRow(Icons.Default.Business, "Type", "Verified Business")
                        (live?.email?.takeIf { it.isNotBlank() } ?: enrichment?.email)?.let {
                            DetailRow(Icons.Default.Email, "Email", it, live?.email?.let { "scan" } ?: enrichment?.emailSource, onCopy = { v -> copyField("Email", v) })
                        }
                        if (enrichment?.about?.contains("breach", ignoreCase = true) == true || enrichment?.source?.contains("Dark Web", ignoreCase = true) == true) {
                            DetailRow(Icons.Default.Warning, "Security Alert", "Found in data breaches or dark web", "Forensic")
                        }
                        if (enrichment?.lastChecked != null && enrichment?.lastChecked != 0L) {
                            val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(enrichment!!.lastChecked))
                            DetailRow(Icons.Default.Update, "Last Updated", date)
                        }
                        live?.city?.takeIf { it.isNotBlank() && it != enrichment?.city }?.let {
                            DetailRow(Icons.Default.LocationCity, "City (live)", it, enrichment?.source ?: lastProvider, onCopy = { v -> copyField("City", v) })
                        }
                        live?.country?.takeIf { it.isNotBlank() && it != enrichment?.country }?.let {
                            DetailRow(Icons.Default.Public, "Country (live)", it, enrichment?.source ?: lastProvider, onCopy = { v -> copyField("Country", v) })
                        }
                    }
                    val deepNid = live?.nid?.takeIf { it.isNotBlank() } ?: enrichment?.nid
                    val deepDob = live?.dob?.takeIf { it.isNotBlank() } ?: enrichment?.dob
                    if (enrichment?.plateNumber != null || enrichment?.iban != null || enrichment?.vatId != null || enrichment?.macAddress != null || deepNid != null || enrichment?.source?.contains("Dark Web", ignoreCase = true) == true) {
                        DetailSection("Deep Web Intelligence") {
                            if (enrichment?.source?.contains("Dark Web", ignoreCase = true) == true) {
                                DetailRow(Icons.Default.VisibilityOff, "Exposure", "Mentioned in hidden services", "Dark Web Recon")
                            }
                            deepNid?.let { nid ->
                                DetailRow(Icons.Default.Fingerprint, "National ID", nid, "BD Database", onCopy = { v -> copyField("National ID", v) })
                            }
                            deepDob?.let { DetailRow(Icons.Default.Cake, "Date of Birth", it, "BD Database", onCopy = { v -> copyField("Date of Birth", v) }) }
                            enrichment?.plateNumber?.let { DetailRow(Icons.Default.DirectionsCar, "License Plate", it, enrichment?.plateNumberSource, onCopy = { v -> copyField("License Plate", v) }) }
                            enrichment?.iban?.let { DetailRow(Icons.Default.AccountBalance, "IBAN", it, enrichment?.ibanSource, onCopy = { v -> copyField("IBAN", v) }) }
                            enrichment?.vatId?.let { DetailRow(Icons.Default.CorporateFare, "VAT ID", it, enrichment?.vatIdSource, onCopy = { v -> copyField("VAT ID", v) }) }
                            enrichment?.macAddress?.let { DetailRow(Icons.Default.Router, "MAC Address", it, enrichment?.macAddressSource, onCopy = { v -> copyField("MAC Address", v) }) }
                        }
                    }
                    val socialProfiles = remember(enrichment?.socialProfilesJson, liveSocials) {
                        val cached = SocialUtils.filteredUsedProfiles(SocialUtils.fromJson(enrichment?.socialProfilesJson))
                        if (liveSocials.isEmpty()) cached
                        else {
                            val merged = cached.toMutableList()
                            for (p in liveSocials) {
                                val key = (p.platform.lowercase() + "|" + (p.profileUrl?.lowercase().orEmpty())).take(300)
                                if (merged.none {
                                        (it.platform.lowercase() + "|" + (it.profileUrl?.lowercase().orEmpty())).take(300) == key
                                    }
                                ) merged.add(p)
                            }
                            SocialUtils.filteredUsedProfiles(merged)
                        }
                    }
                    val waNumber = remember(phoneNumber, isNonPhoneScan) {
                        if (isNonPhoneScan) "" else phoneNumber.filter { it.isDigit() }
                    }
                    val allSocialProfiles = remember(socialProfiles, waNumber) {
                        val merged = socialProfiles.toMutableList()
                        if (waNumber.length >= 7 &&
                            merged.none { it.platform.equals("whatsapp", ignoreCase = true) }
                        ) {
                            merged.add(
                                SocialProfile(
                                    platform = "WhatsApp",
                                    username = waNumber,
                                    profileUrl = "https://wa.me/$waNumber",
                                    status = com.infocaller.app.domain.model.SocialLookupStatus.POSSIBLE_MATCH,
                                    source = "wa.me"
                                )
                            )
                        }
                        val (messaging, social) = merged.partition {
                            it.platform.equals("whatsapp", true) || it.platform.equals("telegram", true)
                        }
                        social.sortedBy { it.platform.lowercase() } +
                            messaging.sortedBy { it.platform.lowercase() }
                    }
                    LaunchedEffect(allSocialProfiles) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try { SocialUtils.prefetchLogos(context, allSocialProfiles.map { it.platform }) } catch (_: Exception) { }
                        }
                    }
                    if (allSocialProfiles.isNotEmpty()) {
                        DetailSection("Linked Accounts (${allSocialProfiles.size})") {
                            Column(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                allSocialProfiles.chunked(4).forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        row.forEach { profile -> SocialIcon(profile) }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                allSocialProfiles.forEach { profile ->
                                    val url = profile.profileUrl.orEmpty()
                                    if (url.isNotBlank()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = { copyField(profile.platform, url) },
                                                    onLongClick = { copyField(profile.platform, url) },
                                                )
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                profile.platform,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Primary,
                                                modifier = Modifier.width(96.dp),
                                            )
                                            Text(
                                                profile.username?.takeIf { it.isNotBlank() } ?: url,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = contentPrimary,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                            )
                                            Icon(
                                                Icons.Default.ContentCopy, null,
                                                tint = contentSecondary(0.5f),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "Tap any account to copy its link",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentSecondary(0.45f),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        }
        if (showAddContactDialog) {
            com.infocaller.app.ui.dialogs.AddContactBottomSheet(viewModel = viewModel, phoneNumber = phoneNumber, initialName = enrichment?.publicName ?: caller?.displayName ?: "", onDismiss = { showAddContactDialog = false }) { showAddContactDialog = false }
        }
    }
}

@Composable
fun SourceBadge(source: String?) {
    if (source.isNullOrBlank()) return
    val icon = when {
        source.contains("Truecaller", ignoreCase = true) -> Icons.Default.Verified
        source.contains("WhatsApp", ignoreCase = true) -> Icons.AutoMirrored.Filled.Chat
        source.contains("Gravatar", ignoreCase = true) -> Icons.Default.Face
        source.contains("Apify", ignoreCase = true) -> Icons.Default.Memory
        source.contains("Pivot", ignoreCase = true) -> Icons.AutoMirrored.Filled.AltRoute
        source.contains("Forensic", ignoreCase = true) -> Icons.Default.Gavel
        source.contains("Dark Web", ignoreCase = true) -> Icons.Default.VisibilityOff
        else -> Icons.Default.Info
    }
    Surface(color = faintTint(0.1f), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(start = 4.dp)) {
        Icon(icon, null, tint = Primary.copy(alpha = 0.7f), modifier = Modifier.size(14.dp).padding(2.dp))
    }
}

@Composable
fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelMedium, color = Primary, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        Box(modifier = Modifier.fillMaxWidth().glassy(radius = 24.dp)) {
            Column(content = content)
        }
    }
}

@Composable
fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    source: String? = null,
    trailingContent: @Composable (() -> Unit)? = null,

    onCopy: ((String) -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Primary.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f).let {
                if (onCopy != null) it.clickable { onCopy(value) } else it
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = contentSecondary(0.5f))
                SourceBadge(source)
            }
            Text(value, style = MaterialTheme.typography.bodyLarge, color = contentPrimary)
        }
        if (onCopy != null) {
            IconButton(onClick = { onCopy(value) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label", tint = contentSecondary(0.55f), modifier = Modifier.size(18.dp))
            }
        }
        if (trailingContent != null) {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                trailingContent()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CopyableText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    onCopy: () -> Unit,
) {
    Text(
        text = text,
        style = style,
        fontWeight = fontWeight,
        color = color,
        textAlign = align,
        maxLines = maxLines,
        modifier = modifier.combinedClickable(
            onClick = onCopy,
            onLongClick = onCopy,
        ),
    )
}

data class AboutLink(val label: String, val url: String)

data class AboutParts(val texts: List<String>, val links: List<AboutLink>)

fun splitAboutText(about: String): AboutParts {
    val urlRegex = Regex("""(https?://[^\s•|]+|www\.[^\s•|]+|[a-z0-9-]+(?:\.[a-z0-9-]+)+\.[a-z]{2,}(?:/[^\s•|]*)?)""", RegexOption.IGNORE_CASE)
    val links = mutableListOf<AboutLink>()
    val seen = mutableSetOf<String>()
    val labelRegex = Regex("""([A-Za-z][A-Za-z0-9 ._-]{1,40}?):\s*$""")
    val textRanges = mutableListOf<Pair<Int, Int>>()
    var lastEnd = 0
    for (m in urlRegex.findAll(about)) {
        var raw = m.value.trimEnd('.', ',', ')', ']', '"', '\'')
        if (raw.isBlank()) continue
        val url = when {
            raw.startsWith("http", true) -> raw
            raw.startsWith("www.", true) -> "https://$raw"
            else -> "https://$raw"
        }
        if (!seen.add(url.lowercase())) continue
        val before = about.substring(lastEnd, m.range.first)
        val labelMatch = labelRegex.find(before)
        val label = labelMatch?.groupValues?.getOrNull(1)?.trim()?.take(40)
            ?: hostLabel(url)
        links.add(AboutLink(label.ifBlank { hostLabel(url) }, url))
        textRanges.add(lastEnd to m.range.first)
        lastEnd = m.range.first + m.value.length
    }
    textRanges.add(lastEnd to about.length)
    val texts = textRanges.mapNotNull { (s, e) ->
        if (s >= e) return@mapNotNull null
        var t = about.substring(s, e)
            .replace(Regex("""([A-Za-z][A-Za-z0-9 ._-]{1,40}?):\s*$"""), "")
            .replace(Regex("""^[•|·\-–—\s]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '•', '|', '·', '-', '–', '—', '.', ',', ';', ':')
            .trim()
        if (t.length < 3) null else t
    }.distinct()
    return AboutParts(texts, links)
}

private fun hostLabel(url: String): String {
    return try {
        val host = android.net.Uri.parse(url).host.orEmpty().lowercase()
            .removePrefix("www.")
        when {
            host.contains("lens.google") -> "Google Lens"
            host.contains("tineye") -> "TinEye"
            host.contains("bing.com") -> "Bing Visual"
            host.contains("pimeyes") -> "Pimeyes"
            host.contains("facecheck") -> "FaceCheck"
            host.contains("facebook") -> "Facebook"
            host.contains("instagram") -> "Instagram"
            host.contains("tiktok") -> "TikTok"
            host.contains("youtube") -> "YouTube"
            host.contains("github") -> "GitHub"
            host.contains("telegram") || host == "t.me" -> "Telegram"
            host.contains("wa.me") || host.contains("whatsapp") -> "WhatsApp"
            host.contains("epios") -> "EPIOS"
            host.contains("intelx") -> "IntelligenceX"
            host.contains("dehashed") -> "Dehashed"
            host.contains("ahmia") -> "Ahmia"
            host.isBlank() -> "Open link"
            else -> host.replaceFirstChar { it.uppercase() }.substringBefore("/").take(24)
        }
    } catch (_: Exception) { "Open link" }
}

@Composable
fun EmbeddedLinkCard(
    label: String,
    url: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        color = Primary.copy(alpha = 0.12f)
    ) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.OpenInNew, null, tint = Primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = contentPrimary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    try { android.net.Uri.parse(url).host.orEmpty().ifBlank { url.take(48) } } catch (_: Exception) { url.take(48) },
                    color = contentSecondary(0.55f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label link", tint = contentSecondary(0.55f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun SocialIcon(profile: SocialProfile) {
    val context = LocalContext.current
    val brand = remember(profile.platform) {
        when (profile.platform.lowercase()) {
            "whatsapp" -> Color(0xFF25D366)
            "telegram" -> Color(0xFF229ED9)
            "facebook" -> Color(0xFF1877F2)
            "instagram" -> Color(0xFFE1306C)
            "linkedin" -> Color(0xFF0A66C2)
            "twitter", "x" -> Color(0xFF1D9BF0)
            "youtube" -> Color(0xFFFF0000)
            "tiktok" -> Color(0xFF69C9D0)
            "github" -> Color(0xFF9E9E9E)
            "reddit" -> Color(0xFFFF4500)
            "pinterest" -> Color(0xFFE60023)
            "medium" -> Color(0xFF00AB6C)
            "steam" -> Color(0xFF1B78D3)
            "twitch" -> Color(0xFF9146FF)
            "chess.com", "chess" -> Color(0xFF7FA650)
            "soundcloud" -> Color(0xFFFF5500)
            "spotify" -> Color(0xFF1DB954)
            "discord" -> Color(0xFF5865F2)
            "gitlab" -> Color(0xFFFC6D26)
            "devto", "dev.to", "kaggle", "hashnode" -> Color(0xFF0A0A0A)
            else -> Color(0xFFFBBF24)
        }
    }
    var logoFailed by remember(profile.platform) { mutableStateOf(false) }
    val logoModel = remember(profile.platform) {
        try { SocialUtils.logoModel(context, profile.platform) } catch (_: Exception) { SocialUtils.getLogoUrl(profile.platform) }
    }
    Surface(onClick = {
        try {
            SocialUtils.openSocialProfile(context, profile)
        } catch (_: Exception) { }
    }, modifier = Modifier.size(48.dp), shape = CircleShape, color = faintTint(0.1f), border = BorderStroke(1.dp, faintTint(0.1f))) {
        Box(contentAlignment = Alignment.Center) {
            if (!logoFailed) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = "${profile.platform} — open account",
                    modifier = Modifier.size(28.dp).clip(CircleShape),
                    contentScale = ContentScale.Fit,
                    placeholder = rememberVectorPainter(Icons.Default.Share),
                    error = rememberVectorPainter(Icons.Default.Share),
                    onError = { logoFailed = true }
                )
            }
            if (logoFailed) {
                Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(brand), contentAlignment = Alignment.Center) {
                    Text(profile.platform.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
