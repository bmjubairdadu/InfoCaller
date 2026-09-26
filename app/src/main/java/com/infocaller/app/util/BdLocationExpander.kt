package com.infocaller.app.util

/**
 * Expands a short user-typed location (e.g. "Dhaka, Uttora") into a full,
 * structured location line with postcode, thana/upazila and city
 * corporation/municipality where known.
 *
 * Offline-first: a curated table for Dhaka metro areas plus major cities.
 * Unknown input is returned cleaned but unchanged (never dropped).
 */
object BdLocationExpander {

    private data class Area(
        val keys: List<String>,
        val display: String,
        val postcode: String?,
        val thana: String?,
        val corp: String?,
        val district: String,
    )

    private val AREAS = listOf(
        Area(listOf("uttara", "uttora", "uttar"), "Uttara", "1230", "Uttara", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("mirpur 10", "mirpur-10", "mirpur10"), "Mirpur 10", "1216", "Mirpur", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("mirpur 1", "mirpur-1", "mirpur1"), "Mirpur 1", "1216", "Mirpur", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("mirpur 2", "mirpur-2", "mirpur2"), "Mirpur 2", "1216", "Mirpur", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("mirpur 11", "mirpur-11"), "Mirpur 11", "1216", "Mirpur", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("mirpur 12", "mirpur-12"), "Mirpur 12", "1216", "Mirpur", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("mirpur"), "Mirpur", "1216", "Mirpur", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("pallabi"), "Pallabi", "1216", "Pallabi", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("kaf rul", "kafrul"), "Kafrul", "1216", "Kafrul", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("gulshan 1", "gulshan-1"), "Gulshan 1", "1212", "Gulshan", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("gulshan 2", "gulshan-2", "gulshan"), "Gulshan", "1212", "Gulshan", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("banani"), "Banani", "1213", "Banani", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("baridhara"), "Baridhara", "1212", "Gulshan", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("bashundhara", "basundhara"), "Bashundhara", "1229", "Bhatara", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("bhatara", "bhatar"), "Bhatara", "1212", "Bhatara", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("badda"), "Badda", "1212", "Badda", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("tejgaon", "tejgao"), "Tejgaon", "1208", "Tejgaon", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("mohakhali", "mohakhalli"), "Mohakhali", "1212", "Banani", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("farmgate", "farm gate"), "Farmgate", "1215", "Tejgaon", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("karwan bazar", "kawran bazar", "karwanbazar"), "Karwan Bazar", "1215", "Tejgaon", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("shahbag", "shahbagh"), "Shahbag", "1000", "Shahbag", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("dhanmondi 27", "dhanmondi-27"), "Dhanmondi 27", "1205", "Dhanmondi", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("dhanmondi"), "Dhanmondi", "1205", "Dhanmondi", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("mohammadpur"), "Mohammadpur", "1207", "Mohammadpur", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("shyamoli", "shamoli"), "Shyamoli", "1207", "Mohammadpur", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("adarsha", "adarsha nagar", "adarshanagar"), "Adabor", "1207", "Adabor", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("lalmatia"), "Lalmatia", "1207", "Mohammadpur", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("elephant road", "elephantroad"), "Elephant Road", "1205", "New Market", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("new market"), "New Market", "1205", "New Market", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("azimpur"), "Azimpur", "1205", "Lalbagh", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("lalbagh", "lalbag"), "Lalbagh", "1211", "Lalbagh", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("chawkbazar", "chawk bazar", "chowkbazar"), "Chawkbazar", "1211", "Chawkbazar", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("old dhaka", "purana paltan", "purana palt", "old town"), "Old Dhaka", "1000", "Kotwali", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("sadarghat"), "Sadarghat", "1100", "Kotwali", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("motijheel"), "Motijheel", "1000", "Motijheel", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("paltan"), "Paltan", "1000", "Paltan", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("ramna"), "Ramna", "1000", "Ramna", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("malibagh", "malibag"), "Malibagh", "1217", "Ramna", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("mouchak", "mouchak market"), "Mouchak", "1217", "Ramna", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("khilgaon", "khilgao"), "Khilgaon", "1219", "Khilgaon", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("bashabo", "bashaboo"), "Bashabo", "1214", "Sabujbagh", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("sabujbagh", "sabujbag"), "Sabujbagh", "1214", "Sabujbagh", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("mugda", "mugdha"), "Mugda", "1214", "Mugda", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("jatrabari", "jatra bari"), "Jatrabari", "1204", "Jatrabari", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("saydabad", "saidabad"), "Saydabad", "1203", "Jatrabari", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("demra"), "Demra", "1360", "Demra", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("kadamtali", "kadomtoli"), "Kadamtali", "1362", "Kadamtali", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("shyampur"), "Shyampur", "1204", "Shyampur", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("dania"), "Dania", "1236", "Shyampur", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("shantinagar", "shanti nagar"), "Shantinagar", "1217", "Paltan", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("kakrail"), "Kakrail", "1000", "Ramna", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("seg unbagicha", "segunbagicha"), "Segunbagicha", "1000", "Ramna", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("banasree", "banasri"), "Banasree", "1219", "Khilgaon", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("rampura"), "Rampura", "1219", "Rampura", "Dhaka South City Corporation", "Dhaka"),
        Area(listOf("khilkhet"), "Khilkhet", "1229", "Khilkhet", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("nikunja", "nikunjo"), "Nikunja", "1229", "Khilkhet", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("airport", "hazrat shahjalal"), "Airport", "1229", "Uttara", "Dhaka North City Corporation", "Dhaka"),
        Area(listOf("tongi"), "Tongi", "1710", "Tongi", "Gazipur City Corporation", "Gazipur"),
        Area(listOf("gazipur"), "Gazipur", "1700", "Gazipur Sadar", "Gazipur City Corporation", "Gazipur"),
        Area(listOf("savar"), "Savar", "1340", "Savar", "Savar Municipality", "Dhaka"),
        Area(listOf("ashulia"), "Ashulia", "1341", "Savar", "Savar Municipality", "Dhaka"),
        Area(listOf("keraniganj"), "Keraniganj", "1310", "Keraniganj", "Keraniganj Upazila", "Dhaka"),
        Area(listOf("narayanganj", "narayanganj"), "Narayanganj", "1400", "Narayanganj Sadar", "Narayanganj City Corporation", "Narayanganj"),
        Area(listOf("munshiganj", "munshigonj"), "Munshiganj", "1500", "Munshiganj Sadar", "Munshiganj Municipality", "Munshiganj"),
        Area(listOf("chattogram", "chittagong", "ctg"), "Chattogram", "4000", "Kotwali", "Chattogram City Corporation", "Chattogram"),
        Area(listOf("agrabad"), "Agrabad", "4100", "Double Mooring", "Chattogram City Corporation", "Chattogram"),
        Area(listOf("sylhet"), "Sylhet", "3100", "Kotwali", "Sylhet City Corporation", "Sylhet"),
        Area(listOf("khulna"), "Khulna", "9100", "Kotwali", "Khulna City Corporation", "Khulna"),
        Area(listOf("rajshahi"), "Rajshahi", "6000", "Boalia", "Rajshahi City Corporation", "Rajshahi"),
        Area(listOf("barishal", "barisal"), "Barishal", "8200", "Kotwali", "Barishal City Corporation", "Barishal"),
        Area(listOf("rangpur"), "Rangpur", "5400", "Kotwali", "Rangpur City Corporation", "Rangpur"),
        Area(listOf("mymensingh", "mymensing"), "Mymensingh", "2200", "Kotwali", "Mymensingh City Corporation", "Mymensingh"),
        Area(listOf("cumilla", "comilla"), "Cumilla", "3500", "Kotwali", "Cumilla City Corporation", "Cumilla"),
        Area(listOf("cox's bazar", "coxs bazar", "cox bazar"), "Cox's Bazar", "4700", "Cox's Bazar Sadar", "Cox's Bazar Municipality", "Cox's Bazar"),
    )

