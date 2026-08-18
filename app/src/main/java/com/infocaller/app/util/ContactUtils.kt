package com.infocaller.app.util

import android.accounts.AccountManager
import android.content.Context

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

    fun getContactAccounts(context: Context): List<ContactAccount> {
        val accounts = mutableListOf<ContactAccount>()
        val accountManager = AccountManager.get(context)
        
        accounts.add(ContactAccount("Phone", "Local Device", null, null))
        
        try {
            val amAccounts = accountManager.accounts
            for (account in amAccounts) {
                val type = account.type.lowercase()
                if (type == "com.google" || type.contains("sim") || type.contains("telecom") || type.contains("contact")) {
                    val label = when {
                        type == "com.google" -> "Google"
                        type.contains("sim") -> "SIM Card"
                        type.contains("whatsapp") -> "WhatsApp"
                        else -> account.name
                    }
                    accounts.add(ContactAccount(account.name, label, account.name, account.type))
                }
            }
        } catch (_: Exception) {}
        
        return accounts.distinctBy { it.accountName + it.accountType }
    }
}

data class ContactAccount(
    val name: String,
    val typeLabel: String,
    val accountName: String?,
    val accountType: String?
)
