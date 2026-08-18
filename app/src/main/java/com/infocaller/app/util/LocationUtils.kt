package com.infocaller.app.util

object LocationUtils {

    /**
     * Formats city, region, and country into a clean string like "City, Country".
     * Rules:
     * 1. Remove duplicates (e.g., "Bangladesh, Bangladesh" -> "Bangladesh").
     * 2. Remove null/empty/blank values.
     * 3. Trim whitespace.
     * 4. If City == Country, only show Country.
     */
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
