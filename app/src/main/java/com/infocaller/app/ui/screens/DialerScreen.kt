package com.infocaller.app.ui.screens

import android.media.AudioManager
import android.media.ToneGenerator
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.ui.dialogs.AddContactDialog
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.viewmodel.SearchUiState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    viewModel: CallerViewModel,
    onCall: (String) -> Unit,
    innerPadding: PaddingValues = PaddingValues(0.dp)
) {
    val dialerInput by viewModel.dialerInput.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val searchResult by viewModel.searchResult.collectAsState()
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var showAddContactDialog by remember { mutableStateOf(value = false) }

    // Clipboard suggestion
    var clipboardNumber by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            val clean = text.filter { it.isDigit() }
            if (clean.length in 7..15) {
                clipboardNumber = clean
            }
        }
    }

    // Automatic Search with Debounce
    LaunchedEffect(dialerInput) {
        if (dialerInput.length >= 5) {
            kotlinx.coroutines.delay(600.milliseconds)
            viewModel.searchNumber(dialerInput)
        } else if (dialerInput.isEmpty()) {
            viewModel.clearSearch()
        }
    }

    val filteredContacts by remember {
        derivedStateOf {
            if (dialerInput.isEmpty()) emptyList()
            else contacts.filter { 
                (it.phoneNumber?.contains(dialerInput) == true) || 
                com.infocaller.app.util.T9Search.matches(dialerInput, it.displayName) 
            }
        }
    }
    
    val toneGenerator = remember {
        try {
            ToneGenerator(AudioManager.STREAM_DTMF, 80)
        } catch (_: Exception) {
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            toneGenerator?.release()
        }
    }

    fun playTone(key: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val tone = when (key) {
            "1" -> ToneGenerator.TONE_DTMF_1
            "2" -> ToneGenerator.TONE_DTMF_2
            "3" -> ToneGenerator.TONE_DTMF_3
            "4" -> ToneGenerator.TONE_DTMF_4
            "5" -> ToneGenerator.TONE_DTMF_5
            "6" -> ToneGenerator.TONE_DTMF_6
            "7" -> ToneGenerator.TONE_DTMF_7
            "8" -> ToneGenerator.TONE_DTMF_8
            "9" -> ToneGenerator.TONE_DTMF_9
            "0" -> ToneGenerator.TONE_DTMF_0
            "*" -> ToneGenerator.TONE_DTMF_S
            "#" -> ToneGenerator.TONE_DTMF_P
            else -> -1
        }
        if (tone != -1) {
            toneGenerator?.startTone(tone, 150)
        }
    }
    
    var isRecording by remember { mutableStateOf(value = false) }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Background, Surface)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(bottom = innerPadding.calculateBottomPadding())
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Identified Result Display
            if (searchResult is SearchUiState.Success) {
                val caller = (searchResult as SearchUiState.Success).caller
                val isSaved = contacts.any { it.phoneNumber == dialerInput }
                
                Row(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .glassy(radius = 16.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = caller.displayName ?: "Unknown",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isSaved) {
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            onClick = { showAddContactDialog = true },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("+ Add Contact", color = Primary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Smart Dialer Search Results
            if (filteredContacts.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(filteredContacts) { contact ->
                        AssistChip(
                            onClick = { contact.phoneNumber?.let { viewModel.updateDialerInput(it) } },
                            label = { Text(contact.displayName, color = Color.White) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = Primary.copy(alpha = 0.2f)),
                            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Primary.copy(alpha = 0.5f))
                        )
                    }
                }
            } else if (clipboardNumber != null && dialerInput.isEmpty()) {
                AssistChip(
                    onClick = { viewModel.updateDialerInput(clipboardNumber!!) },
                    label = { Text("Paste: $clipboardNumber", color = Primary) },
                    leadingIcon = { Icon(Icons.Default.ContentPaste, null, tint = Primary, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = Primary.copy(alpha = 0.1f)),
                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Primary)
                )
            }

            if (isRecording) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .glassy(radius = 12.dp, blur = 4.dp)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = "Recording",
                        tint = Color.Red,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Recording...", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Number Display
            AnimatedContent(
                targetState = dialerInput,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.8f)).togetherWith(fadeOut() + scaleOut(targetScale = 1.2f))
                },
                label = "DialerInput"
            ) { targetInput ->
                val formattedInput = remember(targetInput) {
                    com.infocaller.app.util.PhoneNumberUtils.formatAsYouType(targetInput)
                }
                Text(
                    text = formattedInput.ifEmpty { " " },
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }

            // Keypad
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("*", "0", "#")
            )

            keys.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { key ->
                        DialerButton(text = key) {
                            playTone(key)
                            viewModel.updateDialerInput(dialerInput + key)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Add Contact Button
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .requiredSize(72.dp)
                            .glassy(radius = 36.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (dialerInput.isNotEmpty()) {
                                    showAddContactDialog = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PersonAdd, 
                            contentDescription = "Add Contact", 
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Call Button
                Box(
                    modifier = Modifier.weight(1.5f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .requiredSize(96.dp)
                            .shadow(16.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.linearGradient(colors = listOf(GradientStart, GradientEnd))
                            )
                            .clickable {
                                if (dialerInput.isNotEmpty()) onCall(dialerInput)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call, 
                            contentDescription = "Call", 
                            modifier = Modifier.size(48.dp), 
                            tint = Color.White
                        )
                    }
                }

                // Backspace Button
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .requiredSize(72.dp)
                            .glassy(radius = 36.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = {
                                    if (dialerInput.isNotEmpty()) {
                                        viewModel.updateDialerInput(dialerInput.dropLast(1))
                                    }
                                },
                                onLongClick = {
                                    if (dialerInput.isNotEmpty()) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.updateDialerInput("")
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace, 
                            contentDescription = "Backspace", 
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
            
            // Extra padding to ensure the action row is above the bottom nav with a gap
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    
    if (showAddContactDialog) {
        val initialName = if (searchResult is SearchUiState.Success) {
            (searchResult as SearchUiState.Success).caller.displayName ?: ""
        } else ""
        
        AddContactDialog(
            phoneNumber = dialerInput,
            initialName = initialName,
            onDismiss = { showAddContactDialog = false }
        ) { showAddContactDialog = false }
    }
}

@Composable
fun DialerButton(text: String, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(value = false) }
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1f, label = "ButtonScale")

    val subText = when (text) {
        "2" -> "ABC"
        "3" -> "DEF"
        "4" -> "GHI"
        "5" -> "JKL"
        "6" -> "MNO"
        "7" -> "PQRS"
        "8" -> "TUV"
        "9" -> "WXYZ"
        else -> null
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .requiredSize(82.dp)
            .scale(scale)
            .shadow(if (isPressed) 2.dp else 8.dp, CircleShape)
            .glassy(radius = 41.dp, blur = 8.dp),
        shape = CircleShape,
        color = Color.Transparent
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (subText != null) {
                Text(
                    text = subText,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
