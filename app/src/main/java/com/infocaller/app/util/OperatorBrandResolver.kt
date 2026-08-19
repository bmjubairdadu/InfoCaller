package com.infocaller.app.util

import android.graphics.Color

data class OperatorBrand(
    val operatorName: String,
    val normalizedName: String,
    val brandColor: Int,
    val officialDomain: String? = null,
    val logoResId: Int? = null
)

object OperatorBrandResolver {

    // Primary lookup by MCC + MNC (Country specific)
    private val mccMncMap = mapOf(
        // Bangladesh
        "47001" to OperatorBrand("Grameenphone", "grameenphone", 0xFF00A7E1.toInt(), "grameenphone.com"),
        "47002" to OperatorBrand("Robi", "robi", 0xFFE8107C.toInt(), "robi.com.bd"),
        "47003" to OperatorBrand("Banglalink", "banglalink", 0xFFFF8200.toInt(), "banglalink.net"),
        "47004" to OperatorBrand("Teletalk", "teletalk", 0xFF00833E.toInt(), "teletalk.com.bd"),
        "47007" to OperatorBrand("Airtel", "airtel", 0xFFE4002B.toInt(), "bd.airtel.com"),
        
        // India
        "404" to OperatorBrand("Airtel India", "airtel", 0xFFE4002B.toInt(), "airtel.in"),
        "405" to OperatorBrand("Jio", "jio", 0xFF0055D4.toInt(), "jio.com"),
        "406" to OperatorBrand("Vi", "vi", 0xFFE60000.toInt(), "myvi.in"),
        
        // USA
        "310120" to OperatorBrand("Sprint", "sprint", 0xFFFFE100.toInt(), "sprint.com"),
        "310260" to OperatorBrand("T-Mobile US", "t-mobile", 0xFFE20074.toInt(), "t-mobile.com"),
        "310410" to OperatorBrand("AT&T", "at&t", 0xFF00A8E0.toInt(), "att.com"),
        "311480" to OperatorBrand("Verizon", "verizon", 0xFFCD040B.toInt(), "verizon.com"),
        
        // UK
        "23410" to OperatorBrand("O2 UK", "o2", 0xFF001E5B.toInt(), "o2.co.uk"),
        "23415" to OperatorBrand("Vodafone UK", "vodafone", 0xFFE60000.toInt(), "vodafone.co.uk"),
        "23430" to OperatorBrand("EE", "ee", 0xFFFE8D27.toInt(), "ee.co.uk"),
        "23420" to OperatorBrand("Three UK", "three", 0xFF000000.toInt(), "three.co.uk"),
        
        // UAE
        "42402" to OperatorBrand("Etisalat", "etisalat", 0xFF00833E.toInt(), "etisalat.ae"),
        "42403" to OperatorBrand("Du", "du", 0xFF00A8E0.toInt(), "du.ae"),
        
        // Saudi Arabia
        "42001" to OperatorBrand("STC", "stc", 0xFF4F2D7F.toInt(), "stc.com.sa"),
        "42004" to OperatorBrand("Zain SA", "zain", 0xFF000000.toInt(), "sa.zain.com"),
        "42003" to OperatorBrand("Mobily", "mobily", 0xFF65B32E.toInt(), "mobily.com.sa")
    )

    // Secondary lookup by name normalization (Fallback if MCC/MNC fails)
    private val nameToBrandMap = mapOf(
        "grameenphone" to Pair("Grameenphone", "grameenphone.com"),
        "gp" to Pair("Grameenphone", "grameenphone.com"),
        "robi" to Pair("Robi", "robi.com.bd"),
        "banglalink" to Pair("Banglalink", "banglalink.net"),
        "teletalk" to Pair("Teletalk", "teletalk.com.bd"),
        "airtel" to Pair("Airtel", "airtel.com"),
        "jio" to Pair("Jio", "jio.com"),
        "vi" to Pair("Vi", "myvi.in"),
        "vodafone" to Pair("Vodafone", "vodafone.com"),
        "verizon" to Pair("Verizon", "verizon.com"),
        "att" to Pair("AT&T", "att.com"),
        "tmobile" to Pair("T-Mobile", "t-mobile.com"),
        "orange" to Pair("Orange", "orange.com"),
        "o2" to Pair("O2", "o2.com"),
        "ee" to Pair("EE", "ee.co.uk"),
        "three" to Pair("Three", "three.com"),
        "etisalat" to Pair("Etisalat", "etisalat.com"),
        "du" to Pair("Du", "du.ae"),
        "stc" to Pair("STC", "stc.com.sa"),
        "zain" to Pair("Zain", "zain.com"),
        "globe" to Pair("Globe", "globe.com.ph"),
        "smart" to Pair("Smart", "smart.com.ph"),
        "viettel" to Pair("Viettel", "viettel.com.vn"),
        "mtn" to Pair("MTN", "mtn.co.za")
    )

    fun resolveBrand(
        carrierName: String?,
        displayName: String?,
        mcc: String?,
        mnc: String?
    ): OperatorBrand {
        // 1. Precise Resolution via MCC + MNC
        if (mcc != null && mnc != null) {
            val fullKey = "$mcc$mnc"
            mccMncMap[fullKey]?.let { return it }
            
            // Try MCC prefix (Country level fallback)
            mccMncMap[mcc]?.let { return it }
        }

        // 2. Name-based Resolution (Normalization)
        val rawName = (carrierName ?: displayName ?: "Unknown").lowercase()
        val normalizedToken = rawName.replace(" ", "").replace("-", "").replace("_", "").replace("&", "")

        for ((token, info) in nameToBrandMap) {
            if (normalizedToken.contains(token)) {
                return OperatorBrand(
                    operatorName = info.first,
                    normalizedName = token,
                    brandColor = getDerivedColor(token),
                    officialDomain = info.second
                )
            }
        }

        // 3. Absolute Fallback
        return OperatorBrand(
            operatorName = carrierName ?: displayName ?: "Unknown",
            normalizedName = "unknown",
            brandColor = 0xFFFBBF24.toInt() // Amber (InfoCaller Brand)
        )
    }

    private fun getDerivedColor(name: String): Int {
        return when (name.lowercase()) {
            "grameenphone" -> 0xFF00A7E1.toInt()
            "airtel" -> 0xFFE4002B.toInt()
            "robi" -> 0xFFE8107C.toInt()
            "banglalink" -> 0xFFFF8200.toInt()
            "teletalk" -> 0xFF00833E.toInt()
            "jio" -> 0xFF0055D4.toInt()
            "vi", "vodafone" -> 0xFFE60000.toInt()
            "t-mobile", "tmobile" -> 0xFFE20074.toInt()
            "orange" -> 0xFFFF7900.toInt()
            "verizon" -> 0xFFCD040B.toInt()
            "at&t", "att" -> 0xFF00A4E0.toInt()
            "stc" -> 0xFF4F2D7F.toInt()
            "mobily" -> 0xFF65B32E.toInt()
            else -> {
                val hue = (name.hashCode() % 360).toFloat()
                Color.HSVToColor(floatArrayOf(if (hue < 0) hue + 360 else hue, 0.7f, 0.8f))
            }
        }
    }
}
