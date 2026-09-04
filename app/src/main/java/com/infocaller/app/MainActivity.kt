package com.infocaller.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.infocaller.app.ui.navigation.NavGraph
import com.infocaller.app.ui.theme.InfoCallerTheme
import com.infocaller.app.ui.viewmodel.AuthViewModel
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.util.SimManager
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // WorkManager may not be initialized on some devices/ROMs — a throw here
        // would crash onCreate ("keeps stopping" on launch), so never let it escape.
        try {
            val workRequest = androidx.work.PeriodicWorkRequestBuilder<com.infocaller.app.worker.EnrichmentWorker>(
                1, java.util.concurrent.TimeUnit.HOURS
            )
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "EnrichmentSync",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (_: Exception) { }

        try {
            val updateRequest = androidx.work.PeriodicWorkRequestBuilder<com.infocaller.app.worker.ProviderUpdateWorker>(
                12, java.util.concurrent.TimeUnit.HOURS
            )
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .build()

            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "ProviderUpdate",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                updateRequest
            )
        } catch (_: Exception) { }

        // Community DB auto-download: periodic (KEEP = idempotent) + one immediate
        // sync only on cold start — NOT on every rotation/recreation.
        try {
            com.infocaller.app.worker.CommunitySyncWorker.schedulePeriodic(this)
            if (savedInstanceState == null) {
                com.infocaller.app.worker.CommunitySyncWorker.triggerNow(this)
            }
        } catch (_: Exception) { }

        enableEdgeToEdge()
        setContent {
            val app = application as InfoCallerApplication
            val enrichmentService = com.infocaller.app.data.repository.ContactEnrichmentService(
                this, 
                app.lookupEngine, 
                app.repository,
                app.database
            )
            val viewModelFactory = CallerViewModel.Factory(app.repository, app.deviceDataRepository, enrichmentService, app.database, app.lookupEngine)
            val authViewModelFactory = AuthViewModel.Factory(app.authRepository, this)

            val viewModel: CallerViewModel = viewModel(factory = viewModelFactory)
            val authViewModel: AuthViewModel = viewModel(factory = authViewModelFactory)
            
            val context = androidx.compose.ui.platform.LocalContext.current
            
            LaunchedEffect(Unit) {
                val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val stored = if (prefs.contains("dark_theme")) prefs.getBoolean("dark_theme", true) else true
                viewModel.setThemeMode(stored, context)
            }

            val themeMode by viewModel.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                true -> true
                false -> false
                null -> true
            }

            InfoCallerTheme(darkTheme = darkTheme) {
                val navController = rememberNavController()

                NavGraph(
                    navController = navController,
                    viewModel = viewModel,
                    authViewModel = authViewModel
                ) { number -> 
                    viewModel.searchNumber(number)
                    makeCall(viewModel, number) 
                }

                LaunchedEffect(intent) {
                    handleIntent(intent, viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun handleIntent(intent: Intent, viewModel: CallerViewModel) {
        val uri = intent.data
        if (uri != null && uri.scheme == "tel") {
            val number = uri.schemeSpecificPart
            if (number.isNotBlank()) {
                viewModel.updateDialerInput(number)
            }
        }
    }


    private fun makeCall(viewModel: CallerViewModel, phoneNumber: String) {
        val needCall = com.infocaller.app.permissions.PermissionManager.DIALER_PERMISSIONS
        if (!com.infocaller.app.permissions.PermissionManager.hasPermissions(this, needCall)) {
            androidx.core.app.ActivityCompat.requestPermissions(this, needCall, 1001)
            getSharedPreferences("pending_call", MODE_PRIVATE).edit().putString("number", phoneNumber).apply()
            return
        }
        lifecycleScope.launch {
            val simInfos = try { SimManager.getSimInfos(this@MainActivity) } catch (_: Exception) { emptyList() }
            if (simInfos.size > 1) viewModel.showSimSelection(phoneNumber)
            else if (simInfos.size == 1) SimManager.placeCall(this@MainActivity, phoneNumber, simInfos[0].phoneAccountHandle)
            else SimManager.placeCall(this@MainActivity, phoneNumber)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            val pending = getSharedPreferences("pending_call", MODE_PRIVATE).getString("number", null)
            if (!pending.isNullOrBlank()) {
                getSharedPreferences("pending_call", MODE_PRIVATE).edit().remove("number").apply()
                lifecycleScope.launch {
                    val vm = (application as com.infocaller.app.InfoCallerApplication).let { null }
                }
                SimManager.placeCall(this, pending)
            }
        }
    }
}
