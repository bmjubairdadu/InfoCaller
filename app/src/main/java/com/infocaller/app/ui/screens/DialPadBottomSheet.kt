package com.infocaller.app.ui.screens

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
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
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.infocaller.app.ui.dialogs.AddContactBottomSheet
import com.infocaller.app.ui.theme.*
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.ui.viewmodel.SearchUiState

/**
 * Full dial pad as a bottom sheet hosted inside Contacts (bottom-right FAB).
 * Same lookup + add-contact behavior as the former standalone Dialer tab:
 * typing debounces into a scan, "+ Add" opens the auto-populated sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DialPadBottomSheet(
    viewModel: CallerViewModel,
    onCall: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        DialPadContent(
            viewModel = viewModel,
            onCall = onCall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .imePadding()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialPadContent(
    viewModel: CallerViewModel,
    onCall: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val dialerInputRaw by viewModel.dialerInput.collectAsState()
    var textFieldValue by remember { mutableStateOf(TextFieldValue(dialerInputRaw)) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(dialerInputRaw) {
        if (dialerInputRaw != textFieldValue.text) {
            textFieldValue = TextFieldValue(text = dialerInputRaw, selection = TextRange(dialerInputRaw.length))
        }
    }

    val contacts by viewModel.contacts.collectAsState()
    val filteredContacts by viewModel.filteredContacts.collectAsState()
    val searchResult by viewModel.searchResult.collectAsState()
    val simInfos by viewModel.simInfos.collectAsState()
    val commonUssdCodes = remember { com.infocaller.app.util.OSINTManager.getCommonUssdCodes() }
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var showAddContactDialog by rememberSaveable { mutableStateOf(false) }
    var clipboardNumber by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                val clean = text.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                if (clean.length in 3..15) clipboardNumber = clean
            }
        } catch (_: Exception) { }
    }

    // Cancellable debounce: each keystroke cancels the previous pending lookup
    // so fast typing fires exactly one search instead of one per keystroke.
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(textFieldValue.text) {
        val currentText = textFieldValue.text
        debounceJob?.cancel()
        if (currentText.length >= 7 && !currentText.contains("*") && !currentText.contains("#")) {
            val job = scope.launch {
                kotlinx.coroutines.delay(350)
                viewModel.searchNumber(currentText)
            }
            debounceJob = job
        } else if (currentText.isEmpty()) {
            viewModel.clearSearch()
        }
    }

    val toneGenerator = remember { try { ToneGenerator(AudioManager.STREAM_DTMF, 80) } catch (_: Exception) { null } }
    DisposableEffect(Unit) { onDispose { toneGenerator?.release() } }

    fun playTone(key: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val tone = when (key) {
            "1" -> ToneGenerator.TONE_DTMF_1; "2" -> ToneGenerator.TONE_DTMF_2; "3" -> ToneGenerator.TONE_DTMF_3
            "4" -> ToneGenerator.TONE_DTMF_4; "5" -> ToneGenerator.TONE_DTMF_5; "6" -> ToneGenerator.TONE_DTMF_6
            "7" -> ToneGenerator.TONE_DTMF_7; "8" -> ToneGenerator.TONE_DTMF_8; "9" -> ToneGenerator.TONE_DTMF_9
            "0" -> ToneGenerator.TONE_DTMF_0; "*" -> ToneGenerator.TONE_DTMF_S; "#" -> ToneGenerator.TONE_DTMF_P
            else -> -1
        }
        if (tone != -1) toneGenerator?.startTone(tone, 150)
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (searchResult is SearchUiState.Success) {
                    val caller = (searchResult as SearchUiState.Success).caller
                    val isSaved = contacts.any { it.phoneNumber == textFieldValue.text }
                    Card(modifier = Modifier.padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = caller.displayName ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                            if (!isSaved) {
                                Spacer(modifier = Modifier.width(12.dp))
                                TextButton(onClick = { showAddContactDialog = true }) { Text("+ Add", color = Primary, fontSize = 12.sp) }
                            }
                        }
                    }
                }
                if (filteredContacts.isNotEmpty()) {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(filteredContacts) { contact ->
                            AssistChip(onClick = { contact.phoneNumber?.let { viewModel.updateDialerInput(it) } }, label = { Text(contact.displayName, fontSize = 12.sp) }, colors = AssistChipDefaults.assistChipColors(containerColor = Primary.copy(alpha = 0.1f)), border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Primary.copy(alpha = 0.3f)))
                        }
                    }
                } else if (textFieldValue.text.isEmpty() || textFieldValue.text.startsWith("*")) {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(commonUssdCodes) { ussd ->
                            AssistChip(onClick = { viewModel.updateDialerInput(ussd.url) }, label = { Text(ussd.title, fontSize = 11.sp) }, leadingIcon = { ussd.icon?.let { Icon(it, null, modifier = Modifier.size(14.dp)) } }, colors = AssistChipDefaults.assistChipColors(containerColor = Secondary.copy(alpha = 0.1f)))
                        }
                    }
                } else if (clipboardNumber != null && textFieldValue.text.isEmpty()) {
                    AssistChip(onClick = { viewModel.updateDialerInput(clipboardNumber!!) }, label = { Text("Paste: $clipboardNumber", color = Primary, fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Default.ContentPaste, null, tint = Primary, modifier = Modifier.size(14.dp)) }, colors = AssistChipDefaults.assistChipColors(containerColor = Primary.copy(alpha = 0.05f)), border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Primary.copy(alpha = 0.5f)))
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(88.dp).clickable { focusRequester.requestFocus() }, contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                val defaultSimLogo = simInfos.firstOrNull()?.localLogoPath
                if (defaultSimLogo != null && textFieldValue.text.isNotEmpty()) {
                    AsyncImage(model = defaultSimLogo, contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape).padding(end = 8.dp), contentScale = ContentScale.Fit)
                }
                SelectionContainer {
                    BasicTextField(value = textFieldValue, onValueChange = { textFieldValue = it; viewModel.updateDialerInput(it.text) }, textStyle = TextStyle(fontSize = if (textFieldValue.text.length > 12) 30.sp else 40.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground, textAlign = androidx.compose.ui.text.style.TextAlign.Center), modifier = Modifier.wrapContentWidth().focusRequester(focusRequester), cursorBrush = SolidColor(Primary), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, decorationBox = { innerTextField ->
                        if (textFieldValue.text.isEmpty()) Text("Enter number", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        innerTextField()
                    })
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            keys.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
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
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            DialPadActionIconButton(icon = Icons.Default.PersonAdd, contentDescription = "Add Contact", size = 56.dp, onClick = { if (textFieldValue.text.isNotEmpty()) showAddContactDialog = true })
            Surface(onClick = { if (textFieldValue.text.isNotEmpty()) onCall(textFieldValue.text) }, modifier = Modifier.size(72.dp).shadow(12.dp, CircleShape), shape = CircleShape, color = Color.Transparent) {
                Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(colors = listOf(GradientStart, GradientEnd))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Call, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                }
            }
            DialPadActionIconButton(icon = Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace", size = 56.dp, onClick = {
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
            }, onLongClick = {
                if (textFieldValue.text.isNotEmpty()) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    textFieldValue = TextFieldValue("")
                    viewModel.updateDialerInput("")
                }
            })
        }
    }
    if (showAddContactDialog) {
        val initialName = if (searchResult is SearchUiState.Success) (searchResult as SearchUiState.Success).caller.displayName ?: "" else ""
        AddContactBottomSheet(viewModel = viewModel, phoneNumber = textFieldValue.text, initialName = initialName, onDismiss = { showAddContactDialog = false }) { showAddContactDialog = false }
    }
}

@Composable
private fun DialPadActionIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Surface(modifier = Modifier.size(size).clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLongClick), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription, modifier = Modifier.size(24.dp)) }
    }
}

@Composable
private fun DialerButton(text: String, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(value = false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (isPressed) 0.9f else 1f, label = "ButtonScale")
    Surface(onClick = onClick, modifier = Modifier.size(72.dp).scale(scale).glassy(radius = 36.dp, blur = 8.dp), shape = CircleShape, color = Color.Transparent) {
        Box(contentAlignment = Alignment.Center) { Text(text = text, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
    }
}
