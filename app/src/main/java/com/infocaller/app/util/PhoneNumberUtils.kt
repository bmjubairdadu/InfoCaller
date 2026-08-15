package com.infocaller.app.util

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

object PhoneNumberUtils {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    /**
     * Normalizes a phone number to E.164 format (e.g., +8801785917145).
     * Uses Google's libphonenumber for proper international parsing.
     */
    fun normalize(phoneNumber: String, defaultRegion: String = "BD"): String {
        val trimmed = phoneNumber.trim()
        if (trimmed.isEmpty()) return ""
        
        try {
            // Try to parse with provided default region
            val parsed: PhoneNumber = phoneUtil.parse(trimmed, defaultRegion)
            if (phoneUtil.isValidNumber(parsed) || phoneUtil.isPossibleNumber(parsed)) {
                return phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
        } catch (_: Exception) {
            // Fall through to fallback
        }
        
        // Fallback: strip non-digits and return
        return trimmed.filter { it.isDigit() }
    }


    /**
     * Builds the dynamic image URL based on the normalized number.
     */
    fun getImageUrl(phoneNumber: String): String {
        val normalized = normalize(phoneNumber)
        // Remove leading + for URL
        val cleanNumber = normalized.removePrefix("+")
        return "https://whatsapp-db.checkleaked.com/$cleanNumber.jpg"
    }

    fun getCarrierInfo(phoneNumber: String, context: android.content.Context): String? {
        try {
            val parsed = phoneUtil.parse(phoneNumber, "")
            val carrierMapper = com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper.getInstance()
            return carrierMapper.getNameForNumber(parsed, Locale.getDefault())
        } catch (e: Exception) {
            return null
        }
    }

    fun getLocationInfo(phoneNumber: String): String? {
        try {
            val parsed = phoneUtil.parse(phoneNumber, "")
            val geocoder = com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder.getInstance()
            return geocoder.getDescriptionForNumber(parsed, Locale.getDefault())
        } catch (e: Exception) {
            return null
        }
    }

    fun formatAsYouType(number: String, region: String = "BD"): String {
        val formatter = phoneUtil.getAsYouTypeFormatter(region)
        var result = ""
        for (char in number) {
            result = formatter.inputDigit(char)
        }
        return result
    }

    fun getContactName(context: android.content.Context, phoneNumber: String): String? {
        val uri = android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(phoneNumber)
        )
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getContactPhotoUri(context: android.content.Context, phoneNumber: String): String? {
        val uri = android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(phoneNumber)
        )
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.PHOTO_URI)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
