package com.infocaller.app.util

/**
 * Offline Bangladesh place-name gazetteer for repairing truncated name
 * tokens ("sujansah" -> "sujansaha", "chittagon" -> "chittagong").
 * Pure Kotlin, no network: prefix + edit-distance match over districts,
 * major upazilas and common village-name suffixes.
 */
object BdPlaceGazetteer {

    private val DISTRICTS = listOf(
        "bagerhat", "bandarban", "barguna", "barishal", "barisal", "bhola",
        "bogura", "bogra", "brahmanbaria", "chandpur", "chattogram", "chittagong",
        "chuadanga", "coxsbazar", "cumilla", "comilla", "dhaka", "dinajpur",
        "faridpur", "feni", "gaibandha", "gazipur", "gopalganj", "habiganj",
        "jamalpur", "jashore", "jessore", "jhalokati", "jhenaidah", "joypurhat",
        "khagrachari", "khulna", "kishoreganj", "kurigram", "kushtia",
        "lakshmipur", "lalmonirhat", "madaripur", "magura", "manikganj",
        "meherpur", "moulvibazar", "munshiganj", "mymensingh", "naogaon",
        "narail", "narayanganj", "narsingdi", "natore", "netrokona", "nilphamari",
        "noakhali", "pabna", "panchagarh", "patuakhali", "pirojpur", "rajbari",
        "rajshahi", "rangamati", "rangpur", "satkhira", "shariatpur", "sherpur",
        "sirajganj", "sunamganj", "sylhet", "tangail", "thakurgaon",
    )

    private val MAJOR_UPAZILAS = listOf(
        "satkhira sadar", "sujansaha", "kalaiganj", "kaliganj", "ashashuni",
        "debhatta", "kalaroa", "tala", "shyamnagar", "savar", "dhamrai",
        "keraniganj", "nawabganj", "mirpur", "pallabi", "uttara", "gulshan",
        "dhanmondi", "chawkbazar", "kotwali", "panchlaish", "halishahar",
        "khulshi", "fatickchari", "mirsharai", "sitakunda", "patiya",
        "boalkhali", "rangunia", "sonargaon", "araihazar", "rupganj",
    )

    private val ALL: List<String> by lazy { (DISTRICTS + MAJOR_UPAZILAS).distinct() }

    /**
     * Repair a possibly-truncated token to the closest gazetteer entry.
     * Returns the input unchanged when nothing is close enough (>= 0.80).
     */
    fun repair(token: String): String {
        val low = token.lowercase().trim()
        if (low.length < 4) return token
        if (low in ALL) return token
        // Fast path: unique prefix completion ("sujansah" -> "sujansaha").
        val prefixHits = ALL.filter { it.startsWith(low) }
        if (prefixHits.size == 1) return prefixHits.first()
        if (prefixHits.isNotEmpty()) {
            // Prefer the shortest completion (least invented suffix).
            return prefixHits.minByOrNull { it.length } ?: token
        }
        // Edit-distance fallback for transpositions ("chittagon").
        var best: String? = null
        var bestScore = 0.80
        for (entry in ALL) {
            if (kotlin.math.abs(entry.length - low.length) > 2) continue
            val s = VillageResolver.similarity(low, entry)
            if (s > bestScore) { bestScore = s; best = entry }
        }
        if (best != null) return best
        // Suffix heuristic: BD village names often end in aha/para/ganj/pur —
        // a token missing its tail ("sujanpur" typed "sujanpu") still resolves
        // via Nominatim, so leave it for the network step.
        return token
    }

    fun isKnownPlace(token: String): Boolean = token.lowercase() in ALL
}
