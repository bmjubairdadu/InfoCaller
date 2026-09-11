package com.infocaller.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
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
import com.infocaller.app.ui.theme.Secondary
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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

        suspend fun isCancellationMsg(msg: String?): Boolean {
            if (msg.isNullOrBlank()) return false
            return msg.contains("scoped flow", ignoreCase = true) ||
                msg.contains("was cancelled", ignoreCase = true) ||
                msg.contains("Job was cancelled", ignoreCase = true)
        }

        suspend fun tryAutoVerify(codeRaw: String, rid: String, isMissedCall: Boolean = false): Boolean {
            // Never let coroutine cancellation become a user-visible error.
            if (autoVerifying || tcLoading) return false
            val digits = codeRaw.filter { it.isDigit() }
            val code = when {
                digits.length in 4..10 -> digits
                digits.length > 10 -> digits.takeLast(6)
                else -> return false
            }
            if (code.length !in 4..10) return false
            val attemptKey = "$rid:$code"
            if (autoConsumedCodes.contains(attemptKey)) return false

            autoVerifying = true
            tcOtp = code
            verifyError = null
            verifyErrorPopup = null
            try {
                // If it's a missed call, wait ~1.2s for Truecaller's telecom partner
                // to register the drop-call event on their gateway before we verify.
                if (isMissedCall) {
                    delay(1200)
                }

                val phoneNow = try { viewModel.tcPhone.value } catch (_: Exception) { tcPhone }
                // Retry loop for status 11 (gateway still syncing drop-call):
                // 4 attempts spaced out (~1.2s, ~3.2s, ~5.7s, ~8.7s after drop call).
                val retryDelays = if (isMissedCall) listOf(0L, 2000L, 2500L, 3000L) else listOf(0L)
                var res: com.infocaller.app.data.remote.TruecallerAuthManager.VerifyResult? = null

                for (retryDelay in retryDelays) {
                    if (retryDelay > 0) {
                        delay(retryDelay)
                    }
                    res = try {
                        authManager.verifyOtp(phoneNow, rid, code)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isCancellationMsg(e.message)) throw e
                        com.infocaller.app.data.remote.TruecallerAuthManager.VerifyResult(
                            false, null, -1, e.message ?: "Network error"
                        )
                    }

                    if (res.success) break
                    // Only retry if error is status 11 (Invalid OTP/carrier sync delay) or status 40101
                    if (res.status != 11 && res.status != 40101) break
                }

                val finalRes = res ?: com.infocaller.app.data.remote.TruecallerAuthManager.VerifyResult(
                    false, null, -1, "Verification failed"
                )

                if (finalRes.success) {
                    autoConsumedCodes = autoConsumedCodes + attemptKey
                    try {
                        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().remove("last_tc_request_id").remove("last_tc_method").apply()
                    } catch (_: Exception) { }
                    OtpManager.clearOtp()
                    OtpManager.clearMissedCallTail()
                    viewModel.loginWithTruecaller(null)
                    return true
                } else {
                    // Swallow cancellation races (manual verify won / screen navigating).
                    if (isCancellationMsg(finalRes.message)) return false
                    val live = finalRes.message ?: "Invalid code"
                    verifyError = live
                    verifyErrorPopup = live
                    // Non-destructive: keep tcOtp = code so the user can easily review or tap manual verify!
                    return false
                }
            } finally {
                autoVerifying = false
            }
        }

        // Code already arrived before this screen recomposed (SMS or missed-call).
        val immediateSms: String? = OtpManager.lastOtpFlow.value
        if (!immediateSms.isNullOrBlank() && tcOtp.isEmpty()) {
            if (tryAutoVerify(immediateSms, servedRequestId, isMissedCall = false)) return@LaunchedEffect
        }
        val immediateTail: String? = OtpManager.missedCallFlow.value
        if (!immediateTail.isNullOrBlank() && tcOtp.isEmpty()) {
            if (tryAutoVerify(immediateTail, servedRequestId, isMissedCall = true)) return@LaunchedEffect
        }
        var pendingMissedCallWatchdog: kotlinx.coroutines.Job? = null
        launch {
            // Collect each missed call event (ringing and disconnect/idle)
            OtpManager.missedCallEventFlow.collect { event ->
                if (event == null || event.tail.isBlank()) return@collect
                if (viewModel.tcAuthResult.value?.requestId != servedRequestId) return@collect
                val digits = event.tail.filter { it.isDigit() }
                val code = when {
                    digits.length in 4..10 -> digits
                    digits.length > 10 -> digits.takeLast(6)
                    else -> event.tail
                }
                tcOtp = code
                if (!event.isIdle) {
                    // Call is actively ringing: wait for disconnect, but set a fallback watchdog in case IDLE is lost
                    pendingMissedCallWatchdog?.cancel()
                    pendingMissedCallWatchdog = launch {
                        delay(6000)
                        tryAutoVerify(code, servedRequestId, isMissedCall = true)
                    }
                } else {
                    // Call has disconnected (IDLE): verify now!
                    pendingMissedCallWatchdog?.cancel()
                    tryAutoVerify(code, servedRequestId, isMissedCall = true)
                }
            }
        }
        launch {
            OtpManager.otpFlow.collect { code: String? ->
                if (viewModel.tcAuthResult.value?.requestId != servedRequestId) return@collect
                if (code.isNullOrBlank()) return@collect
                tryAutoVerify(code, servedRequestId, isMissedCall = false)
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
                                    .clickable(enabled = tcOtp.length in 4..10 && !tcLoading && !autoVerifying) {
                                        if (tcLoading || autoVerifying) return@clickable
                                        val reqId = tcAuthResult?.requestId ?: return@clickable
                                        val codeSnap = tcOtp.filter { it.isDigit() }
                                        val phoneSnap = tcPhone
                                        if (codeSnap.length !in 4..10) return@clickable
                                        // Claim this code so auto collectors don't double-verify it.
                                        autoConsumedCodes = autoConsumedCodes + "$reqId:$codeSnap"
                                        tcLoading = true
                                        verifyError = null
                                        verifyErrorPopup = null
                                        scope.launch {
                                            try {
                                                val verifyResult = authManager.verifyOtp(phoneSnap, reqId, codeSnap)
                                                if (verifyResult.success) {
                                                    try {
                                                        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                                            .edit().remove("last_tc_request_id").remove("last_tc_method").apply()
                                                    } catch (_: Exception) { }
                                                    OtpManager.clearOtp()
                                                    OtpManager.clearMissedCallTail()
                                                    viewModel.loginWithTruecaller(null)
                                                } else {
                                                    val live = verifyResult.message ?: "Invalid OTP code. Please try again."
                                                    // Never show coroutine cancellation as a verify error.
                                                    val isCancel = live.contains("scoped flow", ignoreCase = true) ||
                                                        live.contains("was cancelled", ignoreCase = true) ||
                                                        live.contains("Job was cancelled", ignoreCase = true)
                                                    if (!isCancel) {
                                                        verifyError = live
                                                        verifyErrorPopup = live
                                                    }
                                                }
                                            } catch (e: CancellationException) {
                                                // Screen navigating away — silently stop, never popup.
                                                throw e
                                            } catch (e: Exception) {
                                                val msg = e.message.orEmpty()
                                                val isCancel = msg.contains("scoped flow", ignoreCase = true) ||
                                                    msg.contains("was cancelled", ignoreCase = true)
                                                if (!isCancel) {
                                                    verifyError = msg.ifBlank { "Invalid OTP code. Please try again." }
                                                    verifyErrorPopup = verifyError
                                                }
                                            } finally {
                                                tcLoading = false
                                            }
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
            VerifyPermissionsDialog(
                onAllow = {
                    showVerifyPermissionsPopup = false
                    verifyPermissionLauncher.launch(PermissionManager.VERIFY_PERMISSIONS)
                },
                onDismiss = { showVerifyPermissionsPopup = false }
            )
        }
    }
}

@Composable
private fun VerifyPermissionsDialog(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF141B2D), Color(0xFF0B1120))
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Primary.copy(alpha = 0.55f),
                            Primary.copy(alpha = 0.08f),
                        )
                    ),
                    shape = RoundedCornerShape(28.dp),
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .brandGradient(radius = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "Verify your number",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Allow these once — the code is picked up automatically and you jump straight to OTP.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
            Spacer(modifier = Modifier.height(18.dp))

            VerifyPermissionRow(
                icon = Icons.Default.Sms,
                accent = TruecallerBlue,
                title = "SMS",
                desc = "Reads only the incoming verification code — inbox is never opened.",
            )
            Spacer(modifier = Modifier.height(10.dp))
            VerifyPermissionRow(
                icon = Icons.Default.History,
                accent = Secondary,
                title = "Call log",
                desc = "Detects the missed-call verification so OTP fills without typing.",
            )
            Spacer(modifier = Modifier.height(10.dp))
            VerifyPermissionRow(
                icon = Icons.Default.Call,
                accent = Primary,
                title = "Phone",
                desc = "Confirms your SIM number and ends the verify call automatically.",
            )

            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .brandGradient(radius = 16.dp)
                    .clickable(onClick = onAllow),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Allow & Continue",
                    color = Color.Black,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss) {
                Text(
                    "Not now",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun VerifyPermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    title: String,
    desc: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.62f),
                lineHeight = 17.sp,
            )
        }
    }
}
