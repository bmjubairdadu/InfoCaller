package com.infocaller.app.data.remote

import android.content.Context


object TruecallerCloudStore {

    private const val PREFS = "app_prefs"
    private const val KEY_INSTALLATION_ID = "truecaller_token"

    fun saveInstallationId(context: Context, installationId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_INSTALLATION_ID, installationId).apply()
    }

    fun getInstallationId(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_INSTALLATION_ID, null)?.takeIf { it.isNotBlank() }
    }

    fun hasValidSession(context: Context): Boolean = !getInstallationId(context).isNullOrBlank()
}
