package com.infocaller.app.data.remote

import android.content.Context
import okhttp3.Request

class EyeconAuthStore(private val context: Context) {
    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasCustomAuth(): Boolean = prefs().getString(KEY_CID, null)?.takeIf { it.isNotBlank() } != null

    fun hasAuth(): Boolean = !cid().isNullOrBlank()

    fun isUsingBuiltIn(): Boolean = !hasCustomAuth()
    fun cid(): String? = prefs().getString(KEY_CID, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CID
    fun c(): String? = prefs().getString(KEY_C, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_C
    fun k(): String? = prefs().getString(KEY_K, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_K
    fun cv(): String? = prefs().getString(KEY_CV, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CV

    fun save(cid: String, c: String, k: String, cv: String? = null) {
        val e = prefs().edit()
            .putString(KEY_CID, cid.trim())
            .putString(KEY_C, c.trim())
            .putString(KEY_K, k.trim())
        if (!cv.isNullOrBlank()) e.putString(KEY_CV, cv.trim())
        e.apply()
    }

    fun clear() { prefs().edit().clear().apply() }

    fun attach(b: Request.Builder): Request.Builder = attachWith(b, cid())

    fun attachWith(b: Request.Builder, clientId: String?): Request.Builder {
        val id = clientId?.takeIf { it.isNotBlank() } ?: return b
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
        const val DEFAULT_CID = "9be0d99b-4f5a-477d-97b2-b8e809781e15"
        const val DEFAULT_C = "37"
        const val DEFAULT_K = "PgdtSBeR0MumR7fO"
        const val DEFAULT_CV = "vc_786_vn_4.2026.09.06.1153_a"
    }
}
