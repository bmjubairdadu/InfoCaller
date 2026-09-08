package com.infocaller.app.data.remote

import android.content.Context
import okhttp3.Request

/**
 * Persists the Eyecon device auth from the user's own Eyecon app capture
 * (Reqable eyecon.har, 2026-09-07): `join.jsp` answers the e-auth client id
 * and every API call then carries e-auth-v / e-auth / e-auth-c / e-auth-k.
 * Paste the three values from your own capture into Settings → Eyecon Caller
 * ID; without them the provider still tries anonymously.
 */
class EyeconAuthStore(private val context: Context) {
    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasAuth(): Boolean = !cid().isNullOrBlank()
    fun cid(): String? = prefs().getString(KEY_CID, null)?.takeIf { it.isNotBlank() }
    fun c(): String? = prefs().getString(KEY_C, null)?.takeIf { it.isNotBlank() }
    fun k(): String? = prefs().getString(KEY_K, null)?.takeIf { it.isNotBlank() }
    fun cv(): String? = prefs().getString(KEY_CV, null)?.takeIf { it.isNotBlank() }

    fun save(cid: String, c: String, k: String, cv: String? = null) {
        val e = prefs().edit()
            .putString(KEY_CID, cid.trim())
            .putString(KEY_C, c.trim())
            .putString(KEY_K, k.trim())
        if (!cv.isNullOrBlank()) e.putString(KEY_CV, cv.trim())
        e.apply()
    }

    fun clear() { prefs().edit().clear().apply() }

    /** Attaches captured e-auth headers; no-op when nothing is stored. */
    fun attach(b: Request.Builder): Request.Builder {
        val id = cid() ?: return b
        b.header("e-auth-v", "e1")
        b.header("e-auth", id)
        c()?.let { b.header("e-auth-c", it) }
        k()?.let { b.header("e-auth-k", it) }
        return b
    }

    companion object {
        private const val PREFS = "eyecon_auth"
        private const val KEY_CID = "e_auth"
        private const val KEY_C = "e_auth_c"
        private const val KEY_K = "e_auth_k"
        private const val KEY_CV = "e_auth_cv"
    }
}
