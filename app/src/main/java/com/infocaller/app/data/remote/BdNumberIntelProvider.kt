package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BdNumberIntelProvider : LookupProvider {
    override val id = "bd_number_intel"
    override val name = "BD Number Intel"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.PHONE_METADATA, Capability.CARRIER, Capability.COUNTRY,
        Capability.CITY, Capability.LINE_TYPE
    )
    override val priority = 960
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            if (type != IdentifierType.PHONE) return@withContext null
            val digits = PhoneNumberUtils.normalize(identifier).filter { it.isDigit() }
            val national = when {
                digits.startsWith("880") -> digits.substring(3)
                digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
                digits.length == 10 && digits.startsWith("1") -> digits
                else -> return@withContext null
            }
            if (national.length < 4) return@withContext null
            val isMobile = national.length == 10 && national.startsWith("1")
            val carrier = if (isMobile) mobileOperator(national.substring(0, 2)) else "BTCL"
            val lineType = if (isMobile) "MOBILE" else "FIXED_LINE"
            val city = if (isMobile) null else landlineArea(national)
            if (carrier == null && city == null) return@withContext null
            PartialResult(
                carrier = carrier,
                country = "Bangladesh",
                city = city,
                region = city,
                lineType = lineType,
                confidence = if (isMobile) { if (carrier != null) 0.95f else 0.6f } else { if (city != null) 0.85f else 0.6f },
                source = name,
                providerId = id,
                providerVersion = version
            )
        }

    private fun mobileOperator(prefix: String): String? = when (prefix) {
        "13", "17" -> "Grameenphone"
        "14", "19" -> "Banglalink"
        "15" -> "Teletalk"
        "16" -> "Airtel"
        "18" -> "Robi"
        "11" -> "Citycell"
        else -> null
    }

    private fun landlineArea(national: String): String? =
        landlineCodes.firstOrNull { national.startsWith(it.first) }?.second

    private val landlineCodes: List<Pair<String, String>> = listOf(
        "321" to "Noakhali",
        "331" to "Feni",
        "341" to "Cox's Bazar",
        "351" to "Rangamati",
        "361" to "Bandarban",
        "371" to "Khagrachhari",
        "381" to "Lakshmipur",
        "421" to "Jashore",
        "431" to "Barishal",
        "441" to "Patuakhali",
        "451" to "Jhenaidah",
        "461" to "Pirojpur",
        "468" to "Bagerhat",
        "471" to "Satkhira",
        "481" to "Narail",
        "491" to "Bhola",
        "498" to "Jhalakathi",
        "521" to "Rangpur",
        "531" to "Dinajpur",
        "541" to "Gaibandha",
        "551" to "Nilphamari",
        "561" to "Thakurgaon",
        "568" to "Panchagarh",
        "571" to "Joypurhat",
        "581" to "Kurigram",
        "591" to "Lalmonirhat",
        "611" to "Magura",
        "621" to "Narsingdi",
        "631" to "Faridpur",
        "641" to "Rajbari",
        "651" to "Manikganj",
        "661" to "Madaripur",
        "662" to "Shariatpur",
        "671" to "Narayanganj",
        "681" to "Gazipur",
        "682" to "Kaliakair, Gazipur",
        "691" to "Munshiganj",
        "721" to "Rajshahi",
        "731" to "Pabna",
        "741" to "Naogaon",
        "751" to "Sirajganj",
        "761" to "Chuadanga",
        "771" to "Natore",
        "781" to "Chapai Nawabganj",
        "791" to "Meherpur",
        "821" to "Sylhet",
        "831" to "Habiganj",
        "841" to "Chandpur",
        "851" to "Brahmanbaria",
        "861" to "Moulvibazar",
        "871" to "Sunamganj",
        "921" to "Tangail",
        "922" to "Mirzapur, Tangail",
        "931" to "Sherpur",
        "941" to "Kishoreganj",
        "951" to "Netrokona",
        "981" to "Jamalpur",
        "31" to "Chattogram",
        "41" to "Khulna",
        "51" to "Bogura",
        "71" to "Kushtia",
        "81" to "Cumilla",
        "91" to "Mymensingh",
        "2" to "Dhaka"
    )
}
