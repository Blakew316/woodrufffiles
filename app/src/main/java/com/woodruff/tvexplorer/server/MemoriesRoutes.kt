package com.woodruff.tvexplorer.server

import android.content.Context
import android.content.Intent
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.photos.FamilyMemories
import com.woodruff.tvexplorer.player.ImageViewerActivity
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.util.Opener
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * The phone's Family Memories tab ([FamilyMemories]): what's in it, pictures of it for the grid and the photo
 * viewer, adding from the phone (the file itself as the body, as uploads are sent), deleting, and showing one on
 * the TV. A picture's address carries its item's time, so the phone keeps each one for as long as it's the same.
 */
internal class MemoriesRoutes(private val ctx: Context, private val launch: (Intent) -> Unit) {

    fun handle(s: IHTTPSession, body: () -> JSONObject): Response? = when {
        s.uri == "/api/memories" && s.method == Method.GET -> json(listJson())
        s.uri == "/api/memories/thumb" && s.method == Method.GET -> picture(s, FamilyMemories.Size.GRID)
        s.uri == "/api/memories/photo" && s.method == Method.GET -> picture(s, FamilyMemories.Size.VIEW)
        s.uri == "/api/memories/add" && s.method == Method.POST -> add(s)
        s.uri == "/api/memories/delete" && s.method == Method.POST -> {
            val f = FamilyMemories.find(body().optString("path"))
            if (!f.delete()) throw IOException("Couldn’t delete ${f.name}")
            FamilyMemories.deleted(ctx, f)
            json(listJson())
        }
        s.uri == "/api/memories/show" && s.method == Method.POST -> {
            show(FamilyMemories.find(body().optString("path")))
            json(JSONObject().put("ok", true))
        }
        else -> null
    }

    /** The listing as last read, kept for [FRESH_MS] while nothing was added or deleted from the phone ([FamilyMemories.changes]). */
    private class Listed(val changes: Int, val at: Long, val items: List<FamilyMemories.Item>)
    @Volatile private var listed: Listed? = null

    /**
     * What's in Family Memories: read again only when the phone added or deleted something, or after a few seconds
     * (so a Google Photos pick shows up). Show on TV follows each swipe on the phone, and listing both folders (a stat
     * and a date for every file, through Android 11+'s storage layer) for each one made the TV lag behind (finding 47).
     */
    private fun items(): List<FamilyMemories.Item> {
        val now = android.os.SystemClock.elapsedRealtime()
        val changes = FamilyMemories.changes.value
        listed?.let { if (it.changes == changes && now - it.at in 0..FRESH_MS) return it.items }
        return FamilyMemories.list(ctx).also { listed = Listed(changes, now, it) }
    }

    private fun listJson(): JSONObject {
        val (w, h) = FamilyMemories.fit(ctx)
        return JSONObject()
            .put("items", JSONArray().apply { items().forEach { put(itemJson(it)) } })
            .put("folder", FamilyMemories.photos.path)
            // The size photos are kept at: the phone shrinks what it sends to fit it.
            .put("fit", JSONArray().put(w).put(h))
            // The room a photo or video from the phone has (both folders are on the TV's own storage): the phone checks
            // each one against it before sending, and says so in plain words when it won't fit (finding 44).
            .apply { UploadRoom.of(FamilyMemories.videos, onTv = true)?.let { put("free", it) } }
            .put("slideshow", Appearance.slideshowFolder == FamilyMemories.photos.path)
    }

    private fun itemJson(i: FamilyMemories.Item) = JSONObject()
        .put("path", i.file.path).put("name", i.file.name).put("video", i.video)
        .put("taken", i.taken).put("size", i.file.length()).put("v", i.file.lastModified())

    private fun picture(s: IHTTPSession, size: FamilyMemories.Size): Response {
        val f = FamilyMemories.find(s.parameters["path"]?.firstOrNull().orEmpty())
        val picture = FamilyMemories.picture(ctx, f, size)
            ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "No picture of ${f.name}")
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(picture), picture.length())
            .apply { addHeader("Cache-Control", "private, max-age=31536000") }
    }

    /** ?name= and, when the phone could read it, ?taken= (ms); the body is the photo or video. */
    private fun add(s: IHTTPSession): Response {
        val name = s.parameters["name"]?.firstOrNull() ?: throw IllegalArgumentException("Missing name")
        // One that won't fit is turned down before anything is written (the phone checks first; this is the backstop).
        val video = FamilyMemories.kindOf(name.substringAfterLast('/')) == FileKind.VIDEO
        UploadRoom.check(name, s.headers["content-length"]?.toLongOrNull(),
            UploadRoom.of(if (video) FamilyMemories.videos else FamilyMemories.photos, onTv = true), com.woodruff.tvexplorer.tvs.TvLinks.name(ctx))
        val target = FamilyMemories.target(name)
        try {
            receiveFile(s, target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        FamilyMemories.added(ctx, target, s.parameters["taken"]?.firstOrNull()?.toLongOrNull())
        return json(JSONObject().put("item", itemJson(FamilyMemories.item(ctx, target))))
    }

    /**
     * A photo opens in the photo viewer with the other photos either side of it, in the phone's order (left and
     * right on the remote go through them), at most Opener's 300 (an intent's extras cross a ~1 MB binder buffer).
     * While the viewer is up with this photo among its own, the phone's next photo is only where to go: a tiny intent,
     * no listing (finding 47). A video plays.
     */
    private fun show(f: File) {
        if (FamilyMemories.kindOf(f.name) == FileKind.VIDEO) {
            launch(PlayerActivity.intent(ctx, listOf(f.path), listOf(f.name), f.path))
            return
        }
        if (ImageViewerActivity.shows(f.path)) {
            // It's on screen, so Woodruff may bring it forward itself (it has a window showing), pop-ups allowed or not.
            val next = ImageViewerActivity.follow(ctx, f.path)
            if (runCatching { ctx.startActivity(next) }.isFailure) launch(next)
            return
        }
        val start = f.path   // (found in Family Memories: its real path, as photoPaths gives them)
        val photos = photoPaths(items())
        launch(ImageViewerActivity.intent(ctx, Opener.window(if (start in photos) photos else listOf(start) + photos, start), start)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** The photos' real paths (each folder resolved once, not each file), as [FamilyMemories.find] gives one. */
    private fun photoPaths(items: List<FamilyMemories.Item>): List<String> {
        val real = HashMap<String, String>()
        return items.filter { !it.video }.map { i ->
            val dir = i.file.parent.orEmpty()
            File(real.getOrPut(dir) { runCatching { File(dir).canonicalPath }.getOrDefault(dir) }, i.file.name).path
        }
    }

    private fun json(o: JSONObject) = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    private companion object {
        /** How long a listing is reused while nothing changed from the phone. */
        const val FRESH_MS = 5_000L
    }
}
