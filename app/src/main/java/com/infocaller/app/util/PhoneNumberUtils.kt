package com.infocaller.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import java.util.Locale

object PhoneNumberUtils {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    fun isUssdCode(number: String): Boolean {
        return number.startsWith("*") && number.endsWith("#")
    }

    fun normalize(phoneNumber: String, defaultRegion: String = "BD"): String {
        val trimmed = phoneNumber.trim()
        if (trimmed.isEmpty()) return ""
        
        if (trimmed.startsWith("*") || (trimmed.startsWith("#") && trimmed.endsWith("#"))) {
            return trimmed
        }
        
        val filtered = trimmed.filter { it.isDigit() || it == '+' }
        
        try {
            if (filtered.startsWith("+")) {
                val parsed: PhoneNumber = phoneUtil.parse(filtered, null)
                if (phoneUtil.isValidNumber(parsed) || phoneUtil.isPossibleNumber(parsed)) {
                    return phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
                }
            }
            
            val parsed: PhoneNumber = phoneUtil.parse(filtered, "BD")
            if (phoneUtil.isValidNumber(parsed)) {
                return phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
            
            if (!filtered.startsWith("+")) {
                if (filtered.startsWith("880")) {
                    val p = phoneUtil.parse("+$filtered", null)
                    if (phoneUtil.isValidNumber(p)) {
                        return phoneUtil.format(p, PhoneNumberUtil.PhoneNumberFormat.E164)
                    }
                }
            }

            if (phoneUtil.isPossibleNumber(parsed)) {
                return phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
        } catch (e: Exception) {
            Log.w("PhoneNumberUtils", "Normalization failed for $trimmed")
        }
        
        val digitsOnly = filtered.filter { it.isDigit() }
        return if (digitsOnly.startsWith("880") && digitsOnly.length == 13) {
            "+$digitsOnly"
        } else if (digitsOnly.startsWith("0") && digitsOnly.length == 11) {
            "+88$digitsOnly"
        } else if (filtered.startsWith("+")) {
            filtered
        } else {
            digitsOnly
        }
    }

    fun getSearchFormats(phoneNumber: String): List<String> {
        val normalized = normalize(phoneNumber)
        val clean = normalized.replace("+", "")
        
        return listOf(
            normalized,
            clean,
            formatAsYouType(normalized),
            formatAsYouType(normalized).replace(" ", "-")
        ).distinct()
    }

    fun getCountryCode(phoneNumber: String): String? {
        return try {
            val normalized = normalize(phoneNumber)
            val parsed = phoneUtil.parse(normalized, "")
            phoneUtil.getRegionCodeForNumber(parsed)
        } catch (e: Exception) {
            null
        }
    }

    fun getSignificantNumber(phoneNumber: String): String? {
        return try {
            val normalized = normalize(phoneNumber)
            val parsed = phoneUtil.parse(normalized, "")
            parsed.nationalNumber.toString()
        } catch (e: Exception) {
            null
        }
    }

    fun getDialingCode(phoneNumber: String): Int? {
        return try {
            val normalized = normalize(phoneNumber)
            val parsed = phoneUtil.parse(normalized, "")
            parsed.countryCode
        } catch (e: Exception) {
            null
        }
    }

    fun getCarrierInfo(phoneNumber: String, context: Context? = null): String? {
        return try {
            val normalized = normalize(phoneNumber)
            val parsed = phoneUtil.parse(normalized, "")
            val carrierMapper = com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper.getInstance()
            carrierMapper.getNameForNumber(parsed, Locale.getDefault())
        } catch (e: Exception) {
            null
        }
    }

    fun getLocationInfo(phoneNumber: String): String? {
        return try {
            val normalized = normalize(phoneNumber)
            val parsed = phoneUtil.parse(normalized, "")
            val geocoder = com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder.getInstance()
            geocoder.getDescriptionForNumber(parsed, Locale.getDefault())
        } catch (e: Exception) {
            null
        }
    }

    fun formatAsYouType(number: String, region: String = "BD"): String {
        if (number.startsWith("*") || number.startsWith("#")) return number
        
        val formatter = phoneUtil.getAsYouTypeFormatter(region)
        var result = ""
        for (char in number) {
            result = formatter.inputDigit(char)
        }
        return result
    }

    fun getLineType(phoneNumber: String): String {
        return try {
            val normalized = normalize(phoneNumber)
            val parsed = phoneUtil.parse(normalized, "")
            phoneUtil.getNumberType(parsed).name
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    fun sendSms(context: Context, phoneNumber: String) {
        val normalized = normalize(phoneNumber)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$normalized")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "No SMS app found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun getContactName(context: Context, phoneNumber: String): String? {
        val normalized = normalize(phoneNumber)
        val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalized))
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    fun getContactPhotoUri(context: Context, phoneNumber: String): String? {
        val normalized = normalize(phoneNumber)
        val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalized))
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.PHOTO_URI)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }
}
