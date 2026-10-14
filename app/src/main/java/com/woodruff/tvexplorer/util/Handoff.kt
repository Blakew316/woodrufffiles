package com.woodruff.tvexplorer.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Hands links and shortcuts to the streaming apps installed on the TV (YouTube, Apple Music,
 * Spotify). Their catalogues and sign-in stay in their own apps; this just gets you there.
 */
object Handoff {
    const val YOUTUBE = "com.google.android.youtube.tv"
    const val APPLE_MUSIC = "com.apple.android.music"
    const val SPOTIFY = "com.spotify.tv.android"
    /** Google Meet on Google TV (the former Duo app keeps this package name). */
    const val MEET = "com.google.android.apps.tachyon"
    /** AirScreen: receives iPhone Screen Mirroring (AirPlay), e.g. a Meet call using the phone's camera. */
    const val AIRSCREEN = "com.ionitech.airscreen"

    fun packageFor(uri: Uri): String? {
        val host = uri.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.")
        return when (host) {
            "youtube.com", "youtu.be", "music.youtube.com" -> YOUTUBE
            "music.apple.com" -> APPLE_MUSIC
            "open.spotify.com" -> SPOTIFY
            "meet.google.com" -> MEET
            else -> null
        }
    }

    fun installed(c: Context, pkg: String): Boolean = try {
        c.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** youtu.be short links become full watch links, which the TV YouTube app understands. */
    fun viewIntent(uri: Uri, pkg: String): Intent {
        val target = if (uri.host.equals("youtu.be", ignoreCase = true)) {
            Uri.parse("https://www.youtube.com/watch?v=" + uri.lastPathSegment.orEmpty())
        } else uri
        return Intent(Intent.ACTION_VIEW, target).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens the app, or its Play Store page when it isn't installed yet. */
    fun launch(c: Context, pkg: String) {
        val pm = c.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            c.startActivity(intent)
        } catch (e: Exception) {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
