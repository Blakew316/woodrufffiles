package com.woodruff.tvexplorer.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.mail.MailAccounts
import com.woodruff.tvexplorer.mail.MailClient
import com.woodruff.tvexplorer.mail.MailProvider
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.player.PlayerActivity
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phone-companion routes on top of the file server: notifications, "play/open on TV", settings
 * (Google keys, mail accounts) and the Google sign-in callback. All but the callback require the
 * pairing key (checked by [TransferServer] before [handle] is called).
 */
class CompanionRoutes(private val ctx: Context) {

    /** The Google OAuth callback proves itself with the one-time `state`, not the pairing key. */
    fun public(s: IHTTPSession): Response? {
        if (s.uri != "/oauth/callback") return null
        val state = s.parameters["state"]?.firstOrNull()
        val attempt = CompanionHub.googleAttempt
        if (state == null || attempt == null || state != attempt.state) {
            return page("Sign-in expired", "Open Google Drive on the TV and scan the new QR code.")
        }
        s.parameters["error"]?.firstOrNull()?.let {
            return page("Sign-in cancelled", "You can try again from the TV.")
        }
        val code = s.parameters["code"]?.firstOrNull() ?: return page("Missing code", "Try again from the TV.")
        CompanionHub.googleAttempt = null // one use only
        CompanionHub.googleCodes.tryEmit(code to attempt)
        return page("You're signed in ✓", "Look at your TV: Woodruff Pictures is loading your Google Drive.")
    }

    fun handle(s: IHTTPSession): Response? = when {
        s.uri == "/api/notify" && s.method == Method.POST -> {
            val b = body(s)
            val title = b.optString("title").ifBlank { b.optString("message") }.ifBlank { "Message from your phone" }
            val text = b.optString("body").ifBlank { if (b.has("title")) b.optString("message") else "" }
            Notices.post(ctx, NoticeSource.PHONE, title, text, b.optString("link").ifBlank { null })
            ok()
        }
        s.uri == "/api/open" && s.method == Method.POST -> {
            val url = body(s).optString("url").trim()
            require(url.startsWith("http://") || url.startsWith("https://")) { "Send a web address starting with http:// or https://" }
            openUrl(url)
            ok()
        }
        s.uri == "/api/play" && s.method == Method.POST -> {
            val path = body(s).optString("path")
            val f = File(path).canonicalFile
            require(f.isFile) { "Not a file" }
            launch(PlayerActivity.intent(ctx, listOf(f.path), listOf(f.name), f.path))
            ok()
        }
        s.uri == "/api/settings" && s.method == Method.GET -> json(settingsJson(s))
        s.uri == "/api/settings/google" && s.method == Method.POST -> {
            val b = body(s)
            val id = b.optString("client_id").trim()
            val secret = b.optString("client_secret").trim()
            require(id.endsWith(".apps.googleusercontent.com")) { "The Client ID ends in .apps.googleusercontent.com" }
            require(secret.isNotEmpty()) { "Enter the Client secret" }
            GoogleAuth.saveClient(ctx, id, secret)
            CompanionHub.settingsChanged.tryEmit(Unit)
            json(settingsJson(s))
        }
        s.uri == "/api/mail" && s.method == Method.POST -> {
            val b = body(s)
            val provider = runCatching { MailProvider.valueOf(b.optString("provider")) }.getOrDefault(MailProvider.CUSTOM)
            val account = MailAccounts.add(
                ctx, provider, b.optString("email"), b.optString("password"),
                b.optString("host").ifBlank { null }, b.optString("port").toIntOrNull(),
            )
            // Check it works now, so a wrong password is reported on the phone, not discovered later.
            try {
                runBlocking { MailClient.test(account) }
            } catch (e: Exception) {
                MailAccounts.remove(ctx, account.id)
                throw IllegalArgumentException(e.message ?: "Couldn't sign in to ${provider.label}")
            }
            CompanionHub.settingsChanged.tryEmit(Unit)
            json(settingsJson(s))
        }
        s.uri == "/api/mail/delete" && s.method == Method.POST -> {
            MailAccounts.remove(ctx, body(s).optString("id"))
            CompanionHub.settingsChanged.tryEmit(Unit)
            json(settingsJson(s))
        }
        else -> null
    }

