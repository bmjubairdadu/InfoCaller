package com.infocaller.app.data.local

import android.content.Context
import androidx.core.content.edit

/**
 * First-open consent state for background caller-ID contribution.
 * Stored locally only. Shown once: UNASKED -> ACCEPTED or DECLINED, never re-prompts.
 */
object ContributionConsentStore {
    private const val PREFS = "contribution_consent_prefs"
    private const val KEY_DECISION = "decision"
    private const val KEY_VERSION = "consent_version"
    private const val KEY_DECIDED_AT = "decided_at"

    fun getDecision(context: Context): ContributionPolicy.Decision {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            ContributionPolicy.Decision.valueOf(
                prefs.getString(KEY_DECISION, ContributionPolicy.Decision.UNASKED.name)!!
            )
        } catch (_: Exception) {
            ContributionPolicy.Decision.UNASKED
        }
    }

    fun isAccepted(context: Context): Boolean =
        getDecision(context) == ContributionPolicy.Decision.ACCEPTED

    fun setAccepted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_DECISION, ContributionPolicy.Decision.ACCEPTED.name)
            putInt(KEY_VERSION, ContributionPolicy.CONSENT_VERSION)
            putLong(KEY_DECIDED_AT, System.currentTimeMillis())
        }
        // Opt-in also enables community lookup reads so the user benefits.
        try {
            com.infocaller.app.data.remote.CommunityConsent.setEnabled(context, true)
        } catch (_: Exception) { }
    }

    fun setDeclined(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_DECISION, ContributionPolicy.Decision.DECLINED.name)
            putInt(KEY_VERSION, ContributionPolicy.CONSENT_VERSION)
            putLong(KEY_DECIDED_AT, System.currentTimeMillis())
        }
        try {
            com.infocaller.app.data.remote.CommunityConsent.setEnabled(context, false)
        } catch (_: Exception) { }
    }
}
