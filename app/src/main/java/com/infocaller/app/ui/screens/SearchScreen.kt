package com.infocaller.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.infocaller.app.ui.components.AccentBar
import com.infocaller.app.ui.components.AppCard
import com.infocaller.app.ui.components.IconBadge
import com.infocaller.app.ui.components.InfoCallerLoading
import com.infocaller.app.ui.components.Pill
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.viewmodel.SearchUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: CallerViewModel,
    onNavigateToDetails: (String) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.searchResult.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var numberInput by remember { mutableStateOf("") }
    var nidInput by remember { mutableStateOf("") }
    var dobInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Identify", color = contentPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = contentPrimary)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Number / Email") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("NID / DOB") })
        }
        if (tab == 1) {
            val backendConfigured = remember {
                try {
                    com.infocaller.app.BuildConfig.BACKEND_BASE_URL.trim().isNotBlank() &&
                        com.infocaller.app.BuildConfig.INFOCALLER_API_KEY.trim().isNotBlank()
                } catch (_: Exception) { false }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!backendConfigured) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(Radii.md)
                    ) {
                        Row(
                            modifier = Modifier.padding(Space.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(Space.sm))
                            Text(
                                "NID lookup needs a backend, which is not configured in this build. Nothing is stored on the device — see backend/DATA-SETUP.md.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.xs))
                }
                OutlinedTextField(
                    value = nidInput,
                    onValueChange = { nidInput = it.filter { c -> c.isDigit() }.take(17) },
                    label = { Text("NID number (10-17 digits)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = dobInput,
                    onValueChange = { dobInput = it.take(12) },
                    label = { Text("DOB optional (YYYY-MM-DD)") },
                    placeholder = { Text("1969-04-19") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Button(
                    onClick = {
                        val q = if (dobInput.isNotBlank()) nidInput.trim() + "|" + dobInput.trim() else nidInput.trim()
                        if (q.isNotBlank()) viewModel.searchNidManual(q)
                    },
                    enabled = nidInput.filter { it.isDigit() }.length in 10..17,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Search NID database") }
                Text(
                    "NID database is served securely from our backend — it is not stored in the app. NID alone, or NID|DOB to verify.",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentSecondary(0.6f),
                )
            }
        } else {
            OutlinedTextField(
                value = numberInput,
                onValueChange = { numberInput = it.take(120) },
                label = { Text("Number, email or @username", style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.sm),
                singleLine = true,
                shape = RoundedCornerShape(Radii.md),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = strokeSoft,
                    focusedContainerColor = surfaceElevated,
                    unfocusedContainerColor = surfaceElevated
                )
            )
            val ready = numberInput.isNotBlank()
            val buttonInteraction = rememberPressInteraction()
            val buttonPressed by buttonInteraction.collectIsPressedAsState()
            val buttonScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (buttonPressed) 0.97f else 1f,
                animationSpec = Motion.pressSpring,
                label = "searchBtn"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = Space.screen)
                    .fillMaxWidth()
                    .height(54.dp)
                    .scale(buttonScale)
                    .alpha(if (ready) 1f else 0.45f)
                    .brandGradient(radius = Radii.md)
                    .pressable(buttonInteraction, enabled = ready, pressedScale = 1f)
                    .clickable(
                        interactionSource = buttonInteraction,
                        indication = null,
                        enabled = ready
                    ) { viewModel.searchNumber(numberInput.trim()) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Search",
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when (uiState) {
                is SearchUiState.Idle -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(Icons.Default.Search, size = 56.dp, tint = contentSecondary(0.4f), background = Color.Transparent)
                    Spacer(Modifier.height(Space.md))
                    Text(
                        "Number, email or username লিখে search করুন",
                        color = contentSecondary(0.5f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is SearchUiState.Loading -> InfoCallerLoading(isFullScreen = true, text = "Searching...")
                is SearchUiState.Success -> {
                    val caller = (uiState as SearchUiState.Success).caller
                    var appeared by remember { mutableStateOf(false) }
                    LaunchedEffect(caller.phoneNumber) { appeared = true }
                    val resultAlpha by animateFloatAsState(
                        targetValue = if (appeared) 1f else 0f,
                        animationSpec = tween(Motion.SLOW, easing = Motion.decelerate),
                        label = "resultFade"
                    )
                    val resultScale by animateFloatAsState(
                        targetValue = if (appeared) 1f else 0.94f,
                        animationSpec = tween(Motion.SLOW, easing = Motion.emphasized),
                        label = "resultScale"
                    )
                    AppCard(
                        radius = Radii.xl,
                        onClick = {
                            val number = caller.phoneNumber
                            if (number.isNotBlank()) onNavigateToDetails(number)
                        },
                        modifier = Modifier
                            .padding(Space.xxl)
                            .fillMaxWidth()
                            .alpha(resultAlpha)
                            .scale(resultScale)
                    ) {
                        Column(
                            modifier = Modifier.padding(Space.xxl),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AccentBar(Modifier.fillMaxWidth(0.4f))
                            Spacer(Modifier.height(Space.lg))
                            Text(
                                text = caller.displayName ?: "Unknown Caller",
                                style = MaterialTheme.typography.headlineSmall,
                                color = contentPrimary
                            )
                            Spacer(Modifier.height(Space.sm))
                            Text(
                                text = caller.phoneNumber,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Primary
                            )
                            Spacer(Modifier.height(Space.lg))
                            Pill("Verified Result", color = Success)
                        }
                    }
                }
                is SearchUiState.NotFound -> Text("No data found for this number", color = contentPrimary)
                is SearchUiState.Error -> Text("Error: ${(uiState as SearchUiState.Error).message}", color = Error)
            }
        }
        }
        }
}
