package com.infocaller.app.ui.screens

import android.app.Activity
import android.content.IntentFilter
import android.util.Log
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
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.receiver.SmsUserConsentReceiver
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
    var verifyErrorPopup by remember { mutableStateOf<String?>(null) }
    var autoConsumedCodes by remember { mutableStateOf(setOf<String>()) }
    var isCallVerification by remember { mutableStateOf(false) }

    fun requestOtpNow() {
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
    }

    val verifyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted: Map<String, Boolean> ->
        autoFillEnabled = PermissionManager.hasPermissions(context, PermissionManager.VERIFY_PERMISSIONS)
        verifyError = null
        verifyErrorPopup = null
        authError = null
        if (!autoFillEnabled) {
            val allDeniedPermanently = try {
                val activity = context as? Activity
                activity != null && !PermissionManager.shouldShowRationale(activity, PermissionManager.VERIFY_PERMISSIONS)
            } catch (_: Exception) { false }
            val note = if (allDeniedPermanently) {
                "Phone & call log permission is off, so we can't auto-detect a verification call. An SMS code will still autofill, or type the code manually."
            } else {
                "Without Phone & call log permission we can't auto-detect a verification call. An SMS code will still autofill, or type the code manually."
            }
            scope.launch { snackbarHostState.showSnackbar(note) }
        }
        requestOtpNow()
    }

    val smsConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK) {
                val msg = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)
                val otp = SmsUserConsentReceiver.onConsentSmsMessage(msg)
                if (otp != null && tcOtp.isEmpty()) {
                    tcOtp = otp
                }
            }
        } catch (e: Exception) {
            Log.w("LoginScreen", "SMS consent result failed: ${e.message}")
        }
    }

    DisposableEffect(tcAuthResult) {
        if (tcAuthResult == null) {
            onDispose { }
        } else {
            val method = tcAuthResult!!.method.lowercase()
            val wantsSms = method.contains("sms") || method.contains("otp") ||
                method.contains("text") || method.contains("message") ||
                (!method.contains("call") && !method.contains("flash") && !method.contains("miss"))
            var receiver: SmsUserConsentReceiver? = null
            if (wantsSms) {
                try {
                    SmsRetriever.getClient(context).startSmsUserConsent(null)
                        .addOnSuccessListener {
                            Log.d("LoginScreen", "SMS User Consent started")
                        }
                        .addOnFailureListener { e ->
                            Log.w("LoginScreen", "SMS User Consent failed: ${e.message}")
                        }
                } catch (e: Exception) {
                    Log.w("LoginScreen", "startSmsUserConsent error: ${e.message}")
                }
                try {
                    receiver = SmsUserConsentReceiver { consentIntent ->
                        try {
                            smsConsentLauncher.launch(consentIntent)
                        } catch (e: Exception) {
                            Log.w("LoginScreen", "launch consent failed: ${e.message}")
                        }
                    }
                    val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(
                            receiver,
                            filter,
                            SmsRetriever.SEND_PERMISSION,
                            null,
                            android.content.Context.RECEIVER_NOT_EXPORTED
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        context.registerReceiver(
                            receiver,
                            filter,
                            SmsRetriever.SEND_PERMISSION,
                            null
                        )
                    }
                } catch (e: Exception) {
                    Log.w("LoginScreen", "register consent receiver failed: ${e.message}")
                    receiver = null
                }
            }
            onDispose {
                try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) { }
            }
        }
    }

    LaunchedEffect(tcAuthResult) {
        if (tcAuthResult == null) {
            autoConsumedCodes = emptySet()
            isCallVerification = false
            return@LaunchedEffect
        }
        val method = tcAuthResult!!.method.lowercase()
        if (method == "already_logged_in") {
            viewModel.loginWithTruecaller(null)
            return@LaunchedEffect
        }
        val servedRequestId = tcAuthResult!!.requestId
        val isCallMethod = com.infocaller.app.util.VerificationState.isCallMethod(method)
        val isSmsMethod = !isCallMethod
        isCallVerification = isCallMethod

        suspend fun isCancellationMsg(msg: String?): Boolean {
            if (msg.isNullOrBlank()) return false
            return msg.contains("scoped flow", ignoreCase = true) ||
                msg.contains("was cancelled", ignoreCase = true) ||
                msg.contains("Job was cancelled", ignoreCase = true)
        }

        suspend fun tryAutoVerify(codeRaw: String, rid: String, isMissedCall: Boolean = false): Boolean {
            if (autoVerifying || tcLoading) return false
            if (isMissedCall && !isCallMethod) return false
            if (!isMissedCall && !isSmsMethod) return false
            val digits = codeRaw.filter { it.isDigit() }
            val code = when {
                digits.length in 4..10 -> digits
                digits.length > 10 -> digits.takeLast(6)
                else -> return false
            }
            if (code.length !in 4..10) return false
            val attemptKey = "$rid:$code"
            if (autoConsumedCodes.contains(attemptKey)) return false
            autoConsumedCodes = autoConsumedCodes + attemptKey

            autoVerifying = true
            tcOtp = code
            verifyError = null
            verifyErrorPopup = null
            try {
                if (isMissedCall) {
                    delay(1200)
                }

                val phoneNow = try { viewModel.tcPhone.value } catch (_: Exception) { tcPhone }
                val retryDelays = if (isMissedCall) listOf(0L, 2500L) else listOf(0L)
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
                    if (res.status != 11 && res.status != 40101) break
                }

                val finalRes = res ?: com.infocaller.app.data.remote.TruecallerAuthManager.VerifyResult(
                    false, null, -1, "Verification failed"
                )

                if (finalRes.success) {
                    try {
                        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().remove("last_tc_request_id").remove("last_tc_method").apply()
                    } catch (_: Exception) { }
                    OtpManager.clearOtp()
                    OtpManager.clearMissedCallTail()
                    viewModel.loginWithTruecaller(null)
                    return true
                } else {
                    if (isCancellationMsg(finalRes.message)) return false
                    val live = finalRes.message?.takeIf { it.isNotBlank() } ?: "Invalid code"
                    verifyError = live
                    verifyErrorPopup = live
                    return false
                }
            } finally {
                autoVerifying = false
            }
        }

        if (isSmsMethod) {
            val immediateSms: String? = OtpManager.lastOtpFlow.value
            if (!immediateSms.isNullOrBlank() && tcOtp.isEmpty()) {
                if (tryAutoVerify(immediateSms, servedRequestId, isMissedCall = false)) return@LaunchedEffect
            }
        }
        if (isCallMethod) {
            val immediateTail: String? = OtpManager.missedCallFlow.value
            if (!immediateTail.isNullOrBlank() && tcOtp.isEmpty()) {
                if (tryAutoVerify(immediateTail, servedRequestId, isMissedCall = true)) return@LaunchedEffect
            }
        }
        var pendingMissedCallWatchdog: kotlinx.coroutines.Job? = null
        if (isCallMethod) {
            launch {
                OtpManager.missedCallEventFlow.collect { event ->
                    if (event == null || event.tail.isBlank()) return@collect
                    if (viewModel.tcAuthResult.value?.requestId != servedRequestId) return@collect
                    val digits = event.tail.filter { it.isDigit() }
                    val code = when {
                        digits.length in 4..10 -> digits
                        digits.length > 10 -> digits.takeLast(6)
                        else -> event.tail
                    }
                    if (event.isIdle) {
                        tcOtp = code
                    }
                    if (!event.isIdle) {
                        pendingMissedCallWatchdog?.cancel()
                        pendingMissedCallWatchdog = launch {
                            delay(6000)
                            tryAutoVerify(code, servedRequestId, isMissedCall = true)
                        }
                    } else {
                        pendingMissedCallWatchdog?.cancel()
                        tryAutoVerify(code, servedRequestId, isMissedCall = true)
                    }
                }
            }
        }
        if (isSmsMethod) {
            launch {
                OtpManager.otpFlow.collect { code: String? ->
                    if (viewModel.tcAuthResult.value?.requestId != servedRequestId) return@collect
                    if (code.isNullOrBlank()) return@collect
                    tryAutoVerify(code, servedRequestId, isMissedCall = false)
                }
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
                    text = if (autoVerifying) "Verifying…" else "Please wait…"
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
                    text = if (tcAuthResult == null) "Verify your number" else "Enter verification code",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (tcAuthResult == null)
                        "Enter your mobile number. We'll send a verification code to confirm it's you. Only the permission needed to receive that code is requested now — everything else comes after you're verified."
                    else
                        "Code sent to $tcPhone. Enter it below to continue.",
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
                                "Mobile number",
                                style = MaterialTheme.typography.titleMedium,
                                color = contentPrimary,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(Modifier.height(16.dp))

                            OutlinedTextField(
                                value = tcPhone,
                                onValueChange = { viewModel.setTcPhone(it) },
                                label = { Text("Mobile number", color = contentSecondary(0.5f)) },
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
                                        if (!PermissionManager.hasPermissions(context, PermissionManager.VERIFY_PERMISSIONS)) {
                                            verifyPermissionLauncher.launch(PermissionManager.VERIFY_PERMISSIONS)
                                            return@clickable
                                        }
                                        requestOtpNow()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (tcLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Text(
                                        "Get verification code",
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        } else {
                            Text(
                                "Enter verification code",
                                style = MaterialTheme.typography.titleMedium,
                                color = contentPrimary,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Text(
                                if (isCallVerification)
                                    "We're placing a verification call to $tcPhone. It's detected automatically — or enter the last 6 digits of the calling number."
                                else
                                    "We sent a 6-digit code to $tcPhone. Enter it below to continue.",
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
                                        "Verifying automatically…",
                                        color = Primary,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

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
                                            snackbarHostState.showSnackbar("No code found. Please enter it manually.")
                                        }
                                    }
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, null, tint = contentSecondary(0.5f), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Paste from clipboard", color = contentSecondary(0.5f), fontSize = 12.sp)
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
                                                    val isCancel = live.contains("scoped flow", ignoreCase = true) ||
                                                        live.contains("was cancelled", ignoreCase = true) ||
                                                        live.contains("Job was cancelled", ignoreCase = true)
                                                    if (!isCancel) {
                                                        verifyError = live
                                                        verifyErrorPopup = live
                                                    }
                                                }
                                            } catch (e: CancellationException) {
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
                                        "Verify",
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
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                TextButton(onClick = { viewModel.setTcAuthResult(null); tcOtp = ""; verifyError = null; authError = null }) {
                                    Text("Change number", color = contentSecondary(0.6f), fontSize = 13.sp, maxLines = 1)
                                }
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
                                            }
                                            tcLoading = false
                                        }
                                    }
                                ) {
                                    Text(
                                        if (resendCooldown > 0) "Resend in ${resendCooldown}s" else "Resend code",
                                        color = if (resendCooldown > 0) contentSecondary(0.35f) else Primary,
                                        fontSize = 13.sp,
                                        maxLines = 1
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
                        "Protected with secure verification",
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
    }
}
