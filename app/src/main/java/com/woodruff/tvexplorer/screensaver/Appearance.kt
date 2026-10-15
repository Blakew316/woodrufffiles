package com.woodruff.tvexplorer.screensaver

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.FileTypes
import java.io.File

/**
 * App background picture and slideshow/screensaver settings. Values are Compose state so the
 * background changes immediately when set from the TV or the phone.
 */
object Appearance {
    private const val PREFS = "appearance"
    val SPEEDS = listOf(5, 10, 20, 30, 60)
    val IDLE = listOf(0, 2, 5, 10, 30)

    var background by mutableStateOf<String?>(null); private set
    var slideshowFolder by mutableStateOf<String?>(null); private set
    var seconds by mutableStateOf(10); private set
    /** Minutes without a button press before the slideshow starts inside the app; 0 = never. */
    var idleMinutes by mutableStateOf(0); private set
    /** Command center: cameras next to clock, notifications and notes (true) or cameras only. */
    var allInOne by mutableStateOf(true); private set
    /** Home's camera tiles stream live by themselves. Per TV: each live view counts against Google's limits. */
    /** Home's camera tiles: every one live, one live at a time (the rest show their last frame), or none. */
    enum class HomeCameras(val label: String) { ALL("All live"), ROTATE("One at a time (lighter)"), OFF("Off · select a camera to watch") }
    var homeCameras by mutableStateOf(HomeCameras.ALL); private set
    val homeLive: Boolean get() = homeCameras != HomeCameras.OFF

    fun load(c: Context) {
        val p = prefs(c)
        background = p.getString("background", null)?.takeIf { File(it).isFile }
        slideshowFolder = p.getString("folder", null)
        seconds = p.getInt("seconds", 10)
        idleMinutes = p.getInt("idle", 0)
        allInOne = p.getBoolean("all_in_one", true)
        // The "open the Command center at start" option was removed; drop its saved value.
        if (p.contains("start_cameras")) p.edit().remove("start_cameras").apply()
        homeCameras = p.getString("home_cameras", null)?.let { runCatching { HomeCameras.valueOf(it) }.getOrNull() }
            ?: if (p.getBoolean("home_live", true)) HomeCameras.ALL else HomeCameras.OFF
    }

    fun nextHomeCameras(c: Context) {
        homeCameras = HomeCameras.entries[(homeCameras.ordinal + 1) % HomeCameras.entries.size]
        prefs(c).edit().putString("home_cameras", homeCameras.name).apply()
    }

    fun toggleAllInOne(c: Context) {
        allInOne = !allInOne
        prefs(c).edit().putBoolean("all_in_one", allInOne).apply()
    }

    fun setBackground(c: Context, path: String?) {
        require(path == null || isImage(File(path))) { "Choose a photo (JPEG, PNG, WebP…)" }
        background = path
        prefs(c).edit().putString("background", path).apply()
    }

    fun setSlideshowFolder(c: Context, path: String?) {
        require(path == null || File(path).isDirectory) { "Choose a folder" }
        slideshowFolder = path
        prefs(c).edit().putString("folder", path).apply()
    }

    fun nextSpeed(c: Context) {
        seconds = SPEEDS[(SPEEDS.indexOf(seconds) + 1).mod(SPEEDS.size)]
        prefs(c).edit().putInt("seconds", seconds).apply()
    }

    fun nextIdle(c: Context) {
        idleMinutes = IDLE[(IDLE.indexOf(idleMinutes) + 1).mod(IDLE.size)]
        prefs(c).edit().putInt("idle", idleMinutes).apply()
    }

    /** Photos for the slideshow: the chosen folder, else the TV's Pictures and DCIM folders. */
    fun photos(c: Context, limit: Int = 2000): List<File> {
        val roots = slideshowFolder?.let { listOf(File(it)) } ?: listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        )
        return roots.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().maxDepth(6).onEnter { d -> !d.name.startsWith(".") } }
            .filter { it.isFile && isImage(it) }
            .take(limit)
            .toList()
            .shuffled()
    }

    fun isImage(f: File) = FileTypes.kindOf(f.name) == FileKind.IMAGE

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
