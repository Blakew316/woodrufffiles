package com.woodruff.tvexplorer.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.woodruff.tvexplorer.tvs.AppExit
import com.woodruff.tvexplorer.tvs.ExitKind

/**
 * Why Android closed Woodruff lately, as Android 11 and newer record it (ApplicationExitInfo): low
 * memory, not responding, a crash (also inside native code such as the cameras' WebRTC, which
 * Woodruff's own crash handler never sees: [CrashLog]) or a failed start. Routine closes (an update,
 * the TV's settings, Woodruff exiting by itself) aren't kept. Read once per run of the app, off the
 * main thread (it asks Android's activity service), and kept.
 */
object ExitReasons {
    @Volatile private var read: List<AppExit>? = null

    /** The notable closes Android remembers, newest first; null below Android 11. Blocking: call off the main thread. */
    fun recent(c: Context): List<AppExit>? {
        if (Build.VERSION.SDK_INT < 30) return null
        read?.let { return it }
        return synchronized(this) { read ?: runCatching { load(c.applicationContext) }.getOrDefault(emptyList()).also { read = it } }
    }

    @RequiresApi(30)
    private fun load(c: Context): List<AppExit> {
        val am = c.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val infos = am.getHistoricalProcessExitReasons(c.packageName, 0, 64).filter { it.processName == c.packageName }
        val notable = infos.mapNotNull { info -> exitKind(info.reason)?.let { it to info } }
        // Only the newest native crash's trace is read (it can be large): where it crashed.
        val native = notable.firstOrNull { it.first == ExitKind.NATIVE_CRASH }?.second
        val media = native?.let { info -> runCatching { info.traceInputStream?.use { readAtMost(it, 512 * 1024) } }.getOrNull()?.let(::mentionsMediaCode) } == true
        return notable.map { (kind, info) -> AppExit(kind, info.timestamp, inMediaCode = info === native && media) }
    }

    private fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** The report's kind for one of Android's exit reasons; null for the routine ones. */
internal fun exitKind(reason: Int): ExitKind? = when (reason) {
    ApplicationExitInfo.REASON_LOW_MEMORY -> ExitKind.LOW_MEMORY
    ApplicationExitInfo.REASON_ANR -> ExitKind.ANR
    ApplicationExitInfo.REASON_CRASH -> ExitKind.CRASH
    ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitKind.NATIVE_CRASH
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ExitKind.INIT_FAILURE
    else -> null
}

/**
 * Whether a native crash's trace (Android 12+ hands over the tombstone, whose library names are
 * plain text inside) points at the camera or video code: WebRTC, Android's media framework and
 * codecs, or the video decoders.
 */
internal fun mentionsMediaCode(trace: ByteArray): Boolean {
    val text = String(trace, Charsets.ISO_8859_1).lowercase()
    return MEDIA_LIBRARIES.any { it in text }
}

private val MEDIA_LIBRARIES = listOf(
    "libjingle_peerconnection", "webrtc", "libstagefright", "libmedia", "libcodec2", "/libc2", "omx", "ffmpeg", "libexoplayer",
    "libaudioclient", "mediacodec",
)
