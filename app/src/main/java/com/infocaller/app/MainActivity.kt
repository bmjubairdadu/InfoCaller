package com.infocaller.app

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.telecom.TelecomManager
import android.telecom.PhoneAccountHandle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.infocaller.app.ui.navigation.NavGraph
import com.infocaller.app.ui.theme.InfoCallerTheme
import com.infocaller.app.ui.viewmodel.AuthViewModel
import com.infocaller.app.ui.viewmodel.CallerViewModel
import com.infocaller.app.util.SimInfo
import com.infocaller.app.util.SimManager

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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

        // Diagnostic: Check Network
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        Log.d("Diagnostic", "Internet Available: ${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true}")
        
        val app = application as InfoCallerApplication
        val enrichmentService = com.infocaller.app.data.repository.ContactEnrichmentService(this)
        val viewModelFactory = CallerViewModel.Factory(app.repository, app.deviceDataRepository, enrichmentService, app.database, app.lookupEngine)
        val authViewModelFactory = AuthViewModel.Factory(app.authRepository)

        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            InfoCallerTheme {
                val navController = rememberNavController()
                val viewModel: CallerViewModel = viewModel(factory = viewModelFactory)
                val authViewModel: AuthViewModel = viewModel(factory = authViewModelFactory)

                NavGraph(
                    navController = navController,
                    viewModel = viewModel,
                    authViewModel = authViewModel
                ) { number -> 
                    // STAGE 1: Automatic search before calling
                    viewModel.searchNumber(number)
                    makeCall(number) 
                }
            }
        }
    }


    fun makeCall(phoneNumber: String) {
        val simInfos = try {
            SimManager.getSimInfos(this)
        } catch (_: Exception) {
            emptyList()
        }
        
        if (simInfos.size > 1) {
            showSimSelectionDialog(phoneNumber)
        } else if (simInfos.size == 1) {
            val sim = simInfos[0]
            sim.phoneAccountHandle?.let { handle ->
                try {
                    SimManager.makeCallWithSim(this, phoneNumber, handle)
                } catch (_: Exception) {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = "tel:$phoneNumber".toUri()
                    }
                    startActivity(intent)
                }
            } ?: run {
                // Fallback to dialer if no phone account handle
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = "tel:$phoneNumber".toUri()
                }
                startActivity(intent)
            }
        } else {
            // No SIM found, fallback to dialer
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = "tel:$phoneNumber".toUri()
            }
            startActivity(intent)
        }
    }

    private fun showSimSelectionDialog(phoneNumber: String) {
        val simInfos = SimManager.getSimInfos(this)
        showLegacySimDialog(phoneNumber, simInfos)
    }

    private fun showLegacySimDialog(phoneNumber: String, simInfos: List<SimInfo>) {
        val items = simInfos.map { sim ->
            val displayName = if (sim.displayName != sim.carrierName) {
                "${sim.displayName} (${sim.carrierName})"
            } else {
                sim.displayName
            }
            displayName
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Select SIM to call $phoneNumber")
            .setItems(items) { _, which ->
                val selectedSim = simInfos[which]
                selectedSim.phoneAccountHandle?.let { handle ->
                    SimManager.makeCallWithSim(this@MainActivity, phoneNumber, handle)
                } ?: run {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = "tel:$phoneNumber".toUri()
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
