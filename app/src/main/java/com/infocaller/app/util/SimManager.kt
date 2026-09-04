package com.infocaller.app.util

import android.content.Context
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.net.toUri

data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrierName: String,
    val displayName: String,
    val countryIso: String,
    val mcc: String?,
    val mnc: String?,
    val phoneAccountHandle: PhoneAccountHandle?,
    val brandColor: Int,
    val iconBitmap: android.graphics.Bitmap? = null,
    val localLogoPath: String? = null
)

object SimManager {

    fun buildBrandfetchLogoUrl(officialDomain: String): String {
        val id = try { com.infocaller.app.BuildConfig.BRANDFETCH_CLIENT_ID } catch(_:Exception) { "1idt4fOOzudt9xCz11q" }
        return "https://cdn.brandfetch.io/domain/$officialDomain?c=$id"
    }

    suspend fun getSimInfos(context: Context): List<SimInfo> {
        val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        
        val phoneAccounts = try {
            telecomManager.callCapablePhoneAccounts
        } catch (_: SecurityException) {
            emptyList<PhoneAccountHandle>()
        }
        val subInfos = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (_: SecurityException) {
            // READ_PHONE_STATE not granted yet (e.g. first launch before onboarding) — no SIMs to show.
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        
        val simInfos = mutableListOf<SimInfo>()
        
        for (subInfo in subInfos) {
            val carrierName = subInfo.carrierName?.toString() ?: "Unknown"
            val displayName = subInfo.displayName?.toString() ?: "SIM ${subInfo.simSlotIndex + 1}"
            val countryIso = subInfo.countryIso ?: ""
            val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subInfo.mccString else subInfo.mcc.let { if (it == 0) null else it.toString() }
            val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subInfo.mncString else subInfo.mnc.let { if (it == 0) null else it.toString() }
            val slotIndex = subInfo.simSlotIndex
            
            val phoneAccountHandle = phoneAccounts.firstOrNull { 
                it.id == subInfo.subscriptionId.toString() 
            }
            
            val brand = OperatorBrandResolver.resolveBrand(carrierName, displayName, mcc, mnc)
            
            val iconBitmap = try {
                subInfo.createIconBitmap(context)
            } catch (_: Exception) {
                null
            }
            
            val tempSimInfo = SimInfo(
                subscriptionId = subInfo.subscriptionId,
                slotIndex = slotIndex,
                carrierName = brand.operatorName,
                displayName = displayName,
                countryIso = countryIso,
                mcc = mcc,
                mnc = mnc,
                phoneAccountHandle = phoneAccountHandle,
                brandColor = brand.brandColor,
                iconBitmap = iconBitmap
            )

            val localPath = app.operatorLogoManager.getLocalLogoPath(tempSimInfo)
            
            simInfos.add(tempSimInfo.copy(localLogoPath = localPath))
        }
        
        return simInfos.sortedBy { it.slotIndex }
    }

    fun placeCall(context: Context, phoneNumber: String, phoneAccountHandle: PhoneAccountHandle? = null) {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val action = if (hasPermission) android.content.Intent.ACTION_CALL else android.content.Intent.ACTION_DIAL
        
        val encodedNumber = if (phoneNumber.contains("#")) {
            phoneNumber.replace("#", android.net.Uri.encode("#"))
        } else {
            phoneNumber
        }

        val intent = android.content.Intent(action).apply {
            data = "tel:$encodedNumber".toUri()
            if (phoneAccountHandle != null) {
                putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            }
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
