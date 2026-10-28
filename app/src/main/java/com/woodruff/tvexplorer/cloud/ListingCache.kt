package com.woodruff.tvexplorer.cloud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last listing of each library place (Movies, TV Shows, a show's seasons), kept on disk with
 * its TMDB details, so the grid appears the moment the screen opens — even right after the app
 * starts — while the fresh listing loads behind it.
 */
object ListingCache {
    private fun dir(c: Context) = File(c.applicationContext.cacheDir, "listings").apply { mkdirs() }

    private fun file(c: Context, place: DrivePlace): File? {
        val key = when (place) {
            is DrivePlace.Named -> "named-" + place.title
            is DrivePlace.Folder -> "folder-" + place.id
            else -> return null
        }
        return File(dir(c), key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
    }

    fun read(c: Context, place: DrivePlace): List<DriveFile>? = runCatching {
        val f = file(c, place)?.takeIf { it.exists() } ?: return null
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
    }.getOrNull()

    fun write(c: Context, place: DrivePlace, files: List<DriveFile>) {
        val f = file(c, place) ?: return
        runCatching {
            val arr = JSONArray()
            files.forEach { arr.put(toJson(it)) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        }
    }

    private fun toJson(d: DriveFile) = JSONObject()
        .put("id", d.id).put("n", d.name).put("m", d.mimeType).put("s", d.size).put("t", d.modified)
        .put("th", d.thumbnailLink ?: "").put("p", d.posterUrl ?: "")
        .apply {
            d.info?.let { i ->
                put("i", JSONObject().put("ti", i.title).put("y", i.year ?: 0).put("g", JSONArray(i.genres))
                    .put("r", i.rating ?: 0.0).put("o", i.overview ?: "").put("b", i.backdrop ?: "")
                    .put("id", i.tmdbId ?: 0).put("tv", i.tv))
            }
        }

    private fun fromJson(o: JSONObject): DriveFile {
        val i = o.optJSONObject("i")
        return DriveFile(
            id = o.getString("id"), name = o.optString("n"), mimeType = o.optString("m"),
            size = o.optLong("s"), modified = o.optLong("t"),
            thumbnailLink = o.optString("th").ifBlank { null }, posterUrl = o.optString("p").ifBlank { null },
            info = i?.let {
                val g = it.optJSONArray("g")
                MediaInfo(
                    it.optString("ti"), it.optInt("y").takeIf { y -> y > 0 },
                    g?.let { a -> (0 until a.length()).map { k -> a.getString(k) } }.orEmpty(),
                    it.optDouble("r", 0.0).takeIf { r -> r > 0 },
                    it.optString("o").ifBlank { null }, it.optString("b").ifBlank { null },
                    it.optInt("id").takeIf { n -> n > 0 }, it.optBoolean("tv"),
                )
            },
        )
    }
}
