package com.infocaller.app.util

object LocationUtils {

    
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
