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
import com.woodruff.tvexplorer.notes.Notes
import com.woodruff.tvexplorer.phones.Phones
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.remote.RemoteInput
import com.woodruff.tvexplorer.remote.TvScreen
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.screensaver.SlideshowActivity
import com.woodruff.tvexplorer.util.Handoff
import com.woodruff.tvexplorer.tmdb.Tmdb
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
        // Home Screen app manifest and icons: no secrets in them, and iOS may fetch them without cookies.
        PUBLIC_ASSETS[s.uri]?.let { mime ->
            val bytes = ctx.assets.open("web" + s.uri).use { it.readBytes() }
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
                .apply { addHeader("Cache-Control", "max-age=604800") }
        }
        // Another Woodruff TV linking to this one with the 6-digit code shown here (Settings → Link this TV).
        if (s.uri == "/api/link" && s.method == Method.POST) {
            val b = body(s) // the request body can only be read once
            val r = TvLinks.redeem(ctx, b.optString("code"))
                ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Wrong or expired code")
            Notices.post(ctx, NoticeSource.SYSTEM, "TV linked", "${b.optString("from").ifBlank { "Another TV" }} can now see what's playing here.")
            return json(r)
        }
        // A linked hub TV: its link key opens only status, screen and remote keys.
        if (s.uri in TvLinks.LINK_ROUTES && TvLinks.isLinkKey(ctx, s.parameters["k"]?.firstOrNull())) return handle(s)
        if (s.uri != "/oauth/callback") return null
        val state = s.parameters["state"]?.firstOrNull()
        val attempt = state?.let { CompanionHub.googleAttempts[it] }
        if (attempt == null) {
            return page("Sign-in expired", "On the TV, open the screen you were connecting and scan the new QR code.")
        }
        s.parameters["error"]?.firstOrNull()?.let {
            return page("Sign-in cancelled", "You can try again from the TV.")
        }
        val code = s.parameters["code"]?.firstOrNull() ?: return page("Missing code", "Try again from the TV.")
        CompanionHub.googleAttempts.remove(attempt.state) // one use only
        CompanionHub.googleCodes.tryEmit(code to attempt)
        if (attempt.grant == GoogleAuth.Grant.CALENDAR) {
            return page("Google Calendar connected ✓", "Today's events will appear on your TV in a moment.")
        }
        if (attempt.grant == GoogleAuth.Grant.PHOTOS) {
            return page("Google Photos connected ✓", "Look at your TV: scan the next QR code to pick photos.")
        }
        if (attempt.grant == GoogleAuth.Grant.NEST) {
            return page("Cameras connected ✓", "Look at your TV: Woodruff Family is loading your cameras.")
        }
        return page("You're signed in ✓", "Look at your TV: Woodruff Family is loading your Google Drive.")
    }

    fun handle(s: IHTTPSession): Response? = when {
        s.uri == "/api/notify" && s.method == Method.POST -> {
            val b = body(s)
            val title = b.optString("title").ifBlank { b.optString("message") }.ifBlank { "Message from your phone" }
            val text = b.optString("body").ifBlank { if (b.has("title")) b.optString("message") else "" }
            Notices.post(ctx, NoticeSource.PHONE, title, text, b.optString("link").ifBlank { null })
            ok()
        }
        // iPhone share sheet (a Shortcut posts whatever was shared): links open on the TV (YouTube and
        // Apple Music links in their apps), anything else shows up as a message.
        s.uri == "/api/share" && s.method == Method.POST -> {
            val b = body(s)
            val input = (b.optString("input").ifBlank { b.optString("url") }.ifBlank { b.optString("text") }.ifBlank { b.optString("message") }).trim()
            require(input.isNotEmpty()) { "Nothing was shared" }
            val link = Regex("""https?://\S+""").find(input)?.value
            if (link != null) openUrl(link) else Notices.post(ctx, NoticeSource.PHONE, "Shared from iPhone", input.take(2000))
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
        // Watch together: another TV (or a phone) starts the same Drive video here at a position.
        s.uri == "/api/watch" && s.method == Method.POST -> {
            val b = body(s)
            val key = b.optString("key")
            val ref = com.woodruff.tvexplorer.player.MediaRef.parse(key)
            require(ref is com.woodruff.tvexplorer.player.MediaRef.Drive) { "Only Google Drive videos can be watched together" }
            launch(PlayerActivity.intent(ctx, listOf(key), listOf(b.optString("title").ifBlank { "Video" }), key, b.optLong("position").coerceAtLeast(0)))
            ok()
        }
        s.uri == "/api/board" && s.method == Method.POST -> {
            val b = body(s)
            require(b.optString("title").isNotBlank()) { "Empty message" }
            com.woodruff.tvexplorer.notify.Board.pin(ctx, b.optString("title"), b.optString("body"), b.optString("from").ifBlank { null }, relay = false)
            ok()
        }
        s.uri == "/api/settings/weather" && s.method == Method.POST -> {
            val place = body(s).optString("place")
            kotlinx.coroutines.runBlocking { com.woodruff.tvexplorer.weather.Weather.setPlace(ctx, place) }
            json(settingsJson(s))
        }
        s.uri == "/api/sync" && s.method == Method.POST -> {
            val b = body(s)
            com.woodruff.tvexplorer.player.WatchSync.receive(
                com.woodruff.tvexplorer.player.WatchSync.Update(b.optString("key"), b.optLong("position"), b.optBoolean("playing", true), b.optLong("at")),
            )
            ok()
        }
        s.uri == "/api/doorbell" && s.method == Method.POST -> {
            val b = body(s)
            com.woodruff.tvexplorer.nest.Doorbell.ring(ctx, b.optString("camera"), b.optString("label"), relay = false, kind = b.optString("kind").ifBlank { "doorbell" })
            ok()
        }
        // ---- Several TVs (this one is the hub) ------------------------------------------------
        s.uri == "/api/status" && s.method == Method.GET -> json(TvLinks.status(ctx))
        s.uri == "/api/tvs" && s.method == Method.GET -> {
            val list = runBlocking { TvLinks.statuses(ctx) }
            json(JSONObject().put("tvs", JSONArray().apply {
                list.forEachIndexed { i, t ->
                    put(JSONObject().put("id", if (i == 0) "this" else t.id).put("name", t.name).put("online", t.online)
                        .put("onScreen", t.onScreen).put("playing", t.playing?.toJson() ?: JSONObject.NULL))
                }
            }))
        }
        s.uri == "/api/tvs/screen" && s.method == Method.GET -> {
            val tv = TvLinks.find(s.parameters["id"]?.firstOrNull().orEmpty()) ?: throw IllegalArgumentException("Unknown TV")
            val jpeg = runCatching { TvLinks.screen(tv) }.getOrNull()
            if (jpeg == null) NanoHTTPD.newFixedLengthResponse(Response.Status.CONFLICT, "application/json",
                JSONObject().put("message", "${tv.name} isn't showing Woodruff Family, or is off.").toString())
            else NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", jpeg.inputStream(), jpeg.size.toLong())
                .apply { addHeader("Cache-Control", "no-store") }
        }
        s.uri == "/api/tvs/key" && s.method == Method.POST -> {
            val b = body(s)
            val tv = TvLinks.find(b.optString("id")) ?: throw IllegalArgumentException("Unknown TV")
            try { TvLinks.press(tv, b.optString("key")) } catch (e: java.io.IOException) { throw IllegalArgumentException("${tv.name} didn't respond") }
            ok()
        }

        // ---- TV screen & remote ------------------------------------------------------------
        s.uri == "/api/screen" && s.method == Method.GET -> {
            val jpeg = TvScreen.frame()
            if (jpeg == null) {
                NanoHTTPD.newFixedLengthResponse(Response.Status.CONFLICT, "application/json",
                    JSONObject().put("message", "Woodruff Family isn't on the TV screen right now. Press Home to open it.").toString())
            } else {
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", jpeg.inputStream(), jpeg.size.toLong())
                    .apply { addHeader("Cache-Control", "no-store") }
            }
        }
        s.uri == "/api/key" && s.method == Method.POST -> {
            val key = body(s).optString("key")
            // Volume works whatever is on screen: it's the TV's own volume, not a key press.
            val volume = mapOf(
                "volup" to android.media.AudioManager.ADJUST_RAISE,
                "voldown" to android.media.AudioManager.ADJUST_LOWER,
                "mute" to android.media.AudioManager.ADJUST_TOGGLE_MUTE,
            )[key]
            if (volume != null) {
                ctx.getSystemService(android.media.AudioManager::class.java)
                    ?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, volume, android.media.AudioManager.FLAG_SHOW_UI)
            } else if (key == "home") {
                // Brings Woodruff Family up on its Home screen (from another app too, if allowed).
                launch(Intent(ctx, com.woodruff.tvexplorer.MainActivity::class.java)
                    .putExtra(com.woodruff.tvexplorer.MainActivity.EXTRA_OPEN, "home")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } else {
                require(TvScreen.press(key)) { "Woodruff Family isn't on the TV screen. Press Home first." }
            }
            ok()
        }

        // ---- Phone keyboard -----------------------------------------------------------------
        s.uri == "/api/type" && s.method == Method.GET -> json(JSONObject().put("field", RemoteInput.target?.let { f ->
            JSONObject().put("id", f.id).put("label", f.label).put("secret", f.secret).put("multiline", f.multiline)
                .put("text", if (f.secret) "" else f.text)
        } ?: JSONObject.NULL))
        s.uri == "/api/type" && s.method == Method.POST -> {
            val b = body(s)
            val id = if (b.has("id")) b.optLong("id") else null
            require(RemoteInput.type(id, b.optString("text").take(20_000), b.optBoolean("submit"))) { "No text box is open on the TV" }
            ok()
        }

        // ---- Notes -------------------------------------------------------------------------
        s.uri == "/api/notes" && s.method == Method.GET -> {
            Notes.load(ctx)
            json(JSONObject().put("notes", JSONArray().apply { Notes.items.value.forEach { put(Notes.toJson(it)) } }))
        }
        s.uri == "/api/notes" && s.method == Method.POST -> {
            val b = body(s)
            val n = Notes.save(ctx, b.optString("id").ifBlank { null }, b.optString("title"), b.optString("body"),
                if (b.has("pinned")) b.optBoolean("pinned") else null)
            json(JSONObject().put("note", n?.let(Notes::toJson) ?: JSONObject.NULL))
        }
        s.uri == "/api/notes/delete" && s.method == Method.POST -> { Notes.delete(ctx, body(s).optString("id")); ok() }

        // ---- Phones & announcements ------------------------------------------------------------
        s.uri == "/api/phone/hello" && s.method == Method.POST -> {
            val b = body(s)
            val p = Phones.hello(ctx, b.optString("id"), b.optString("name"), if (b.has("alerts")) b.optBoolean("alerts") else null)
            json(JSONObject().put("phone", Phones.toJson(p, withTopic = true)).put("phones", phonesJson()))
        }
        s.uri == "/api/phones" && s.method == Method.GET -> json(JSONObject().put("phones", phonesJson()))
        s.uri == "/api/phone/delete" && s.method == Method.POST -> { Phones.remove(ctx, body(s).optString("id")); json(JSONObject().put("phones", phonesJson())) }
        s.uri == "/api/inbox" && s.method == Method.GET -> json(JSONObject().put("messages", Phones.takeInbox(s.parameters["id"]?.firstOrNull().orEmpty())))
        s.uri == "/api/announce" && s.method == Method.POST -> {
            val b = body(s)
            val title = b.optString("title").trim()
            require(title.isNotEmpty()) { "Write a title first" }
            val text = b.optString("body").trim()
            val from = b.optString("from").ifBlank { null }
            if (b.optBoolean("tv", true)) Notices.post(ctx, NoticeSource.PHONE, title, if (from != null && text.isEmpty()) "From $from" else text)
            // Pinned: stays on Home on this TV and every linked TV until dismissed there.
            if (b.optBoolean("pin")) com.woodruff.tvexplorer.notify.Board.pin(ctx, title, text, from, relay = true)
            val phones = b.optJSONArray("phones")
            val sent = when {
                b.optString("phones") == "all" -> Phones.announce(ctx, null, title, text, from)
                phones != null && phones.length() > 0 -> Phones.announce(ctx, (0 until phones.length()).map { phones.getString(it) }, title, text, from)
                else -> 0
            }
            json(JSONObject().put("ok", true).put("phones", sent))
        }

        // ---- Background & slideshow --------------------------------------------------------
        s.uri == "/api/background" && s.method == Method.POST -> {
            val path = body(s).optString("path").ifBlank { null }
            Appearance.setBackground(ctx, path?.let(::sharedPath))
            ok()
        }
        s.uri == "/api/slideshow" && s.method == Method.POST -> {
            val b = body(s)
            if (b.has("folder")) Appearance.setSlideshowFolder(ctx, b.optString("folder").ifBlank { null }?.let(::sharedPath))
            if (b.optBoolean("start")) launch(Intent(ctx, SlideshowActivity::class.java))
            ok()
        }

        s.uri == "/api/settings" && s.method == Method.GET -> json(settingsJson(s))
        s.uri == "/api/crash/clear" && s.method == Method.POST -> { com.woodruff.tvexplorer.util.CrashLog.clear(ctx); json(settingsJson(s)) }
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
        s.uri == "/api/settings/tmdb" && s.method == Method.POST -> {
            Tmdb.saveKey(ctx, body(s).optString("key"))
            CompanionHub.settingsChanged.tryEmit(Unit)
            json(settingsJson(s))
        }
        s.uri == "/api/settings/nest" && s.method == Method.POST -> {
            val b = body(s)
            if (b.has("project")) GoogleAuth.saveNestProject(ctx, b.optString("project"))
            if (b.has("doorbell")) com.woodruff.tvexplorer.nest.Doorbell.setSubscription(ctx, b.optString("doorbell"))
            if (b.has("person") || b.has("motion")) com.woodruff.tvexplorer.nest.Doorbell.setAlerts(
                ctx, if (b.has("person")) b.optBoolean("person") else null, if (b.has("motion")) b.optBoolean("motion") else null,
            )
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
            .put("shareUrl", "http://$host/api/share?k=${PairingKey.get(ctx)}")
            .put("google", JSONObject()
                .put("clientId", clientId ?: "")
                .put("hasSecret", GoogleAuth.isConfigured(ctx))
                .put("connected", GoogleAuth.isConnected(ctx))
                .put("redirectUri", GoogleAuth.REDIRECT_URI))
            .put("tmdb", Tmdb.isConfigured(ctx))
            .put("weather", com.woodruff.tvexplorer.weather.Weather.place(ctx) ?: "")
            .put("version", com.woodruff.tvexplorer.BuildConfig.VERSION_NAME)
            .put("crash", com.woodruff.tvexplorer.util.CrashLog.last(ctx) ?: "")
            .put("awayUrl", TransferService.awayUrl() ?: "")
            .put("nest", JSONObject()
                .put("project", GoogleAuth.nestProject(ctx) ?: "")
                .put("connected", GoogleAuth.isConnected(ctx, GoogleAuth.Grant.NEST))
                .put("doorbell", com.woodruff.tvexplorer.nest.Doorbell.subscription(ctx) ?: "")
                .put("doorbellStatus", com.woodruff.tvexplorer.nest.Doorbell.status.value ?: "")
                .put("person", com.woodruff.tvexplorer.nest.Doorbell.personAlerts(ctx))
                .put("motion", com.woodruff.tvexplorer.nest.Doorbell.motionAlerts(ctx)))
            .put("mail", JSONArray().apply {
                MailAccounts.list(ctx).forEach { put(JSONObject().put("id", it.id).put("email", it.email).put("provider", it.provider.label)) }
            })
            .put("providers", JSONArray().apply {
                MailProvider.entries.forEach { put(JSONObject().put("id", it.name).put("label", it.label).put("help", it.passwordHelp).put("custom", it == MailProvider.CUSTOM)) }
            })
    }

    /**
     * Videos and audio play in the built-in player; YouTube and Apple Music links open in those
     * apps on the TV; anything else goes to whatever app handles links.
     */
    private fun openUrl(url: String) {
        val uri = Uri.parse(url)
        val path = uri.path.orEmpty().lowercase()
        val media = listOf(".mp4", ".mkv", ".webm", ".m3u8", ".mpd", ".mov", ".mp3", ".m4a", ".aac", ".flac", ".ts").any { path.endsWith(it) }
        val app = Handoff.packageFor(uri)
        if (media) launch(PlayerActivity.intent(ctx, listOf(url), listOf(uri.lastPathSegment ?: url), url))
        else if (app != null && Handoff.installed(ctx, app)) launch(Handoff.viewIntent(uri, app))
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
            Notices.post(ctx, NoticeSource.PHONE, "Ready to play", "Open Woodruff Family to start it, or allow \"Display over other apps\" in Settings.")
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

    private fun phonesJson() = JSONArray().apply { Phones.load(ctx); Phones.items.value.forEach { put(Phones.toJson(it, withTopic = false)) } }

    /** Only files on shared storage, never app-private data. */
    private fun sharedPath(path: String): String {
        val f = File(path).canonicalFile
        require(f.path.startsWith("/storage/") || f.path.startsWith("/mnt/media_rw/")) { "Choose something on the TV's storage" }
        return f.path
    }

    private fun ok() = json(JSONObject().put("ok", true))
    private fun json(o: JSONObject) = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    private fun page(title: String, body: String) = NanoHTTPD.newFixedLengthResponse(
        Response.Status.OK, "text/html; charset=utf-8",
        """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Woodruff Family</title><body style="margin:0;padding:28px;font:17px/1.5 -apple-system,system-ui,sans-serif;background:#0b0d12;color:#f2f4f7">
        <h1 style="font-size:24px;margin:0 0 10px">$title</h1><p style="color:#9aa4b2">$body</p></body>""",
    )
}

private val PUBLIC_ASSETS = mapOf(
    "/manifest.webmanifest" to "application/manifest+json",
    "/icon-180.png" to "image/png",
    "/icon-512.png" to "image/png",
    "/emblem.png" to "image/png",
    "/icon-192.png" to "image/png",
)

/** Process-wide companion state shared by the server and the screens. */
object CompanionHub {
    /** Google sign-ins waiting for the phone, by `state` (Drive and cameras can be pending at once). */
    val googleAttempts = java.util.concurrent.ConcurrentHashMap<String, com.woodruff.tvexplorer.cloud.GoogleAuth.Attempt>()

    /** Starts waiting for [a]; replaces any older attempt for the same kind of sign-in. */
    fun expect(a: com.woodruff.tvexplorer.cloud.GoogleAuth.Attempt) {
        googleAttempts.values.removeAll { it.grant == a.grant }
        googleAttempts[a.state] = a
    }
    val googleCodes = MutableSharedFlow<Pair<String, com.woodruff.tvexplorer.cloud.GoogleAuth.Attempt>>(extraBufferCapacity = 4)
    /** Emitted when keys or accounts are changed from the phone, so open screens refresh. */
    val settingsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val changes: SharedFlow<Unit> = settingsChanged.asSharedFlow()
}
