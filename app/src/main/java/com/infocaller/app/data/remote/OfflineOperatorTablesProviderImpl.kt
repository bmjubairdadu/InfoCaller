package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline operator/country enrichment from the bundled prefix tables.
 *
 * Prefix-table approach ported from xsukax/xsukax-Phone-Validator
 * (country calling codes + operator prefix patterns), re-expressed here in
 * Kotlin over the app's existing libphonenumber data. Fully offline, FREE.
 */
class OfflineOperatorTablesProviderImpl : LookupProvider {
    override val id = "offline_operator_tables"
    override val name = "Offline Operator Tables"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.PHONE_METADATA, Capability.CARRIER, Capability.COUNTRY,
        Capability.CITY, Capability.LINE_TYPE
    )
    override val priority = 90
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            if (type != IdentifierType.PHONE) return@withContext null
            val normalized = PhoneNumberUtils.normalize(identifier)
            val digits = normalized.filter { it.isDigit() }
            if (digits.length < 7) return@withContext null
            val region = PhoneNumberUtils.getCountryCode(normalized)
            val country = countryName(region)
            val operator = operatorFor(region, digits)
            val lineType = lineTypeFor(region, digits)
            if (operator == null && country == null && lineType == null) return@withContext null
            PartialResult(
                carrier = operator,
                country = country,
                lineType = lineType,
                confidence = if (operator != null) 0.85f else 0.6f,
                source = "Offline Operator Tables",
                providerId = id, providerVersion = version
            )
        }

    private fun countryName(region: String?): String? = when (region) {
        "BD" -> "Bangladesh"; "IN" -> "India"; "PK" -> "Pakistan"
        "US" -> "United States"; "GB" -> "United Kingdom"
        "SA" -> "Saudi Arabia"; "AE" -> "United Arab Emirates"
        "MY" -> "Malaysia"; "SG" -> "Singapore"
        "NP" -> "Nepal"; "LK" -> "Sri Lanka"; "MM" -> "Myanmar"
        else -> null
    }

    /** National-significant-number prefix -> operator (xsukax-style prefix tables). */
    private fun operatorFor(region: String?, digits: String): String? {
        val national = when {
            digits.startsWith("880") -> digits.removePrefix("880")
            digits.startsWith("91") -> digits.removePrefix("91")
            digits.startsWith("92") -> digits.removePrefix("92")
            else -> digits
        }
        return when (region) {
            "BD" -> when {
                national.startsWith("17") || national.startsWith("13") -> "Grameenphone"
                national.startsWith("18") || national.startsWith("16") -> "Robi"
                national.startsWith("19") || national.startsWith("14") -> "Banglalink"
                national.startsWith("15") -> "Teletalk"
                national.startsWith("2") -> "BTCL"
                else -> null
            }
            "IN" -> when {
                national.startsWith("98") || national.startsWith("99") -> "Airtel India"
                national.startsWith("94") -> "Vodafone Idea"
                national.startsWith("70") || national.startsWith("72") -> "Jio"
                else -> null
            }
            else -> null
        }
    }

    private fun lineTypeFor(region: String?, digits: String): String? {
        val national = when {
            digits.startsWith("880") -> digits.removePrefix("880")
            digits.startsWith("91") -> digits.removePrefix("91")
            digits.startsWith("92") -> digits.removePrefix("92")
            else -> digits
        }
        return when (region) {
            "BD" -> when {
                national.startsWith("2") -> "FIXED_LINE"
                national.length == 10 && national[0] == '1' -> "MOBILE"
                else -> null
            }
            else -> null
        }
    }
}
