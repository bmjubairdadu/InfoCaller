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

    // Short-form / misspelt village tokens seen in caller-ID names.
    // Key = typed form (lowercase), value = correct Maps form.
    private val CORRECTIONS = mapOf(
        "sujonsha" to "sujonsaha",
        "sujonshaha" to "sujonsaha",
        "sujonsa" to "sujonsaha",
        "sujonsaha" to "sujonsaha",
        "sujansha" to "sujansaha",
        "sujanshaha" to "sujansaha",
        "sujansa" to "sujansaha",
        "sujansaha" to "sujansaha",
        "satkira" to "satkhira",
        "satkihra" to "satkhira",
        "satkhira" to "satkhira",
        "kalaiganj" to "kaliganj",
        "bajar" to "bazar",
        "bazaar" to "bazar",
        "chekpost" to "checkpost",
        "chekpose" to "checkpost",
        "checkpost" to "checkpost",
        "checkpostt" to "checkpost",
        "chowrasta" to "chowrasta",
        "chourasta" to "chowrasta",
        "morr" to "mor",
    )

    // Landmark-type tokens: generic, never resolve alone — must combine with brand/place.
    val LANDMARKS = setOf(
        "checkpost", "bazar", "bazaar", "hat", "ghat", "mor", "more",
        "chowrasta", "chourasta", "stand", "station", "market", "super",
        "school", "college", "madrasa", "masjid", "mosque", "mandir",
        "hospital", "clinic", "showroom", "store", "shop", "plaza",
        "tower", "road", "lane", "para", "gram", "pur", "ganj",
        "chowk", "gate", "bridge", "khola", "tala",
    )

    // Business-brand anchors: keep as map-search anchor, never drop as person name.
    val BRANDS = setOf(
        "walton", "singer", "vision", "marcel", "lg", "samsung",
        "vivo", "oppo", "realme", "xiaomi", "mi", "huawei", "nokia",
        "symphony", "itel", "tecno", "infinix", "yamaha", "honda",
        "suzuki", "tvs", "bajaj", "hero", "apex", "bata", "lotto",
        "aarong", "pran", "rfl", "akij", "fresh", "brac", "grameen",
    )

    private val MAJOR_UPAZILAS = listOf(
        "satkhira sadar", "sujansaha", "sujonsaha", "kalaiganj", "kaliganj", "ashashuni",
        "debhatta", "kalaroa", "tala", "shyamnagar", "savar", "dhamrai",
        "keraniganj", "nawabganj", "mirpur", "pallabi", "uttara", "gulshan",
        "dhanmondi", "chawkbazar", "kotwali", "panchlaish", "halishahar",
        "khulshi", "fatickchari", "mirsharai", "sitakunda", "patiya",
        "boalkhali", "rangunia", "sonargaon", "araihazar", "rupganj",
    )

    private val ALL: List<String> by lazy { (DISTRICTS + MAJOR_UPAZILAS).distinct() }

    fun isBrand(token: String): Boolean = token.lowercase().trim() in BRANDS

    fun isLandmark(token: String): Boolean {
        val low = token.lowercase().trim()
        val norm = CORRECTIONS[low] ?: low
        return norm in LANDMARKS || low in LANDMARKS
    }

    fun normalize(token: String): String = repair(token)

    fun repair(token: String): String {
        val low = token.lowercase().trim()
        if (low.length < 3) return token
        CORRECTIONS[low]?.let { return it }
        if (low in ALL) return token
        // Generic rule: "...sha" typed as short form of "...saha" (sujonsha -> sujonsaha).
        if (low.endsWith("sha") && low.length >= 6) {
            val sahaForm = low.dropLast(3) + "saha"
            if (sahaForm in ALL) return sahaForm
            CORRECTIONS[sahaForm]?.let { if (it in ALL) return it }
            // Try inserting single 'a': sujonsha -> sujonsaha needs s+a handling.
            for (entry in ALL) {
                if (entry == sahaForm) return entry
            }
        }
        if (low.endsWith("sa") && low.length >= 6) {
            val sahaForm = low.dropLast(2) + "saha"
            if (sahaForm in ALL) return sahaForm
        }
        val prefixHits = ALL.filter { it.startsWith(low) }
        if (prefixHits.size == 1) return prefixHits.first()
        if (prefixHits.isNotEmpty()) {
            return prefixHits.minByOrNull { it.length } ?: token
        }
        var best: String? = null
        var bestScore = 0.68
        for (entry in ALL) {
            if (kotlin.math.abs(entry.length - low.length) > 3) continue
            val s = VillageResolver.similarity(low, entry)
            if (s > bestScore) { bestScore = s; best = entry }
        }
        if (best != null) return best
        return token
    }

    fun isKnownPlace(token: String): Boolean = token.lowercase() in ALL
}
