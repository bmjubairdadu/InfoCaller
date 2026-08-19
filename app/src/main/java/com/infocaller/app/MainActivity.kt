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
        
        // Schedule periodic enrichment sync
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

        // Schedule Provider Update Check
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
            val authViewModelFactory = AuthViewModel.Factory(app.authRepository)

            val viewModel: CallerViewModel = viewModel(factory = viewModelFactory)
            val authViewModel: AuthViewModel = viewModel(factory = authViewModelFactory)
            
            val context = androidx.compose.ui.platform.LocalContext.current
            
            // Initialize theme from prefs once
            LaunchedEffect(Unit) {
                val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val stored = if (prefs.contains("dark_theme")) prefs.getBoolean("dark_theme", true) else null
                viewModel.setThemeMode(stored, context)
            }

            val themeMode by viewModel.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                true -> true
                false -> false
                null -> androidx.compose.foundation.isSystemInDarkTheme()
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
        lifecycleScope.launch {
            val simInfos = try {
                SimManager.getSimInfos(this@MainActivity)
            } catch (_: Exception) {
                emptyList()
            }
            
            if (simInfos.size > 1) {
                viewModel.showSimSelection(phoneNumber)
            } else if (simInfos.size == 1) {
                val sim = simInfos[0]
                SimManager.placeCall(this@MainActivity, phoneNumber, sim.phoneAccountHandle)
            } else {
                SimManager.placeCall(this@MainActivity, phoneNumber)
            }
        }
    }
}
