package com.infocaller.app.util

import android.accounts.AccountManager
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.content.Intent

object ContactUtils {

    private val PLACEHOLDER_NAMES = setOf(
        "public record",
        "unknown",
        "unknown caller",
        "unnamed contact",
        "search result",
        "identified via infocaller",
        "discovery"
    )

    fun isPlaceholderName(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val normalized = name.trim().lowercase()
        if (PLACEHOLDER_NAMES.contains(normalized)) return true
        val keywords = listOf("infocaller", "unknown", "public record")
        if (keywords.any { normalized.contains(it) }) return true
        if (normalized.filter { it.isDigit() }.length >= 7 && normalized.filter { it.isLetter() }.isEmpty()) return true
        return false
    }

    fun getInitials(name: String?): String {
        if (name.isNullOrBlank() || isPlaceholderName(name)) return "?"
        val parts = name.trim().split("\\s+".toRegex())
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts[0].take(1) + parts.last().take(1)).uppercase()
        }
    }

    fun editContact(context: Context, phoneNumber: String) {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalized))
        val projection = arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY)
        
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val contactId = cursor.getLong(0)
                val lookupKey = cursor.getString(1)
                val contactUri = ContactsContract.Contacts.getLookupUri(contactId, lookupKey)
                val intent = Intent(Intent.ACTION_EDIT).apply {
                    data = contactUri
                }
                context.startActivity(intent)
            }
        }
    }

    fun getLastIncomingCallNumber(context: Context): String? {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(android.provider.CallLog.Calls.NUMBER),
            "${android.provider.CallLog.Calls.TYPE} = ? OR ${android.provider.CallLog.Calls.TYPE} = ?",
            arrayOf(android.provider.CallLog.Calls.INCOMING_TYPE.toString(), android.provider.CallLog.Calls.MISSED_TYPE.toString()),
            "${android.provider.CallLog.Calls.DATE} DESC"
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun getContactAccounts(context: Context): List<ContactAccount> {
        val accounts = mutableListOf<ContactAccount>()
        val accountManager = AccountManager.get(context)
        
        accounts.add(ContactAccount("Phone", "Local Device", null, null))
        
        try {
            val amAccounts = accountManager.accounts
            for (account in amAccounts) {
                val type = account.type.lowercase()
                val name = account.name
                
                val label = when {
                    type == "com.google" -> "Google ($name)"
                    type.contains("sim") -> "SIM Card"
                    type.contains("telecom") -> "Operator"
                    type.contains("whatsapp") -> "WhatsApp"
                    else -> name
                }
                
                if (type == "com.google" || type.contains("sim") || type.contains("telecom") || type.contains("android.contacts")) {
                    accounts.add(ContactAccount(name, label, name, account.type))
                }
            }
            
            if (accounts.none { it.typeLabel.contains("SIM") }) {
                val cursor = context.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE),
                    null, null, null
                )
                cursor?.use {
                    val nameIdx = it.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
                    val typeIdx = it.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    while (it.moveToNext()) {
                        val name = it.getString(nameIdx)
                        val type = it.getString(typeIdx)
                        if (type?.lowercase()?.contains("sim") == true) {
                            accounts.add(ContactAccount(name ?: "SIM", "SIM Card", name, type))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        
        return accounts.distinctBy { (it.accountName ?: "") + (it.accountType ?: "") }
    }
}

data class ContactAccount(
    val name: String,
    val typeLabel: String,
    val accountName: String?,
    val accountType: String?
)
