package com.woodruff.tvexplorer.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val apkPath: String,
    val apkSize: Long,
    val isSystem: Boolean,
    val launchable: Boolean,
)

/** Installed-app listing plus APK backup, for the Apps screen. */
class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    suspend fun apps(includeSystem: Boolean): List<AppEntry> = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else pm.getInstalledPackages(0)
        packages.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val system = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (system && !includeSystem) return@mapNotNull null
            if (pi.packageName == context.packageName) return@mapNotNull null
            AppEntry(
                packageName = pi.packageName,
                label = ai.loadLabel(pm).toString(),
                versionName = pi.versionName ?: "",
                apkPath = ai.sourceDir,
                apkSize = File(ai.sourceDir).length(),
                isSystem = system,
                launchable = launchIntentAvailable(pi.packageName),
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun icon(packageName: String): Drawable? = try {
        pm.getApplicationBanner(packageName) ?: pm.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun launchIntent(packageName: String) =
        pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)

    private fun launchIntentAvailable(packageName: String) = launchIntent(packageName) != null

    /**
     * Copies the base APK to Download/Woodruff Pictures/Apps. Split APKs (app bundles) are only partially
     * captured, which is why the result names the file so the user can check it.
     */
    suspend fun backup(app: AppEntry): File = withContext(Dispatchers.IO) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Woodruff Pictures/Apps",
        ).apply { mkdirs() }
        val safeLabel = app.label.replace(Regex("[^A-Za-z0-9 ._-]"), "_")
        val target = FileOperations.uniqueTarget(dir, "$safeLabel ${app.versionName}.apk".trim())
        File(app.apkPath).copyTo(target)
        target
    }
}
