package com.woodruff.tvexplorer.screensaver

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.FileTypes
import java.io.File
import kotlin.random.Random

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
    /** Reduce motion: nothing glides or grows; every change happens at once (see AppMotion). */
    var reduceMotion by mutableStateOf(false); private set

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
        reduceMotion = p.getBoolean("reduce_motion", false)
        com.woodruff.tvexplorer.ui.motion.AppMotion.reduced = reduceMotion
    }

    fun toggleReduceMotion(c: Context) {
        reduceMotion = !reduceMotion
        com.woodruff.tvexplorer.ui.motion.AppMotion.reduced = reduceMotion
        prefs(c).edit().putBoolean("reduce_motion", reduceMotion).apply()
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

    /**
     * Photos for the slideshow (and "On this day"): the chosen folder, else the TV's Pictures and DCIM folders. Up to
     * [limit] of them, each as likely as any other however many there are, in random order: taking the first [limit]
     * found left the rest out every time, DCIM's included. At most [LOOKED_AT] are looked at, so a slideshow of a huge
     * USB drive doesn't sit on "Loading photos…".
     */
    fun photos(c: Context, limit: Int = 2000, random: Random = Random.Default): List<File> {
        val roots = slideshowFolder?.let { listOf(File(it)) } ?: listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        )
        val found = roots.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().maxDepth(6).onEnter { d -> !d.name.startsWith(".") } }
            .filter { isImage(it) && it.isFile }
            .take(LOOKED_AT)
        return sample(found, limit, random)
    }

    /** The most photos [photos] looks at. */
    internal const val LOOKED_AT = 10_000

    fun isImage(f: File) = FileTypes.kindOf(f.name) == FileKind.IMAGE

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Up to [limit] of [items], each as likely to be among them as any other, in random order: one pass, holding only
 * [limit] (reservoir sampling).
 */
internal fun <T> sample(items: Sequence<T>, limit: Int, random: Random = Random.Default): List<T> {
    val kept = ArrayList<T>()
    var seen = 0
    for (item in items) {
        if (kept.size < limit) {
            kept += item
        } else {
            val at = random.nextInt(seen + 1)
            if (at < limit) kept[at] = item
        }
        seen++
    }
    kept.shuffle(random)
    return kept
}
