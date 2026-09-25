package com.infocaller.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import java.security.MessageDigest

object IntegrityGuard {

    private const val PREFS = "integrity_prefs"
    private const val KEY_GRACE = "integrity_grace_until"

    private fun expectedSignature(): String = try {
        com.infocaller.app.BuildConfig.APP_SIGNATURE_SHA256.trim().lowercase()
    } catch (_: Exception) { "" } catch (_: Error) { "" }

    private fun actualSignatures(context: Context): List<String> = try {
        val pm = context.packageManager
        val pkg = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val raw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val signing = pkg.signingInfo ?: return emptyList()
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkg.signatures
        }
        (raw ?: emptyArray()).mapNotNull { sig ->
            try {
                val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                digest.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }

    fun isRepackaged(context: Context): Boolean {
        val expected = expectedSignature()
        if (expected.isBlank()) return false
        val actual = actualSignatures(context)
        if (actual.isEmpty()) return true
        return actual.none { it.equals(expected, ignoreCase = true) }
    }

    fun verify(context: Context): Boolean {
        return try {
            !isRepackaged(context)
        } catch (_: Exception) { true } catch (_: Error) { true }
    }

    fun start(context: Context) {
        try {
            if (isRepackaged(context)) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_GRACE, 0L).apply()
            }
        } catch (_: Exception) { } catch (_: Error) { }
    }

    fun isHookedEnvironment(): Boolean = try {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xposed/module",
            "/data/local/tmp/riru"
        )
        paths.any { java.io.File(it).exists() }
    } catch (_: Exception) { false } catch (_: Error) { false }

    fun sha256Of(text: String): String = try {
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()),
            Base64.NO_WRAP
        )
    } catch (_: Exception) { "" } catch (_: Error) { "" }
}