    private val CITY_FALLBACK = mapOf(
        "dhaka" to Triple("Dhaka", "1000", "Dhaka North/South City Corporation"),
        "chattogram" to Triple("Chattogram", "4000", "Chattogram City Corporation"),
        "chittagong" to Triple("Chattogram", "4000", "Chattogram City Corporation"),
        "sylhet" to Triple("Sylhet", "3100", "Sylhet City Corporation"),
        "khulna" to Triple("Khulna", "9100", "Khulna City Corporation"),
        "rajshahi" to Triple("Rajshahi", "6000", "Rajshahi City Corporation"),
        "barishal" to Triple("Barishal", "8200", "Barishal City Corporation"),
        "barisal" to Triple("Barishal", "8200", "Barishal City Corporation"),
        "rangpur" to Triple("Rangpur", "5400", "Rangpur City Corporation"),
        "mymensingh" to Triple("Mymensingh", "2200", "Mymensingh City Corporation"),
        "cumilla" to Triple("Cumilla", "3500", "Cumilla City Corporation"),
        "comilla" to Triple("Cumilla", "3500", "Cumilla City Corporation"),
    )

    fun expand(raw: String?): String? {
        val input = raw?.trim()?.replace(Regex("\\s+"), " ")?.take(120) ?: return null
        if (input.isBlank()) return null
        // Already looks expanded (has postcode + corp) — keep as-is.
        if (input.length > 40 && input.contains(Regex("\\d{4}")) &&
            (input.contains("Corporation", true) || input.contains("Municipality", true) || input.contains("Upazila", true))
        ) return input
        val lower = input.lowercase()
        // Longest-key match wins so "mirpur 10" beats "mirpur".
        val hit = AREAS.filter { a -> a.keys.any { k -> lower.contains(k) } }
            .maxByOrNull { a -> a.keys.filter { k -> lower.contains(k) }.maxOf { it.length } }
        if (hit != null) {
            val parts = mutableListOf(hit.display)
            val pc = hit.postcode
            if (!pc.isNullOrBlank()) parts.add("${hit.district}-$pc")
            hit.thana?.takeIf { it.isNotBlank() && !hit.display.equals(it, true) }?.let { parts.add("$it Thana") }
            hit.corp?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (parts.none { it.equals(hit.district, true) }) parts.add(hit.district)
            if (parts.none { it.contains("Bangladesh", true) }) parts.add("Bangladesh")
            return parts.joinToString(", ").take(160)
        }
        // City-only fallback.
        for ((key, info) in CITY_FALLBACK) {
            if (lower.contains(key)) {
                return "${info.first}-${info.second}, ${info.third}, ${info.first}, Bangladesh".take(160)
            }
        }
        // Unknown: return cleaned input so nothing is lost.
        return input
    }
}
