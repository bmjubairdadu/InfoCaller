package com.infocaller.app.util

object LocationUtils {
    data class LocatedSource(val label: String, val value: String)

    fun allLocatedSources(
        city: String?, region: String?, country: String?,
        nidAddress: String? = null,
        emailLocation: String? = null,
        simRegion: String? = null,
        displayFallback: String? = null,
    ): List<LocatedSource> {
        val out = mutableListOf<LocatedSource>()
        fun add(label: String, v: String?) {
            val t = v?.trim().orEmpty()
            if (t.length < 2) return
            if (out.any { it.value.equals(t, ignoreCase = true) }) return
            out.add(LocatedSource(label, t))
        }
        add("Caller location", formatCallerLocation(city, region, country))
        add("NID address", nidAddress)
        add("Profile location", emailLocation)
        add("SIM region", simRegion)
        add("Network", displayFallback)
        return out
    }

    fun formatCallerLocation(city: String?, region: String?, country: String?): String {
        val parts = mutableListOf<String>()

        val cleanCity = city?.trim()
        val cleanRegion = region?.trim()
        val cleanCountry = country?.trim()

        if (!cleanCity.isNullOrBlank()) {
            parts.add(cleanCity)
        }

        if (!cleanRegion.isNullOrBlank() && cleanRegion != cleanCity) {
            parts.add(cleanRegion)
        }

        if (!cleanCountry.isNullOrBlank() && cleanCountry != cleanCity && cleanCountry != cleanRegion) {
            parts.add(cleanCountry)
        }

        return parts.joinToString(", ")
    }
}
