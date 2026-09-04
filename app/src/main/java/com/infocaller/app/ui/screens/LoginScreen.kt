package com.infocaller.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.ui.viewmodel.AuthUiState
import com.infocaller.app.ui.viewmodel.AuthViewModel
import com.infocaller.app.ui.components.InfoCallerLoading
import com.infocaller.app.ui.components.OtpInputField
import com.infocaller.app.ui.theme.*
import com.infocaller.app.util.OtpManager
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.permissions.PermissionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as InfoCallerApplication
    val authManager = remember(app) { app.truecallerAuthManager }
    val truecallerProvider = remember(app) {
        app.providerManager.providers.value.filterIsInstance<com.infocaller.app.data.remote.TruecallerProviderImpl>().firstOrNull()
            ?: com.infocaller.app.data.remote.TruecallerProviderImpl(context.applicationContext)
    }
    
    val scope = rememberCoroutineScope()
    val uiState by viewModel.authState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    val tcPhone by viewModel.tcPhone.collectAsState()
    val tcAuthResult by viewModel.tcAuthResult.collectAsState()
    var tcOtp by remember { mutableStateOf("") }
    var tcLoading by remember { mutableStateOf(false) }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    @Suppress("UNUSED_PARAMETER")
    var autoVerifying by remember { mutableStateOf(false) }

    LaunchedEffect(tcAuthResult) {
        if (tcAuthResult == null) return@LaunchedEffect
        val method = tcAuthResult!!.method.lowercase()
        if (method == "call" || method == "flashcall" || method == "missedcall") {
            val perms = PermissionManager.CORE_PERMISSIONS + PermissionManager.CALL_LOG_PERMISSIONS
            if (!PermissionManager.hasPermissions(context, perms)) smsPermissionLauncher.launch(perms)
        }
        if (method == "already_logged_in") {
            viewModel.loginWithTruecaller(null)
            snackbarHostState.showSnackbar("Already verified ✓")
            return@LaunchedEffect
        }
        val last: String? = OtpManager.lastOtpFlow.value
        if (last != null && last.length == 6 && tcOtp.isEmpty()) {
            tcOtp = last
            val preResult = authManager.verifyOtp(tcPhone, tcAuthResult!!.requestId, last)
            if (preResult.success) {
                viewModel.loginWithTruecaller(null)
                snackbarHostState.showSnackbar("Auto-verified from SMS ✓")
                OtpManager.clearOtp()
                return@LaunchedEffect
            }
        }
        OtpManager.otpFlow.collectLatest { code: String? ->
            if (code == null) return@collectLatest
            val codeStr: String = code
            val extractedOtp: String = when (method) {
                "call", "flashcall", "missedcall" -> if (codeStr.length > 6) codeStr.takeLast(6) else codeStr
                else -> codeStr
            }
            if (extractedOtp.length == 6) {
                tcOtp = extractedOtp
                val verifyResult = authManager.verifyOtp(tcPhone, tcAuthResult!!.requestId, extractedOtp)
                if (verifyResult.success) {
                    viewModel.loginWithTruecaller(null)
                    snackbarHostState.showSnackbar("Auto-verified ✓")
                } else {
                    snackbarHostState.showSnackbar("Auto-verify failed: ${verifyResult.message ?: "Invalid code"} - tap VERIFY to retry")
                }
                OtpManager.clearOtp()
            }
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is AuthUiState.Authenticated) {
            onLoginSuccess()
        } else if (uiState is AuthUiState.Error) {
            snackbarHostState.showSnackbar((uiState as AuthUiState.Error).message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (tcAuthResult == null && (uiState is AuthUiState.Loading || tcLoading)) {
                InfoCallerLoading(
                    isFullScreen = true,
                    text = if (autoVerifying) "Automatic Verification..." else "Authenticating..."
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Security, 
                    contentDescription = null, 
                    modifier = Modifier.size(72.dp),
                    tint = Primary
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "Identity Verification",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Intelligence at your fingertips. Verify your number to unlock full potential.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(40.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassy(radius = 28.dp, borderWidth = 1.5.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (tcAuthResult == null) {
                            Text(
                                "Enter Phone Number",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(Modifier.height(16.dp))
                            
                            OutlinedTextField(
                                value = tcPhone,
                                onValueChange = { viewModel.setTcPhone(it) },
                                label = { Text("Phone Number", color = Color.White.copy(alpha = 0.5f)) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                leadingIcon = { Icon(Icons.Default.Phone, null, tint = Primary) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                                )
                            )

                            Spacer(modifier = Modifier.height(32.dp))
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .alpha(if (tcPhone.length >= 7 && !tcLoading) 1f else 0.5f)
                                    .brandGradient(radius = 16.dp)
                                    .clickable(enabled = tcPhone.length >= 7 && !tcLoading) {
                                        val runAuth = {
                                            tcLoading = true
                                            scope.launch {
                                                val normalized = PhoneNumberUtils.normalize(tcPhone)
                                                val r = authManager.requestOtp(normalized)
                                                val result = if (r!=null) com.infocaller.app.data.remote.TruecallerProviderImpl.AuthRequestResult(r.requestId, r.method, r.ttl, r.status, r.message) else null

                                                if (result == null) {
                                                    snackbarHostState.showSnackbar("Connection error — check internet")
                                                } else if (result.statusCode == -1) {
                                                    snackbarHostState.showSnackbar(result.errorMessage ?: "Connection error. Check your internet.")
                                                } else if (result.requestId.isBlank() && result.statusCode != 3) {
                                                    val errorMsg = result.errorMessage ?: ""
                                                    // Benojir: status 5/6 = rate limit -> "Too many request... Try again after 1 hour"
                                                    val isLimit = result.statusCode == 5 || result.statusCode == 6 || result.statusCode == 429
                                                    if (isLimit) {
                                                        viewModel.refreshTcSession(context)
                                                        snackbarHostState.showSnackbar(errorMsg.takeIf { it.isNotBlank() } ?: "Too many requests. Try again after 1 hour.")
                                                    } else {
                                                        val msg = when(result.statusCode) {
                                                            40104 -> "Configuration Error: Invalid Client Secret."
                                                            40101 -> "Unauthorized request. Please check your credentials."
                                                            12 -> "Region error. Try again shortly."
                                                            else -> errorMsg.takeIf { it.isNotBlank() } ?: "Verification service unavailable (Error ${result.statusCode})."
                                                        }
                                                        snackbarHostState.showSnackbar(msg)
                                                    }
                                                } else {
                                                    // Benojir onSuccess: save requestId even when alreadyLoggedIn, then show OTP box
                                                    viewModel.setTcAuthResult(result)
                                                    if (result.requestId.isNotBlank()) {
                                                        snackbarHostState.showSnackbar(if (result.method == "already_logged_in") "Already verified ✓" else "OTP sent ✓")
                                                    }
                                                }
                                                tcLoading = false
                                            }
                                        }

                                        if (!PermissionManager.hasPermissions(context, PermissionManager.SMS_PERMISSION)) {
                                            smsPermissionLauncher.launch(PermissionManager.SMS_PERMISSION)
                                            runAuth()
                                        } else {
                                            runAuth()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (tcLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Text(
                                        "SEND VERIFICATION CODE",
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        } else {
                            Text(
                                if (tcAuthResult!!.method == "call") 
                                    "Verification via Call" 
                                else if (tcAuthResult!!.method == "whatsapp")
                                    "Verification via WhatsApp"
                                else 
                                    "Enter Verification Code",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Text(
                                when (tcAuthResult!!.method) {
                                    "call" -> "We are calling your number. Please wait..."
                                    "whatsapp" -> "Open WhatsApp to see the 6-digit code"
                                    else -> "We've sent a 6-digit code to your phone"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp).align(Alignment.Start)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            if (tcAuthResult!!.method == "sms" || tcAuthResult!!.method == "whatsapp") {
                                OtpInputField(
                                    otpText = tcOtp,
                                    onOtpTextChange = { tcOtp = it },
                                    modifier = Modifier.wrapContentWidth()
                                )

                                TextButton(
                                    onClick = {
                                        val text = clipboardManager.getText()?.text
                                        if (text != null && text.length == 6 && text.all { it.isDigit() }) {
                                            tcOtp = text
                                        }
                                    },
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Icon(Icons.Default.ContentPaste, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Paste Code", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                }
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "Detected flash call? Enter last 6 digits of caller number:",
                                        color = Color.White.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                    )
                                    
                                    OtpInputField(
                                        otpText = tcOtp,
                                        onOtpTextChange = { tcOtp = it },
                                        modifier = Modifier.wrapContentWidth()
                                    )
                                    
                                    Spacer(modifier = Modifier.height(16.dp))
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Primary.copy(alpha = 0.3f))
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .alpha(if (tcOtp.length == 6 && !tcLoading) 1f else 0.5f)
                                    .brandGradient(radius = 16.dp)
                                    .clickable(enabled = tcOtp.length == 6 && !tcLoading) {
                                        tcLoading = true
                                        scope.launch {
                                            val verifyResult = authManager.verifyOtp(tcPhone, tcAuthResult!!.requestId, tcOtp)
                                            if (verifyResult.success) {
                                                viewModel.loginWithTruecaller(null)
                                                snackbarHostState.showSnackbar("Cloud secret created - Truecaller unlocked ✓")
                                            } else {
                                                snackbarHostState.showSnackbar(verifyResult.message ?: "Invalid OTP code. Please try again.")
                                            }
                                            tcLoading = false
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (tcLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Text(
                                        "VERIFY & CONTINUE",
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                            
                            TextButton(onClick = { viewModel.setTcAuthResult(null); tcOtp = "" }, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Edit Phone Number", color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Icon(Icons.Default.VerifiedUser, null, tint = TruecallerBlue, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Secured by Truecaller Engine",
                        style = MaterialTheme.typography.labelSmall,
                        color = TruecallerBlue.copy(alpha = 0.8f),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "By continuing, you agree to our Terms of Service & Privacy Policy",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
