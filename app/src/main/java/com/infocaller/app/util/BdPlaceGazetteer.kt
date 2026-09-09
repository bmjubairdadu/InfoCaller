package com.infocaller.app.util

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

    fun repair(token: String): String {
        val low = token.lowercase().trim()
        if (low.length < 4) return token
        if (low in ALL) return token
        val prefixHits = ALL.filter { it.startsWith(low) }
        if (prefixHits.size == 1) return prefixHits.first()
        if (prefixHits.isNotEmpty()) {
            return prefixHits.minByOrNull { it.length } ?: token
        }
        var best: String? = null
        var bestScore = 0.80
        for (entry in ALL) {
            if (kotlin.math.abs(entry.length - low.length) > 2) continue
            val s = VillageResolver.similarity(low, entry)
            if (s > bestScore) { bestScore = s; best = entry }
        }
        if (best != null) return best
        return token
    }

    fun isKnownPlace(token: String): Boolean = token.lowercase() in ALL
}
