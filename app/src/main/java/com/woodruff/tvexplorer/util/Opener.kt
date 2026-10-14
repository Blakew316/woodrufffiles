package com.woodruff.tvexplorer.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.woodruff.tvexplorer.data.FileItem
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.FileTypes
import com.woodruff.tvexplorer.player.ImageViewerActivity
import com.woodruff.tvexplorer.player.PlayerActivity
import java.io.File

/** Outcome of trying to open something, so the UI can explain failures instead of silently doing nothing. */
sealed interface OpenResult {
    data object Opened : OpenResult
    data class Failed(val message: String) : OpenResult
    /** APK installs need "Install unknown apps" granted to this app first. */
    data object NeedsInstallPermission : OpenResult
}

object Opener {
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /**
     * Default open action. Media goes to the built-in player/viewer because many TVs ship with no
     * gallery or video app at all; everything else goes to whatever app claims the MIME type.
     * [siblings] lets the viewers step through the rest of the folder with left/right.
     */
    fun open(context: Context, item: FileItem, siblings: List<FileItem>): OpenResult = when (item.kind) {
        FileKind.VIDEO, FileKind.AUDIO -> {
            val playlist = window(siblings.filter { it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO }, item)
            context.startActivity(PlayerActivity.intent(context, playlist, playlist.map { java.io.File(it).name }, item.path))
            OpenResult.Opened
        }
        FileKind.IMAGE -> {
            val images = window(siblings.filter { it.kind == FileKind.IMAGE }, item)
            context.startActivity(ImageViewerActivity.intent(context, images, item.path))
            OpenResult.Opened
        }
        FileKind.APK -> installApk(context, item.file)
        else -> openExternal(context, item.file, chooser = false)
    }

    /**
     * Up to [MAX_QUEUE] paths centred on [current]. Intent extras travel through a ~1 MB binder
     * buffer, and a library category can hold thousands of files.
     */
    private fun window(items: List<FileItem>, current: FileItem): List<String> {
        val i = items.indexOfFirst { it.path == current.path }.coerceAtLeast(0)
        val from = (i - MAX_QUEUE / 2).coerceIn(0, (items.size - MAX_QUEUE).coerceAtLeast(0))
        return items.subList(from, minOf(items.size, from + MAX_QUEUE)).map { it.path }
    }

    private const val MAX_QUEUE = 300

    /** "Open with…": always shows the system chooser, even for media. */
    fun openExternal(context: Context, file: File, chooser: Boolean = true): OpenResult {
        val mime = FileTypes.mimeOf(file.name)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, if (chooser) Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else view,
            "No installed app can open .${FileTypes.extension(file.name).ifEmpty { "?" }} files")
    }

    fun installApk(context: Context, file: File): OpenResult {
        if (!context.packageManager.canRequestPackageInstalls()) return OpenResult.NeedsInstallPermission
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent, "This device has no package installer")
    }

    /** Opens the per-app "Install unknown apps" toggle, falling back to the global screen. */
    fun openInstallPermissionSettings(context: Context): OpenResult {
        val pkg = Uri.parse("package:${context.packageName}")
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        return firstThatStarts(context, attempts,
            "Open Settings > Apps > Security & restrictions > Unknown sources and allow Woodruff Family")
    }

    fun appDetails(context: Context, packageName: String): OpenResult =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")), "App settings unavailable")

    @Suppress("DEPRECATION")
    fun uninstall(context: Context, packageName: String): OpenResult =
        start(context, Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName")), "Uninstall isn't available on this device")

    fun firstThatStarts(context: Context, intents: List<Intent>, failure: String): OpenResult {
        for (i in intents) {
            if (start(context, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), failure) == OpenResult.Opened) return OpenResult.Opened
        }
        return OpenResult.Failed(failure)
    }

    private fun start(context: Context, intent: Intent, failure: String): OpenResult = try {
        context.startActivity(intent)
        OpenResult.Opened
    } catch (e: ActivityNotFoundException) {
        OpenResult.Failed(failure)
    } catch (e: SecurityException) {
        OpenResult.Failed(failure)
    }
}
