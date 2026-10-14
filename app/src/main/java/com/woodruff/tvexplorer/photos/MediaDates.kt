package com.woodruff.tvexplorer.photos

import android.content.Context
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * When each photo or video was taken, for the Photos / Videos date filters.
 *
 * Copies from Google Photos are resized, which drops their EXIF data, so the capture time Google
 * reports is saved here when they download. For anything else, in order: a date in the file name
 * ("PXL_20240512_140322.jpg", "IMG-20240512-WA0001", "2024-05-12 14.03.22"; cameras write the same
 * time into EXIF, and the name costs nothing to read), EXIF / video metadata, then the file's
 * modified time. Results are kept in a small index so each file is read once.
 */
object MediaDates {
    private val index = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false
    @Volatile private var dirty = false

    /** Saves the capture time Google reported for a downloaded item. */
    fun record(c: Context, file: File, takenMs: Long) {
        load(c)
        index[file.absolutePath] = takenMs
        dirty = true
        // Best effort: also visible to other apps that sort by date.
        runCatching { file.setLastModified(takenMs) }
    }

    /**
     * Capture time for each file (path → ms). Reads files, so call off the main thread.
     * Cheap checks first: the index, then the name (camera names carry the time). Only the rest
     * are opened for EXIF / video metadata, four at a time, and everything found is indexed.
     */
    fun of(c: Context, files: List<File>): Map<String, Long> {
        load(c)
        val out = HashMap<String, Long>(files.size * 2)
        val unknown = ArrayList<File>()
        for (f in files) {
            val path = f.absolutePath
            val known = index[path] ?: fromName(f.name)?.also { index[path] = it; dirty = true }
            if (known != null) out[path] = known else unknown += f
        }
        if (unknown.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, unknown.size))
            try {
                unknown.map { f -> pool.submit<Pair<String, Long>> { f.absolutePath to (fromMetadata(f) ?: f.lastModified()) } }
                    .forEach { task -> val (path, ms) = task.get(); out[path] = ms; index[path] = ms }
                dirty = true
            } finally {
                pool.shutdown()
            }
        }
        save(c)
        return out
    }

    private fun fromMetadata(f: File): Long? = runCatching {
        val name = f.name.lowercase()
        when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".heic") || name.endsWith(".png") || name.endsWith(".webp") -> {
                val exif = ExifInterface(f.absolutePath)
                (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))?.let(::parseExif)
            }
            name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4v") || name.endsWith(".3gp") -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(f.absolutePath)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::parseVideoDate)
                } finally {
                    r.release()
                }
            }
            else -> null
        }
    }.getOrNull()

    // ---- Parsing (pure, unit tested) ------------------------------------------------------------

    private val NAME_DATETIME = Regex("(?<!\\d)((?:19|20)\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})[ _T.-]+(\\d{2})[-_.:h]?(\\d{2})[-_.:m]?(\\d{2})")
    private val NAME_DATE = Regex("(?<!\\d)((?:19|20)\\d{2})([-_.]?)(\\d{2})\\2(\\d{2})(?!\\d)")

    /** A date in a camera-style file name, in local time; null when there isn't a believable one. */
    fun fromName(name: String, zone: TimeZone = TimeZone.getDefault()): Long? {
        NAME_DATETIME.find(name)?.let { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            build(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), zone)?.let { return it }
        }
        NAME_DATE.findAll(name).forEach { m ->
            build(m.groupValues[1].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt(), 12, 0, 0, zone)?.let { return it }
        }
        return null
    }

    /** EXIF "2024:05:12 14:03:22" (local time on the camera). */
    fun parseExif(s: String, zone: TimeZone = TimeZone.getDefault()): Long? {
        val m = Regex("^(\\d{4}):(\\d{2}):(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})").find(s.trim()) ?: return null
        val (y, mo, d, h, mi, sec) = m.destructured
        return build(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), sec.toInt(), zone)
    }

    /** Video metadata "20240512T140322.000Z" (UTC). Phones write 1904/1970 when unknown: ignored. */
    fun parseVideoDate(s: String): Long? {
        val m = Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})").find(s.trim()) ?: return null
        val (y, mo, d, h, mi, sec) = m.destructured
        return build(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), sec.toInt(), TimeZone.getTimeZone("UTC"))
    }

    /** Google's RFC 3339 times: "2024-05-12T14:03:22Z", "2024-05-12T14:03:22.123456Z", "…+02:00". */
    fun parseRfc3339(s: String): Long? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})$").find(s.trim()) ?: return null
        val g = m.groupValues
        val base = build(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), TimeZone.getTimeZone("UTC")) ?: return null
        val millis = g[7].padEnd(3, '0').take(3).toInt()
        val offset = if (g[8] == "Z") 0L else {
            val sign = if (g[8][0] == '-') -1 else 1
            sign * (g[8].substring(1, 3).toLong() * 3_600_000 + g[8].substring(4, 6).toLong() * 60_000)
        }
        return base + millis - offset
    }

    private fun build(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, zone: TimeZone): Long? {
        val thisYear = GregorianCalendar(zone).get(Calendar.YEAR)
        if (y !in 1990..thisYear + 1 || mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..60) return null
        val cal = GregorianCalendar(zone).apply { isLenient = false; clear(); set(y, mo - 1, d, h, mi, minOf(s, 59)) }
        return runCatching { cal.timeInMillis }.getOrNull() // Feb 30 etc. throw when not lenient
    }

    // ---- Index -----------------------------------------------------------------------------------

    private fun file(c: Context) = File(c.applicationContext.filesDir, "media_dates.json")

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(file(c).readText())
            o.keys().forEach { k -> index[k] = o.getLong(k) }
        }
    }

    @Synchronized
    fun save(c: Context) {
        if (!dirty) return
        dirty = false
        runCatching {
            // Forget files that were deleted, so the index doesn't grow forever.
            index.keys.removeAll { !File(it).exists() }
            val f = file(c)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject(index as Map<*, *>).toString())
            tmp.renameTo(f)
        }
    }
}
