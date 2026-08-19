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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.ui.dialogs.AddContactBottomSheet
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
    val dialerInputRaw by viewModel.dialerInput.collectAsState()
    var textFieldValue by remember { mutableStateOf(TextFieldValue(dialerInputRaw)) }
    val focusRequester = remember { FocusRequester() }
    
    // Sync external changes (e.g. paste chip) to textFieldValue
    LaunchedEffect(dialerInputRaw) {
        if (dialerInputRaw != textFieldValue.text) {
            textFieldValue = TextFieldValue(text = dialerInputRaw, selection = TextRange(dialerInputRaw.length))
        }
    }

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
            val clean = text.filter { it.isDigit() || it == '+' }
            if (clean.length in 7..15) {
                clipboardNumber = clean
            }
        }
    }

    // Automatic Search with Debounce
    LaunchedEffect(textFieldValue.text) {
        val currentText = textFieldValue.text
        if (currentText.length >= 5) {
            kotlinx.coroutines.delay(600.milliseconds)
            viewModel.searchNumber(currentText)
        } else if (currentText.isEmpty()) {
            viewModel.clearSearch()
        }
    }

    val filteredContacts by remember {
        derivedStateOf {
            val input = textFieldValue.text
            if (input.isEmpty()) emptyList()
            else contacts.filter { 
                (it.phoneNumber?.contains(input) == true) || 
                com.infocaller.app.util.T9Search.matches(input, it.displayName) 
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
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .statusBarsPadding()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Top area / Search results
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (searchResult is SearchUiState.Success) {
                        val caller = (searchResult as SearchUiState.Success).caller
                        val isSaved = contacts.any { it.phoneNumber == textFieldValue.text }
                        
                        Card(
                            modifier = Modifier.padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = caller.displayName ?: "Unknown",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (!isSaved) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    TextButton(
                                        onClick = { showAddContactDialog = true },
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("+ Add", color = Primary, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    if (filteredContacts.isNotEmpty()) {
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(filteredContacts) { contact ->
                                AssistChip(
                                    onClick = { contact.phoneNumber?.let { viewModel.updateDialerInput(it) } },
                                    label = { Text(contact.displayName, fontSize = 12.sp) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = Primary.copy(alpha = 0.1f)),
                                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Primary.copy(alpha = 0.3f))
                                )
                            }
                        }
                    } else if (clipboardNumber != null && textFieldValue.text.isEmpty()) {
                        AssistChip(
                            onClick = { viewModel.updateDialerInput(clipboardNumber!!) },
                            label = { Text("Paste: $clipboardNumber", color = Primary, fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.ContentPaste, null, tint = Primary, modifier = Modifier.size(14.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = Primary.copy(alpha = 0.05f)),
                            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Primary.copy(alpha = 0.5f))
                        )
                    }
                }
            }

            // 2. Editable Number Display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clickable { focusRequester.requestFocus() },
                contentAlignment = Alignment.Center
            ) {
                SelectionContainer {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { 
                            textFieldValue = it
                            viewModel.updateDialerInput(it.text)
                        },
                        textStyle = TextStyle(
                            fontSize = if (textFieldValue.text.length > 12) 32.sp else 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        cursorBrush = SolidColor(Primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (textFieldValue.text.isEmpty()) {
                                Text(
                                    " ",
                                    style = MaterialTheme.typography.displayLarge,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Numeric Keypad
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("*", "0", "#")
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                keys.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { key ->
                            DialerButton(text = key) {
                                playTone(key)
                                val currentText = textFieldValue.text
                                val selection = textFieldValue.selection
                                val newText = currentText.replaceRange(selection.start, selection.end, key)
                                val newSelection = TextRange(selection.start + key.length)
                                textFieldValue = TextFieldValue(newText, newSelection)
                                viewModel.updateDialerInput(newText)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 5. Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Add Contact
                ActionIconButton(
                    icon = Icons.Default.PersonAdd,
                    contentDescription = "Add Contact",
                    size = 56.dp,
                    onClick = { if (textFieldValue.text.isNotEmpty()) showAddContactDialog = true }
                )

                // Call
                Surface(
                    onClick = { if (textFieldValue.text.isNotEmpty()) onCall(textFieldValue.text) },
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(12.dp, CircleShape),
                    shape = CircleShape,
                    color = Color.Transparent
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.linearGradient(colors = listOf(GradientStart, GradientEnd))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Call, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                    }
                }

                // Delete
                ActionIconButton(
                    icon = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Backspace",
                    size = 56.dp,
                    onClick = { 
                        if (textFieldValue.text.isNotEmpty()) {
                            val currentText = textFieldValue.text
                            val selection = textFieldValue.selection
                            if (selection.start == selection.end && selection.start > 0) {
                                val newText = currentText.removeRange(selection.start - 1, selection.start)
                                val newSelection = TextRange(selection.start - 1)
                                textFieldValue = TextFieldValue(newText, newSelection)
                            } else if (selection.start != selection.end) {
                                val newText = currentText.removeRange(selection.start, selection.end)
                                val newSelection = TextRange(selection.start)
                                textFieldValue = TextFieldValue(newText, newSelection)
                            }
                            viewModel.updateDialerInput(textFieldValue.text)
                        }
                    },
                    onLongClick = {
                        if (textFieldValue.text.isNotEmpty()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            textFieldValue = TextFieldValue("")
                            viewModel.updateDialerInput("")
                        }
                    }
                )
            }
        }
    }
    
    if (showAddContactDialog) {
        val initialName = if (searchResult is SearchUiState.Success) {
            (searchResult as SearchUiState.Success).caller.displayName ?: ""
        } else ""
        
        AddContactBottomSheet(
            viewModel = viewModel,
            phoneNumber = textFieldValue.text,
            initialName = initialName,
            onDismiss = { showAddContactDialog = false }
        ) { showAddContactDialog = false }
    }
}

@Composable
fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun DialerButton(text: String, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(value = false) }
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1f, label = "ButtonScale")

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(72.dp)
            .scale(scale)
            .glassy(radius = 36.dp, blur = 8.dp),
        shape = CircleShape,
        color = Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
