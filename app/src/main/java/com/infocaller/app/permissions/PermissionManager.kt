package com.infocaller.app.permissions

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionManager {

    val DIALER_PERMISSIONS = arrayOf(Manifest.permission.CALL_PHONE)

    val CALLER_ID_PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.MANAGE_OWN_CALLS
    )

    val CORE_PERMISSIONS get() = CALLER_ID_PERMISSIONS

    val CONTACTS_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    val CALL_LOG_PERMISSIONS = arrayOf(Manifest.permission.READ_CALL_LOG)
    val WRITE_CALL_LOG_PERMISSION = arrayOf(Manifest.permission.WRITE_CALL_LOG)

    val NOTIFICATION_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray<String>()
    }

    val BLUETOOTH_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray<String>()
    }

    val RECORD_AUDIO_PERMISSION = arrayOf(Manifest.permission.RECORD_AUDIO)

    val SMS_PERMISSION = arrayOf(Manifest.permission.RECEIVE_SMS)

    val SMS_HISTORY_PERMISSIONS = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS
    )

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun isDefaultDialer(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            val telecomManager = context.getSystemService(TelecomManager::class.java)
            context.packageName == telecomManager?.defaultDialerPackage
        }
    }

    fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
        if (permissions.isEmpty()) return true
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun shouldShowRationale(activity: Activity, permissions: Array<String>): Boolean {
        return permissions.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
