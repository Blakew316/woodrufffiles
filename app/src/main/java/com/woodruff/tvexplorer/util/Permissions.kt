package com.woodruff.tvexplorer.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Storage access. Android 11+ needs "All files access"; many Android TV builds hide that settings
 * screen, so [requestAllFilesIntents] lists fallbacks and [adbHint] is shown as a last resort.
 */
object Permissions {
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Runtime permissions to request on Android 10 and below. */
    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun requestAllFilesIntents(context: Context): List<Intent> =
        if (Build.VERSION.SDK_INT >= 30) listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        ) else emptyList()

    fun adbHint(context: Context) =
        "adb shell appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
}
