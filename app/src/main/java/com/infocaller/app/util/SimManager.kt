package com.infocaller.app.util

import android.content.Context
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.net.toUri
import kotlin.math.abs

data class SimInfo(
    val slotIndex: Int,
    val carrierName: String,
    val displayName: String,
    val countryIso: String,
    val mcc: String?,
    val mnc: String?,
    val phoneAccountHandle: PhoneAccountHandle?,
    val brandColor: Int = 0xFF607D8B.toInt(),
)

object SimManager {

    private val carrierBrandColors = mapOf(
        "Airtel" to 0xFFE4002B,
        "Jio" to 0xFF0055D4,
        "Vi" to 0xFFE60000,
        "Vodafone" to 0xFFE60000,
        "Idea" to 0xFF9C27B0,
        "BSNL" to 0xFF1565C0,
        "MTNL" to 0xFF3F51B5,
        "Telenor" to 0xFF00A7E1,
        "Grameenphone" to 0xFFEF3340,
        "Banglalink" to 0xFFFDB913,
        "Robi" to 0xFFE8107C,
        "Teletalk" to 0xFF0072BC,
        "Airtel Bangladesh" to 0xFFE4002B,
        "Telkomsel" to 0xFFD4021D,
        "Indosat" to 0xFFE30613,
        "Verizon" to 0xFFCD040B,
        "AT&T" to 0xFF00A4E0,
        "T-Mobile" to 0xFFE20074,
        "Sprint" to 0xFFFFCB05,
        "Rogers" to 0xFF004C97,
        "Bell" to 0xFF0072CE,
        "Telus" to 0xFF4A00A8,
        "Optus" to 0xFFFF6600,
        "Telstra" to 0xFF00A3E0,
        "EE" to 0xFFFE8D27,
        "O2" to 0xFF001E5B,
        "Orange" to 0xFFFF7900,
        "Three" to 0xFF00A3E0,
        "Giffgaff" to 0xFFEF6726,
        "Vodafone UK" to 0xFFE60000,
        "Ooredoo" to 0xFFED1C24,
        "Zain" to 0xFF009FE3,
        "STC" to 0xFF2D9CDB,
        "Du" to 0xFFE41B1B,
        "Etisalat" to 0xFF6F2DA8,
        "Globe" to 0xFF0067A5,
        "Smart" to 0xFF7B1FA2,
        "Viettel" to 0xFF0099CC,
        "Mobifone" to 0xFF61BA55,
        "Vinaphone" to 0xFFE4002B,
        "AIS" to 0xFF1BA8E0,
        "TrueMove" to 0xFF75B62E,
        "DTAC" to 0xFFE40046,
        "Lyca Mobile" to 0xFFE4002B,
        "Lebara" to 0xFFF69900,
        "Beeline" to 0xFFFFCC00,
        "MegaFon" to 0xFF00A950,
        "Tele2" to 0xFF0061FF,
        "Yota" to 0xFF00AEEF,
        "MTS Russia" to 0xFFE40000,
        "MTS" to 0xFFE40000
    )

    private val fallbackPalette = listOf(
        0xFFE4002B, 0xFF0055D4, 0xFFE60000, 0xFF9C27B0, 0xFFFDB913,
        0xFF00A651, 0xFF0072BC, 0xFFFF8800, 0xFF7B1FA2, 0xFF00838F
    )

    private fun getCarrierColor(carrierName: String): Int {
        val normalized = carrierName.lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace(".", "")

        for ((key, color) in carrierBrandColors) {
            val keyNormalized = key.lowercase()
                .replace(" ", "")
                .replace("-", "")
                .replace(".", "")
            if (normalized.contains(keyNormalized) || keyNormalized.contains(normalized)) {
                return color.toInt()
            }
        }

        return fallbackPalette[abs(carrierName.hashCode()) % fallbackPalette.size].toInt()
    }

    fun getSimInfos(context: Context): List<SimInfo> {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        
        val phoneAccounts = try {
            telecomManager.callCapablePhoneAccounts
        } catch (_: SecurityException) {
            emptyList<PhoneAccountHandle>()
        }
        val subInfos = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        
        val simInfos = mutableListOf<SimInfo>()
        
        for (subInfo in subInfos) {
            val carrierName = subInfo.carrierName.toString()
            val displayName = subInfo.displayName.toString()
            val countryIso = subInfo.countryIso
            val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subInfo.mccString else subInfo.mcc.let { if (it == 0) null else it.toString() }
            val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subInfo.mncString else subInfo.mnc.let { if (it == 0) null else it.toString() }
            val slotIndex = subInfo.simSlotIndex
            
            val phoneAccountHandle = phoneAccounts.firstOrNull { 
                it.id == subInfo.subscriptionId.toString() 
            }
            
            val brandColor = getCarrierColor(carrierName)
            
            simInfos.add(
                SimInfo(
                    slotIndex = slotIndex,
                    carrierName = carrierName,
                    displayName = displayName.ifBlank { carrierName },
                    countryIso = countryIso,
                    mcc = mcc,
                    mnc = mnc,
                    phoneAccountHandle = phoneAccountHandle,
                    brandColor = brandColor,
                )
            )
        }
        
        return simInfos.sortedBy { it.slotIndex }
    }

    fun makeCallWithSim(context: Context, phoneNumber: String, phoneAccountHandle: PhoneAccountHandle) {
        val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
            data = "tel:$phoneNumber".toUri()
            putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
