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

    private val mccMncMap = mapOf(
        "47001" to OperatorBrand("Grameenphone", "grameenphone", 0xFF00A7E1.toInt(), "grameenphone.com"),
        "47002" to OperatorBrand("Robi", "robi", 0xFFE8107C.toInt(), "robi.com.bd"),
        "47003" to OperatorBrand("Banglalink", "banglalink", 0xFFFF8200.toInt(), "banglalink.net"),
        "47004" to OperatorBrand("Teletalk", "teletalk", 0xFF00833E.toInt(), "teletalk.com.bd"),
        "47007" to OperatorBrand("Airtel", "airtel", 0xFFE4002B.toInt(), "bd.airtel.com"),
        // India — Airtel (404 xx) and Jio (405 xx)
        "40410" to OperatorBrand("Airtel India", "airtel_in", 0xFFE4002B.toInt(), "airtel.in"),
        "40445" to OperatorBrand("Airtel India", "airtel_in", 0xFFE4002B.toInt(), "airtel.in"),
        "405840" to OperatorBrand("Jio", "jio", 0xFF0F3CC9.toInt(), "jio.com"),
        "405857" to OperatorBrand("Jio", "jio", 0xFF0F3CC9.toInt(), "jio.com"),
        // USA — Verizon (311 480)
        "311480" to OperatorBrand("Verizon", "verizon", 0xFFCD040B.toInt(), "verizon.com")
    )

    private val nameToBrandMap = mapOf(
        "grameenphone" to Pair("Grameenphone", "grameenphone.com"),
        "gp" to Pair("Grameenphone", "grameenphone.com"),
        "robi" to Pair("Robi", "robi.com.bd"),
        "banglalink" to Pair("Banglalink", "banglalink.net"),
        "teletalk" to Pair("Teletalk", "teletalk.com.bd"),
        "airtel" to Pair("Airtel", "airtel.com"),
        "jio" to Pair("Jio", "jio.com"),
        "verizon" to Pair("Verizon", "verizon.com")
    )

    fun resolveBrand(
        carrierName: String?,
        displayName: String?,
        mcc: String?,
        mnc: String?
    ): OperatorBrand {
        if (mcc != null && mnc != null) {
            val fullKey = "$mcc$mnc"
            mccMncMap[fullKey]?.let { return it }
        }

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

        return OperatorBrand(
            operatorName = carrierName ?: displayName ?: "Unknown",
            normalizedName = "unknown",
            brandColor = 0xFFFBBF24.toInt() 
        )
    }

    private fun getDerivedColor(name: String): Int {
        return when (name.lowercase()) {
            "grameenphone" -> 0xFF00A7E1.toInt()
            "airtel" -> 0xFFE4002B.toInt()
            "robi" -> 0xFFE8107C.toInt()
            "banglalink" -> 0xFFFF8200.toInt()
            "teletalk" -> 0xFF00833E.toInt()
            else -> {
                val hue = (name.hashCode() % 360).toFloat()
                Color.HSVToColor(floatArrayOf(if (hue < 0) hue + 360 else hue, 0.7f, 0.8f))
            }
        }
    }
}
