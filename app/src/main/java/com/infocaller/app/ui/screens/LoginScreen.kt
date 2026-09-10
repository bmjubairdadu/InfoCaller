package com.infocaller.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.infocaller.app.ui.theme.Primary
import com.infocaller.app.ui.theme.TruecallerBlue
import com.infocaller.app.ui.theme.brandGradient
import com.infocaller.app.ui.theme.contentPrimary
import com.infocaller.app.ui.theme.contentSecondary
import com.infocaller.app.ui.theme.faintTint
import com.infocaller.app.ui.theme.glassy
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
    var tcOtp by rememberSaveable { mutableStateOf("") }
    var tcLoading by remember { mutableStateOf(false) }

    var autoFillEnabled by remember {
        mutableStateOf(PermissionManager.hasPermissions(context, PermissionManager.VERIFY_PERMISSIONS))
    }
    var autoVerifying by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var verifyError by remember { mutableStateOf<String?>(null) }
    // Live Truecaller API failure popup (verify path). Shown for manual +
    // auto-verify failures with the exact server message.
    var verifyErrorPopup by remember { mutableStateOf<String?>(null) }
    var showVerifyPermissionsPopup by rememberSaveable { mutableStateOf(false) }
    // Tracks which codes already consumed an auto-verify attempt so we
    // never loop on the same code, but a NEW code for the same requestId
    // (e.g. SMS arrives after missed-call tail) still gets its own attempt.
    var autoConsumedCodes by remember { mutableStateOf(setOf<String>()) }

    val verifyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        autoFillEnabled = PermissionManager.hasPermissions(context, PermissionManager.VERIFY_PERMISSIONS)
        verifyError = null
        verifyErrorPopup = null
        tcLoading = true
        authError = null
        autoConsumedCodes = emptySet()
        OtpManager.clearOtp()
        OtpManager.clearMissedCallTail()
        scope.launch {
            val normalized = PhoneNumberUtils.normalize(tcPhone)
            val r = authManager.requestOtp(normalized)
            val result = if (r != null) com.infocaller.app.data.remote.TruecallerProviderImpl.AuthRequestResult(r.requestId, r.method, r.ttl, r.status, r.message) else null
            if (result == null) {
                authError = "Connection error — check internet"
                verifyErrorPopup = authError
            } else if (result.statusCode == -1) {
                authError = result.errorMessage ?: "Connection error. Check your internet."
                verifyErrorPopup = authError
            } else if (result.requestId.isBlank() && result.statusCode != 3) {
                authError = result.errorMessage?.takeIf { it.isNotBlank() }
                    ?: "Verification service unavailable (Error ${result.statusCode})."
                verifyErrorPopup = authError
            } else {
                viewModel.setTcAuthResult(result)
            }
            tcLoading = false
        }
    }
    val smsPermissionLauncher = verifyPermissionLauncher

    LaunchedEffect(tcAuthResult) {
        if (tcAuthResult == null) {
            autoConsumedCodes = emptySet()
            return@LaunchedEffect
        }
        val method = tcAuthResult!!.method.lowercase()
        if (method == "already_logged_in") {
            viewModel.loginWithTruecaller(null)
            return@LaunchedEffect
        }
        val servedRequestId = tcAuthResult!!.requestId

        suspend fun tryAutoVerify(codeRaw: String, rid: String): Boolean {
            val digits = codeRaw.filter { it.isDigit() }
            val code = when {
                digits.length in 4..10 -> digits
                digits.length > 10 -> digits.takeLast(6)
                else -> return false
            }
            if (code.length !in 4..10) return false
            // Same code must not retry for the same request (it already failed).
            // A different code for the same request gets its own attempt.
            val attemptKey = "$rid:$code"
            if (autoConsumedCodes.contains(attemptKey)) return false
            autoConsumedCodes = autoConsumedCodes + attemptKey
            autoVerifying = true
            tcOtp = code
            verifyError = null
            val phoneNow = try { viewModel.tcPhone.value } catch (_: Exception) { tcPhone }
            val res = try {
                authManager.verifyOtp(phoneNow, rid, code)
            } catch (e: Exception) {
                com.infocaller.app.data.remote.TruecallerAuthManager.VerifyResult(
                    false, null, -1, e.message ?: "Network error"
                )
            }
            autoVerifying = false
            if (res.success) {
                try {
                    context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                        .edit().remove("last_tc_request_id").remove("last_tc_method").apply()
                } catch (_: Exception) { }
                OtpManager.clearOtp()
                OtpManager.clearMissedCallTail()
                viewModel.loginWithTruecaller(null)
                return true
            } else {
                val live = res.message ?: "Invalid code"
                verifyError = live
                verifyErrorPopup = live
                tcOtp = ""
                OtpManager.clearOtp()
                OtpManager.clearMissedCallTail()
                return false
            }
        }

        // Code already arrived before this screen recomposed (SMS or missed-call).
        val immediateSms: String? = OtpManager.lastOtpFlow.value
        if (!immediateSms.isNullOrBlank() && tcOtp.isEmpty()) {
            if (tryAutoVerify(immediateSms, servedRequestId)) return@LaunchedEffect
        }
        val immediateTail: String? = OtpManager.missedCallFlow.value
        if (!immediateTail.isNullOrBlank() && tcOtp.isEmpty()) {
            if (tryAutoVerify(immediateTail, servedRequestId)) return@LaunchedEffect
        }
        launch {
            OtpManager.missedCallFlow.collectLatest { tail: String? ->
                if (viewModel.tcAuthResult.value?.requestId != servedRequestId) return@collectLatest
                if (tail.isNullOrBlank()) return@collectLatest
                // Accept tail from ANY verification call — live API decides validity.
                tryAutoVerify(tail, servedRequestId)
            }
        }
        launch {
            OtpManager.otpFlow.collectLatest { code: String? ->
                if (viewModel.tcAuthResult.value?.requestId != servedRequestId) return@collectLatest
                if (code.isNullOrBlank()) return@collectLatest
                tryAutoVerify(code, servedRequestId)
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
        containerColor = MaterialTheme.colorScheme.background
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
                    color = contentSecondary(0.7f),
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
                                color = contentPrimary,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(Modifier.height(16.dp))

                            OutlinedTextField(
                                value = tcPhone,
                                onValueChange = { viewModel.setTcPhone(it) },
                                label = { Text("Phone Number", color = contentSecondary(0.5f)) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                leadingIcon = { Icon(Icons.Default.Phone, null, tint = Primary) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = contentPrimary,
                                    unfocusedTextColor = contentPrimary,
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = faintTint(0.2f)
                                )
                            )

                            Spacer(modifier = Modifier.height(32.dp))

                            authError?.let { err ->
                                Text(
                                    err,
                                    color = Color(0xFFFFB4A9),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth()
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .alpha(if (tcPhone.length >= 7 && !tcLoading) 1f else 0.5f)
                                    .brandGradient(radius = 16.dp)
                                    .clickable(enabled = tcPhone.length >= 7 && !tcLoading) {
                                        // Explain the three verification permissions before Android's
                                        // permission sheet is shown.
                                        if (!PermissionManager.hasPermissions(context, PermissionManager.VERIFY_PERMISSIONS)) {
                                            showVerifyPermissionsPopup = true
                                            return@clickable
                                        }
                                        tcLoading = true
                                        authError = null
                                        verifyError = null
                                        verifyErrorPopup = null
                                        autoConsumedCodes = emptySet()
                                        OtpManager.clearOtp()
                                        OtpManager.clearMissedCallTail()
                                        scope.launch {
                                            autoFillEnabled = PermissionManager.hasPermissions(context, PermissionManager.VERIFY_PERMISSIONS)
                                            val normalized = PhoneNumberUtils.normalize(tcPhone)
                                            val r = authManager.requestOtp(normalized)
                                            val result = if (r != null) com.infocaller.app.data.remote.TruecallerProviderImpl.AuthRequestResult(r.requestId, r.method, r.ttl, r.status, r.message) else null
                                            if (result == null) {
                                                authError = "Connection error — check internet"
                                                verifyErrorPopup = authError
                                            } else if (result.statusCode == -1) {
                                                authError = result.errorMessage ?: "Connection error. Check your internet."
                                                verifyErrorPopup = authError
                                            } else if (result.requestId.isBlank() && result.statusCode != 3) {
                                                authError = result.errorMessage?.takeIf { it.isNotBlank() }
                                                    ?: "Verification service unavailable (Error ${result.statusCode})."
                                                verifyErrorPopup = authError
                                            } else {
                                                viewModel.setTcAuthResult(result)
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
                                        "SEND VERIFICATION CODE",
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        } else {
                            Text(
                                "Enter Verification Code",
                                style = MaterialTheme.typography.titleMedium,
                                color = contentPrimary,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Text(
                                "Automatically verify call and OTP",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentSecondary(0.6f),
                                modifier = Modifier.padding(top = 4.dp).align(Alignment.Start)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (autoVerifying) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    CircularProgressIndicator(color = Primary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Automatic Verification...",
                                        color = Primary,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // ONE unified box: missed-call auto-rejects + verifies,
                            // SMS auto-verifies, WhatsApp code is typed manually.
                            OtpInputField(
                                otpText = tcOtp,
                                onOtpTextChange = { tcOtp = it; verifyError = null },
                                modifier = Modifier.wrapContentWidth()
                            )

                            TextButton(
                                onClick = {
                                    val raw = clipboardManager.getText()?.text?.toString().orEmpty()
                                    val digits = raw.filter { it.isDigit() }
                                    val code = when {
                                        raw.length == 6 && raw.all { it.isDigit() } -> raw
                                        digits.length == 6 -> digits
                                        digits.length > 6 -> digits.takeLast(6)
                                        else -> null
                                    }
                                    if (code != null) {
                                        tcOtp = code
                                        verifyError = null
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Clipboard has no 6-digit code — type it manually")
                                        }
                                    }
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, null, tint = contentSecondary(0.5f), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Paste Code", color = contentSecondary(0.5f), fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            verifyError?.let { err ->
                                Text(
                                    err,
                                    color = Color(0xFFFFB4A9),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth()
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .alpha(if (tcOtp.length in 4..10 && !tcLoading) 1f else 0.5f)
                                    .brandGradient(radius = 16.dp)
                                    .clickable(enabled = tcOtp.length in 4..10 && !tcLoading) {
                                        tcLoading = true
                                        verifyError = null
                                        scope.launch {
                                            val verifyResult = authManager.verifyOtp(tcPhone, tcAuthResult!!.requestId, tcOtp)
                                            if (verifyResult.success) {
                                                try {
                                                    context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                                        .edit().remove("last_tc_request_id").apply()
                                                } catch (_: Exception) { }
                                                OtpManager.clearOtp()
                                                OtpManager.clearMissedCallTail()
                                                viewModel.loginWithTruecaller(null)
                                            } else {
                                                val live = verifyResult.message ?: "Invalid OTP code. Please try again."
                                                verifyError = live
                                                verifyErrorPopup = live
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

                            var resendCooldown by remember { mutableStateOf(0) }
                            LaunchedEffect(resendCooldown) {
                                if (resendCooldown > 0) {
                                    kotlinx.coroutines.delay(1000)
                                    resendCooldown -= 1
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { viewModel.setTcAuthResult(null); tcOtp = ""; verifyError = null; authError = null }) {
                                    Text("Edit Phone Number", color = contentSecondary(0.6f))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    enabled = resendCooldown == 0 && !tcLoading,
                                    onClick = {
                                        resendCooldown = 60
                                        tcOtp = ""
                                        verifyError = null
                                        authError = null
                                        OtpManager.clearOtp()
                                        OtpManager.clearMissedCallTail()
                                        tcLoading = true
                                        scope.launch {
                                            val normalized = PhoneNumberUtils.normalize(tcPhone)
                                            val r = authManager.requestOtp(normalized)
                                            val result = if (r != null) com.infocaller.app.data.remote.TruecallerProviderImpl.AuthRequestResult(r.requestId, r.method, r.ttl, r.status, r.message) else null
                                            if (result == null || result.statusCode == -1) {
                                                authError = result?.errorMessage ?: "Connection error — check internet"
                                            } else if (result.requestId.isBlank() && result.statusCode != 3) {
                                                val isLimit = result.statusCode == 5 || result.statusCode == 6 || result.statusCode == 429
                                                authError = if (isLimit) "Too many requests. Try again after 1 hour."
                                                else result.errorMessage?.takeIf { it.isNotBlank() } ?: "Verification service unavailable (Error ${result.statusCode})."
                                            } else {
                                                autoConsumedCodes = emptySet()
                                                viewModel.setTcAuthResult(result)
                                                // No "Code resent" snackbar by design.
                                            }
                                            tcLoading = false
                                        }
                                    }
                                ) {
                                    Text(
                                        if (resendCooldown > 0) "Resend in ${resendCooldown}s" else "Resend Code",
                                        color = if (resendCooldown > 0) contentSecondary(0.35f) else Primary
                                    )
                                }
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
                    color = contentSecondary(0.4f),
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }

        // Live Truecaller verify/send failure popup. Shown ONLY when the
        // Truecaller API itself reports a problem.
        verifyErrorPopup?.let { popupMsg ->
            AlertDialog(
                onDismissRequest = { verifyErrorPopup = null },
                icon = { Icon(Icons.Default.VerifiedUser, null, tint = Primary) },
                title = { Text("Verification failed") },
                text = {
                    Text(
                        popupMsg,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { verifyErrorPopup = null }) {
                        Text("OK", color = Primary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        if (showVerifyPermissionsPopup) {
            AlertDialog(
                onDismissRequest = { showVerifyPermissionsPopup = false },
                icon = { Icon(Icons.Default.VerifiedUser, null, tint = Primary) },
                title = { Text("Verification permissions") },
                text = {
                    Text(
                        "InfoCaller needs SMS, call logs, and phone-call access to receive the verification code and verify it automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showVerifyPermissionsPopup = false
                        verifyPermissionLauncher.launch(PermissionManager.VERIFY_PERMISSIONS)
                    }) { Text("Allow", color = Primary, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showVerifyPermissionsPopup = false }) {
                        Text("Not now", color = contentSecondary(0.7f))
                    }
                }
            )
        }
    }
}
