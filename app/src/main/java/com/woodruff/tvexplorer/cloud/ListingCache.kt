package com.woodruff.tvexplorer.cloud

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File

/**
 * The last listing of each library place (Movies, TV Shows, a show's seasons), kept on disk with
 * its TMDB details, so the grid appears the moment the screen opens — even right after the app
 * starts — while the fresh listing loads behind it. Movies and TV Shows are also read at launch, so
 * Home has their posters and Recently added (and search finds them) from the start.
 *
 * Read and written as a stream: a big library is megabytes of titles and synopses, and building it
 * as one string first could run a TV with a small memory allowance out of it.
 */
object ListingCache {
    /**
     * A saved listing: its titles, and when it was last listed in full (the file's time, which an
     * unchanged listing [touch]es; 0 when not known), so a library listed moments before Woodruff
     * restarted isn't listed again at once.
     */
    data class Saved(val files: List<DriveFile>, val listedAt: Long)

    private fun dir(c: Context) = File(c.applicationContext.cacheDir, "listings").apply { mkdirs() }

    /** Bumped by [clear]: a listing begun before a sign-out isn't saved after it. */
    @Volatile private var generation = 0

    /** Read as a listing begins, and handed to [write] / [touch] when it ends. */
    fun generation(): Int = generation

    private fun file(c: Context, place: DrivePlace): File? {
        val key = when (place) {
            is DrivePlace.Named -> "named-" + place.title
            is DrivePlace.Folder -> "folder-" + place.id
            else -> return null
        }
        return File(dir(c), key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
    }

    // Synchronized: a listing that's still being saved (from a load that was since replaced) and the
    // next one would otherwise write the same temporary file at once.
    @Synchronized
    fun read(c: Context, place: DrivePlace): Saved? = runCatching {
        val f = file(c, place)?.takeIf { it.exists() } ?: return null
        Saved(JsonReader(f.bufferedReader()).use(::readFiles), f.lastModified())
    }.getOrNull()

    /** Saves [files] as [place]'s listing, unless the saved listings were cleared (signed out) since [gen]. */
    @Synchronized
    fun write(c: Context, place: DrivePlace, files: List<DriveFile>, gen: Int = generation) {
        if (gen != generation) return
        val f = file(c, place) ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            JsonWriter(tmp.bufferedWriter()).use { writeFiles(it, files) }
            tmp.renameTo(f)
        }
    }

    /** Listed again and nothing changed: the saved copy counts as listed now, without writing it all again. */
    @Synchronized
    fun touch(c: Context, place: DrivePlace, gen: Int = generation) {
        if (gen != generation) return
        runCatching { file(c, place)?.takeIf { it.exists() }?.setLastModified(System.currentTimeMillis()) }
    }

    /** Signing out: the old account's titles and synopses don't stay on the TV. */
    @Synchronized
    fun clear(c: Context) {
        generation++
        File(c.applicationContext.cacheDir, "listings").deleteRecursively()
    }

    /** A listing as it's saved: an array of files. Visible for tests. */
    internal fun writeFiles(w: JsonWriter, files: List<DriveFile>) {
        w.beginArray()
        for (d in files) {
            w.beginObject()
            w.name("id").value(d.id).name("n").value(d.name).name("m").value(d.mimeType).name("s").value(d.size)
                .name("t").value(d.modified).name("c").value(d.created)
                .name("th").value(d.thumbnailLink ?: "").name("p").value(d.posterUrl ?: "")
            d.info?.let { i ->
                w.name("i").beginObject()
                    .name("ti").value(i.title).name("y").value((i.year ?: 0).toLong())
                w.name("g").beginArray()
                i.genres.forEach { w.value(it) }
                w.endArray()
                w.name("r").value(i.rating ?: 0.0).name("o").value(i.overview ?: "").name("b").value(i.backdrop ?: "")
                    .name("id").value((i.tmdbId ?: 0).toLong()).name("tv").value(i.tv)
                w.endObject()
            }
            w.endObject()
        }
        w.endArray()
    }

    /** Reads what [writeFiles] saved (and what earlier versions saved the same way). Visible for tests. */
    internal fun readFiles(r: JsonReader): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        r.beginArray()
        while (r.hasNext()) out += readFile(r)
        r.endArray()
        return out
    }

    private fun readFile(r: JsonReader): DriveFile {
        var id = ""; var name = ""; var mime = ""; var size = 0L; var modified = 0L; var created = 0L
        var thumb: String? = null; var poster: String? = null; var info: MediaInfo? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.nextString()
                "n" -> name = r.nextString()
                "m" -> mime = r.nextString()
                "s" -> size = r.nextLong()
                "t" -> modified = r.nextLong()
                "c" -> created = r.nextLong()
                "th" -> thumb = r.nextString().ifBlank { null }
                "p" -> poster = r.nextString().ifBlank { null }
                "i" -> info = readInfo(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return DriveFile(id, name, mime, size, modified, thumb, poster, info, created)
    }

    private fun readInfo(r: JsonReader): MediaInfo {
        var title = ""; var year = 0; var rating = 0.0; var overview = ""; var backdrop = ""; var tmdbId = 0; var tv = false
        val genres = ArrayList<String>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "ti" -> title = r.nextString()
                "y" -> year = r.nextInt()
                "g" -> { r.beginArray(); while (r.hasNext()) genres += r.nextString(); r.endArray() }
                "r" -> rating = r.nextDouble()
                "o" -> overview = r.nextString()
                "b" -> backdrop = r.nextString()
                "id" -> tmdbId = r.nextInt()
                "tv" -> tv = if (r.peek() == JsonToken.BOOLEAN) r.nextBoolean() else { r.skipValue(); false }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return MediaInfo(
            title, year.takeIf { it > 0 }, genres, rating.takeIf { it > 0 },
            overview.ifBlank { null }, backdrop.ifBlank { null }, tmdbId.takeIf { it > 0 }, tv,
        )
    }
}
