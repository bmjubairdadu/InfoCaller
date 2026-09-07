package com.infocaller.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
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
    // Non-phone scans (email/username) carry their own identifier — phone
    // normalization would strip/mangle them. Detect first so every lookup
    // below can route. Reads ONLY the scan result now that search no longer
    // writes into dialerInput (dial pad keeps its own typed text).
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
    // Lookup key: raw lowercased identifier for non-phone scans (matches
    // repository rows), E.164 number for phone scans.
    val lookupKey = remember(rawIdentifier, phoneNumber, isNonPhoneScan) {
        if (isNonPhoneScan) rawIdentifier.trim().lowercase().removePrefix("@") else phoneNumber
    }
    // Identifier shown/exported: raw handle/address for non-phone scans.
    val displayIdentifier = remember(rawIdentifier, phoneNumber, isNonPhoneScan) {
        if (isNonPhoneScan) rawIdentifier.trim() else phoneNumber
    }
    val enrichment by viewModel.getEnrichment(lookupKey).collectAsState(initial = null)
    val isBlocked = !isNonPhoneScan && blocklist.contains(phoneNumber)
    val contactsList by viewModel.contacts.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
    val isOnline by app.enrichmentEngine.isOnline.collectAsState()
    
    val contact = remember(phoneNumber, contactsList, isNonPhoneScan) {
        if (isNonPhoneScan) null else contactsList.find { it.phoneNumber == phoneNumber }
    }
    val isContact = contact != null
    var showAddContactDialog by remember { mutableStateOf(false) }
    val scanSteps by viewModel.scanSteps.collectAsState()
    val scanActive by viewModel.scanActive.collectAsState()
    var scanPopupVisible by remember { mutableStateOf(false) }
    // Auto-open the popup when a fresh scan starts; auto-close it when the
    // scan completes so the result is revealed underneath.
    LaunchedEffect(scanActive) {
        if (scanActive) scanPopupVisible = true
        else scanPopupVisible = false
    }
    GlassyBackground {
        if (scanPopupVisible && (scanActive || scanSteps.isNotEmpty())) {
            com.infocaller.app.ui.components.ScanProgressPopup(
                identifier = displayIdentifier.ifBlank { rawIdentifier },
                steps = scanSteps,
                scanActive = scanActive,
                onDismiss = {
                    scanPopupVisible = false
                    if (scanActive) viewModel.dismissScanPopup()
                }
            )
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent,
            topBar = {
                Surface(
                    color = Color.Black.copy(alpha = 0.3f),
                    modifier = Modifier.glassy(blur = 20.dp, radius = 0.dp)
                ) {
                    TopAppBar(
                        title = { 
                            Column {
                                Text(if (isEmailScan) "Email Identity" else if (isUsernameScan) "Username Identity" else if (isContact) "Contact Details" else "Caller Identity", color = Color.White, style = MaterialTheme.typography.titleMedium)
                                if (isLive) {
                                    Text(text = "Live scan: ${lastProvider ?: "Searching..."}", style = MaterialTheme.typography.labelSmall, color = Primary)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        windowInsets = WindowInsets.statusBars,
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                                    color = Color.White.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CloudOff, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Offline", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                                    }
                                }
                            }
                            // Blocking is phone-only; non-phone scans have no block action.
                            if (!isNonPhoneScan) {
                                IconButton(onClick = {
                                    if (isBlocked) viewModel.unblockNumber(phoneNumber)
                                    else viewModel.blockNumber(phoneNumber)
                                }) {
                                    Icon(
                                        imageVector = if (isBlocked) Icons.Default.Block else Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        tint = if (isBlocked) Error else Success
                                    )
                                }
                            }
                        }
                    )
                }
            },
            // No call bar for non-phone scans — nothing to dial.
            bottomBar = {
                if (!isNonPhoneScan) {
                Surface(
                    modifier = Modifier.fillMaxWidth().glassy(blur = 30.dp, radius = 0.dp).padding(bottom = 8.dp).navigationBarsPadding(),
                    color = Color.Black.copy(alpha = 0.4f),
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
                                Icon(Icons.Default.Call, "Call", tint = Color.Black, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }
                }
            }
        ) { innerPadding ->
            if (displayIdentifier.isBlank() && caller == null) {
                // Stale/empty navigation (deep link, process death, lost race) must not
                // spin forever — time out with a retry path after 20s.
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
                        Text(if (isNonPhoneScan) "Couldn't identify this identifier" else "Couldn't identify this number", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The lookup timed out or nothing was provided.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.6f)
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
            } else {
                Column(
                    modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Saved-name first, caller-ID name second; show both when they differ
                    // (original never hidden). Every non-name field always renders from
                    // enrichment/caller; nothing stays hidden because it can't go into
                    // ContactsContract. Do not mutate the phonebook name.
                    val savedName = contact?.displayName
                    val callerIdName = enrichment?.publicName ?: caller?.displayName
                    val displayName = savedName ?: callerIdName ?: displayIdentifier.ifBlank { "" }
                    val extraName = when {
                        !savedName.isNullOrBlank() && !callerIdName.isNullOrBlank() && !savedName.equals(callerIdName, ignoreCase = true) -> callerIdName
                        else -> null
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Box(modifier = Modifier.size(140.dp).glassy(radius = 70.dp).shadow(24.dp, CircleShape), contentAlignment = Alignment.Center) {
                        val photoUrl = contact?.photoUri ?: enrichment?.profileImageUrl
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
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = displayName, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp))
                        SourceBadge(enrichment?.publicNameSource)
                    }
                    extraName?.let {
                        Text(
                            text = "Caller ID: $it",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 6.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (!enrichment?.alternateName.isNullOrBlank() && enrichment?.alternateName != displayName && enrichment?.alternateName != extraName) {
                        Text(text = "aka ${enrichment!!.alternateName}", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(text = if (isNonPhoneScan) displayIdentifier else com.infocaller.app.util.PhoneNumberUtils.formatAsYouType(phoneNumber), style = MaterialTheme.typography.titleLarge, color = Primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    if (!enrichment?.about.isNullOrBlank()) {
                        Box(modifier = Modifier.padding(top = 24.dp).padding(horizontal = 32.dp).glassy(radius = 16.dp).padding(16.dp)) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("ABOUT", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.4f))
                                    SourceBadge(enrichment?.aboutSource)
                                }
                                Text(text = enrichment!!.about!!, style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                        }
                    }
                    // Village resolver: when the display name embeds a place token,
                    // show its BD administrative location disambiguated via Maps.
                    val placeToken = remember(callerIdName, displayName) {
                        VillageResolver.extractPlaceToken(callerIdName ?: displayName)
                    }
                    var village by remember(placeToken) { mutableStateOf<com.infocaller.app.util.VillageResolver.ResolvedVillage?>(null) }
                    LaunchedEffect(placeToken) {
                        val t = placeToken ?: return@LaunchedEffect
                        val key = VillageResolver.cacheKey(callerIdName ?: displayName ?: "", t)
                        if (VillageResolver.wasSeen(context, key)) return@LaunchedEffect
                        VillageResolver.markSeen(context, key)
                        village = VillageResolver.resolve(context, t)
                    }
                    village?.let { v ->
                        DetailSection("Possible Location From Name") {
                            DetailRow(Icons.Default.Place, "Village token: \"${v.query}\"", v.display, "Maps (Nominatim)")
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
                    Spacer(modifier = Modifier.height(32.dp))
                    DetailSection("Public Information") {
                        val cachedLoc = remember { com.infocaller.app.util.UserLocationResolver.cached(context) }
                        val location = LocationUtils.formatCallerLocation(enrichment?.city, enrichment?.region, enrichment?.country)
                        // User's own location (if granted) shown so callees can see it.
                        val myLoc = cachedLoc?.display()?.takeIf { it.isNotBlank() }
                        if (!myLoc.isNullOrBlank()) DetailRow(Icons.Default.MyLocation, "Your location (on this device)", myLoc, "SIM / IP")
                        if (village != null && location.isNotBlank()) {
                            // Keep both: derived village + device-derived location
                            DetailRow(Icons.Default.LocationOn, "Caller location", location)
                        } else if (location.isNotBlank()) DetailRow(Icons.Default.LocationOn, "Location", location)
                        enrichment?.timezone?.let { DetailRow(Icons.Default.Schedule, "Timezone", it) }

                        val carrierName = enrichment?.carrier ?: caller?.carrier
                        val simInfos by viewModel.simInfos.collectAsState()
                        val carrierLogoPath = remember(carrierName, simInfos) {
                            if (carrierName.isNullOrBlank()) null
                            else simInfos.find { it.carrierName.contains(carrierName, ignoreCase = true) }?.localLogoPath
                        }

                        if (!carrierName.isNullOrBlank()) DetailRow(
                            icon = Icons.Default.CellTower,
                            label = "Carrier",
                            value = carrierName,
                            trailingContent = {
                                if (carrierLogoPath != null) {
                                    AsyncImage(
                                        model = carrierLogoPath,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp).clip(CircleShape),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        )
                        
                        enrichment?.lineType?.let { DetailRow(Icons.Default.PhoneIphone, "Line Type", it) }
                        if (enrichment?.isBusiness == true) DetailRow(Icons.Default.Business, "Type", "Verified Business")
                        enrichment?.email?.let { DetailRow(Icons.Default.Email, "Email", it, enrichment?.emailSource) }
                        if (enrichment?.about?.contains("breach", ignoreCase = true) == true || enrichment?.source?.contains("Dark Web", ignoreCase = true) == true) {
                            DetailRow(Icons.Default.Warning, "Security Alert", "Found in data breaches or dark web", "Forensic")
                        }
                        if (enrichment?.lastChecked != null && enrichment?.lastChecked != 0L) {
                            val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(enrichment!!.lastChecked))
                            DetailRow(Icons.Default.Update, "Last Updated", date)
                        }
                    }
                    if (enrichment?.plateNumber != null || enrichment?.iban != null || enrichment?.vatId != null || enrichment?.macAddress != null || enrichment?.nid != null || enrichment?.source?.contains("Dark Web", ignoreCase = true) == true) {
                        DetailSection("Deep Web Intelligence") {
                            if (enrichment?.source?.contains("Dark Web", ignoreCase = true) == true) {
                                DetailRow(Icons.Default.VisibilityOff, "Exposure", "Mentioned in hidden services", "Dark Web Recon")
                            }
                            enrichment?.nid?.let { nid ->
                                DetailRow(Icons.Default.Fingerprint, "National ID", nid, "BD Database")
                                // NOTE: Google dork pivot links removed — the
                                // repo-wide DDG/Google-scrape prune covers NID
                                // dorks too (title-guess hallucinations on NIDs
                                // are dangerous). Raw database.json rows render
                                // NID + DOB only.
                            }
                            enrichment?.dob?.let { DetailRow(Icons.Default.Cake, "Date of Birth", it, "BD Database") }
                            enrichment?.plateNumber?.let { DetailRow(Icons.Default.DirectionsCar, "License Plate", it, enrichment?.plateNumberSource) }
                            enrichment?.iban?.let { DetailRow(Icons.Default.AccountBalance, "IBAN", it, enrichment?.ibanSource) }
                            enrichment?.vatId?.let { DetailRow(Icons.Default.CorporateFare, "VAT ID", it, enrichment?.vatIdSource) }
                            enrichment?.macAddress?.let { DetailRow(Icons.Default.Router, "MAC Address", it, enrichment?.macAddressSource) }
                        }
                    }
                    val socialProfiles = remember(enrichment?.socialProfilesJson) {
                        SocialUtils.filteredUsedProfiles(SocialUtils.fromJson(enrichment?.socialProfilesJson))
                    }
                    if (socialProfiles.isNotEmpty()) {
                        DetailSection("Social Presence") {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    socialProfiles.forEach { profile -> SocialIcon(profile) }
                                }
                                // WhatsApp connect is phone-only (button below is gated).
                                if (!isEmailScan) {
                                    Button(onClick = {
                                        val intent = try { SocialUtils.whatsappHelloIntent(context, phoneNumber, "Hello") } catch(_:Exception){ android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/${phoneNumber.filter{it.isDigit()}}?text=Hello")) }
                                        try { context.startActivity(intent) } catch(_:Exception){ context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/${phoneNumber.filter{it.isDigit()}}?text=Hello"))) }
                                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF25D366))) {
                                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.Chat, null, tint = androidx.compose.ui.graphics.Color.White); Spacer(Modifier.width(8.dp)); Text("WhatsApp - Hello", color = androidx.compose.ui.graphics.Color.White)
                                    }
                                }
                            }
                        }
                    } else if (!isNonPhoneScan) {
                        DetailSection("Connect") {
                            Button(onClick = {
                                val intent = try { SocialUtils.whatsappHelloIntent(context, phoneNumber, "Hello") } catch(_:Exception){ android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/${phoneNumber.filter{it.isDigit()}}?text=Hello")) }
                                try { context.startActivity(intent) } catch(_:Exception){ context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/${phoneNumber.filter{it.isDigit()}}?text=Hello"))) }
                            }, modifier = Modifier.fillMaxWidth().padding(16.dp), colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF25D366))) {
                                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.Chat, null, tint = androidx.compose.ui.graphics.Color.White); Spacer(Modifier.width(8.dp)); Text("WhatsApp - Hello", color = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    }
                    DetailSection("Deep OSINT Links") {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val links = remember(displayIdentifier, phoneNumber, isNonPhoneScan, isEmailScan) {
                                when {
                                    isEmailScan -> com.infocaller.app.util.OSINTManager.generateEmailDorkLinks(displayIdentifier)
                                    isUsernameScan -> com.infocaller.app.util.OSINTManager.generateUsernameDorkLinks(displayIdentifier)
                                    else -> com.infocaller.app.util.OSINTManager.generateExtendedDorkLinks(phoneNumber.ifBlank { displayIdentifier })
                                }
                            }
                            links.filter {
                                it.title.contains("Lens", true) || it.title.contains("Pimeyes", true) ||
                                    it.title.contains("FaceCheck", true) || it.title.contains("Perplexity", true)
                            }.take(4).forEach { link ->
                                Surface(
                                    onClick = {
                                        try {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(link.url)
                                                )
                                            )
                                        } catch (_: Exception) { }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color.White.copy(alpha = 0.06f)
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        link.icon?.let { Icon(it, null, tint = Primary, modifier = Modifier.size(20.dp)) }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(link.title, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                            Text(link.description, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                                        }
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                                    }
                                }
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
    Surface(color = Color.White.copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(start = 4.dp)) {
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
    trailingContent: @Composable (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Primary.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                SourceBadge(source)
            }
            Text(value, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
        if (trailingContent != null) {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                trailingContent()
            }
        }
    }
}

@Composable
fun SocialIcon(profile: SocialProfile) {
    val context = LocalContext.current
    Surface(onClick = { SocialUtils.openSocialProfile(context, profile) }, modifier = Modifier.size(48.dp), shape = CircleShape, color = Color.White.copy(alpha = 0.1f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = SocialUtils.getLogoUrl(profile.platform),
                contentDescription = "${profile.platform} — open account",
                modifier = Modifier.size(28.dp).clip(CircleShape),
                contentScale = ContentScale.Fit,
                placeholder = rememberVectorPainter(Icons.Default.Share),
                error = rememberVectorPainter(Icons.AutoMirrored.Filled.OpenInNew)
            )
        }
    }
}