    private fun settingsJson(s: IHTTPSession): JSONObject {
        val clientId = GoogleAuth.clientId(ctx)
        val host = s.headers["host"] ?: "this-tv:8080"
        return JSONObject()
            // For iPhone Shortcuts / automations: the pairing key travels in the URL.
            .put("notifyUrl", "http://$host/api/notify?k=${PairingKey.get(ctx)}")
            .put("google", JSONObject()
                .put("clientId", clientId ?: "")
                .put("hasSecret", GoogleAuth.isConfigured(ctx))
                .put("connected", GoogleAuth.isConnected(ctx))
                .put("redirectUri", GoogleAuth.REDIRECT_URI))
            .put("mail", JSONArray().apply {
                MailAccounts.list(ctx).forEach { put(JSONObject().put("id", it.id).put("email", it.email).put("provider", it.provider.label)) }
            })
            .put("providers", JSONArray().apply {
                MailProvider.entries.forEach { put(JSONObject().put("id", it.name).put("label", it.label).put("help", it.passwordHelp).put("custom", it == MailProvider.CUSTOM)) }
            })
    }

    /** Videos and audio play in the built-in player; anything else goes to whatever app handles links. */
    private fun openUrl(url: String) {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        val media = listOf(".mp4", ".mkv", ".webm", ".m3u8", ".mpd", ".mov", ".mp3", ".m4a", ".aac", ".flac", ".ts").any { path.endsWith(it) }
        if (media) launch(PlayerActivity.intent(ctx, listOf(url), listOf(Uri.parse(url).lastPathSegment ?: url), url))
        else try {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Notices.post(ctx, NoticeSource.PHONE, "Link from your phone", "This TV has no app for this link: $url", url)
        }
    }

    /**
     * Starting a screen from the background needs "Display over other apps" on Android 10+.
     * Without it, the request becomes a notification the viewer can open.
     */
    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (com.woodruff.tvexplorer.notify.AppVisibility.foreground || android.provider.Settings.canDrawOverlays(ctx)) {
            ctx.startActivity(intent)
        } else {
            Notices.post(ctx, NoticeSource.PHONE, "Ready to play", "Open Woodruff Pictures to start it, or allow \"Display over other apps\" in Settings.")
        }
    }

    private fun body(s: IHTTPSession): JSONObject {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        val q = JSONObject()
        s.parameters.forEach { (k, v) -> if (k != "k") v.firstOrNull()?.let { q.put(k, it) } }
        if (len <= 0) return q
        require(len < 64 * 1024) { "Request too large" }
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        val text = String(buf, 0, read, Charsets.UTF_8)
        // JSON from the web app and Shortcuts; plain text bodies become the message.
        return runCatching { JSONObject(text) }.getOrElse { q.put("message", text) }.also { o ->
            q.keys().forEach { k -> if (!o.has(k)) o.put(k, q.get(k)) }
        }
    }

    private fun ok() = json(JSONObject().put("ok", true))
    private fun json(o: JSONObject) = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    private fun page(title: String, body: String) = NanoHTTPD.newFixedLengthResponse(
        Response.Status.OK, "text/html; charset=utf-8",
        """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Woodruff Pictures</title><body style="margin:0;padding:28px;font:17px/1.5 -apple-system,system-ui,sans-serif;background:#0b0d12;color:#f2f4f7">
        <h1 style="font-size:24px;margin:0 0 10px">$title</h1><p style="color:#9aa4b2">$body</p></body>""",
    )
}

/** Process-wide companion state shared by the server and the screens. */
object CompanionHub {
    /** The Google sign-in currently waiting for the phone (see GoogleAuth.newAttempt). */
    @Volatile var googleAttempt: com.woodruff.tvexplorer.cloud.GoogleAuth.Attempt? = null
    val googleCodes = MutableSharedFlow<Pair<String, com.woodruff.tvexplorer.cloud.GoogleAuth.Attempt>>(extraBufferCapacity = 4)
    /** Emitted when keys or accounts are changed from the phone, so open screens refresh. */
    val settingsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val changes: SharedFlow<Unit> = settingsChanged.asSharedFlow()
}
