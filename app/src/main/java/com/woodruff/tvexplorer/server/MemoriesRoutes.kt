package com.woodruff.tvexplorer.server

import android.content.Context
import android.content.Intent
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.photos.FamilyMemories
import com.woodruff.tvexplorer.player.ImageViewerActivity
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.screensaver.Appearance
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
            if (!f.delete()) throw IOException("Couldn't delete ${f.name}")
            FamilyMemories.deleted(ctx, f)
            json(listJson())
        }
        s.uri == "/api/memories/show" && s.method == Method.POST -> {
            show(FamilyMemories.find(body().optString("path")))
            json(JSONObject().put("ok", true))
        }
        else -> null
    }

    private fun listJson(): JSONObject {
        val (w, h) = FamilyMemories.fit(ctx)
        return JSONObject()
            .put("items", JSONArray().apply { FamilyMemories.list(ctx).forEach { put(itemJson(it)) } })
            .put("folder", FamilyMemories.photos.path)
            // The size photos are kept at: the phone shrinks what it sends to fit it.
            .put("fit", JSONArray().put(w).put(h))
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
        val target = FamilyMemories.target(s.parameters["name"]?.firstOrNull() ?: throw IllegalArgumentException("Missing name"))
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
     * right on the remote go through them); while it's up, showing another from the phone changes the photo in
     * it rather than opening another viewer over it. A video plays.
     */
    private fun show(f: File) {
        if (FamilyMemories.kindOf(f.name) == FileKind.VIDEO) {
            launch(PlayerActivity.intent(ctx, listOf(f.path), listOf(f.name), f.path))
            return
        }
        val photos = FamilyMemories.list(ctx).filter { !it.video }.map { it.file.path }
        val start = photos.firstOrNull { File(it).canonicalFile == f } ?: f.path
        launch(ImageViewerActivity.intent(ctx, if (start in photos) photos else listOf(start) + photos, start).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun json(o: JSONObject) = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())
}
