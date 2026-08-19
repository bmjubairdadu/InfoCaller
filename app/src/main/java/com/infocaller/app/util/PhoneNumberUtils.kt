package com.infocaller.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import java.util.Locale

object PhoneNumberUtils {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    fun normalize(phoneNumber: String, defaultRegion: String = "BD"): String {
        val trimmed = phoneNumber.trim()
        if (trimmed.isEmpty()) return ""
        try {
            val parsed: PhoneNumber = phoneUtil.parse(trimmed, defaultRegion)
            if (phoneUtil.isValidNumber(parsed) || phoneUtil.isPossibleNumber(parsed)) {
                return phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
        } catch (_: Exception) {}
        return trimmed.filter { it.isDigit() || it == '+' }
    }

    fun getCountryCode(phoneNumber: String): String? {
        return try {
            val parsed = phoneUtil.parse(phoneNumber, "")
            phoneUtil.getRegionCodeForNumber(parsed)
        } catch (e: Exception) {
            null
        }
    }

    fun getImageUrl(@Suppress("UNUSED_PARAMETER") phoneNumber: String): String? {
        // Removed unsafe checkleaked.com source as per instruction 33
        return null
    }

    fun getCarrierInfo(phoneNumber: String, context: Context? = null): String? {
        return try {
            val parsed = phoneUtil.parse(phoneNumber, "")
            val carrierMapper = com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper.getInstance()
            carrierMapper.getNameForNumber(parsed, Locale.getDefault())
        } catch (e: Exception) {
            null
        }
    }

    fun getLocationInfo(phoneNumber: String): String? {
        return try {
            val parsed = phoneUtil.parse(phoneNumber, "")
            val geocoder = com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder.getInstance()
            geocoder.getDescriptionForNumber(parsed, Locale.getDefault())
        } catch (e: Exception) {
            null
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

    fun sendSms(context: Context, phoneNumber: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$phoneNumber")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "No SMS app found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun getContactName(context: Context, phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    fun getContactPhotoUri(context: Context, phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.PHOTO_URI)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }
}
