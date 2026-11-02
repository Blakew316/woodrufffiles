package com.woodruff.tvexplorer.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.mail.MailAccounts
import com.woodruff.tvexplorer.mail.MailClient
import com.woodruff.tvexplorer.mail.MailProvider
import com.woodruff.tvexplorer.notify.Chime
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.notes.Notes
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import com.woodruff.tvexplorer.phones.Phones
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.remote.RemoteInput
import com.woodruff.tvexplorer.remote.TvScreen
import com.woodruff.tvexplorer.tvs.Health
import com.woodruff.tvexplorer.tvs.HealthGrant
import com.woodruff.tvexplorer.tvs.SignIns
import com.woodruff.tvexplorer.tvs.TvHealth
import com.woodruff.tvexplorer.tvs.TvHealthKind
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.tvs.TvTargets
import com.woodruff.tvexplorer.tvs.aboutTv
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.screensaver.SlideshowActivity
import com.woodruff.tvexplorer.util.Handoff
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.settings.SharedKey
import com.woodruff.tvexplorer.settings.SharedKeys
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
    private val memories = MemoriesRoutes(ctx) { launch(it) }
    /** The phone's Watch tab: its library, title pages, marks and Watch together (a start waits on the TV when it can't pop up). */
    private val watching = WatchRoutes(ctx) { launch(it, hold = true) }

    /** The Google OAuth callback proves itself with the one-time `state`, not the pairing key. */
    fun public(s: IHTTPSession): Response? {
        // The Home Screen app's manifest, named after this TV (PhoneIcon); no secrets in it, and iOS may
        // fetch it without cookies. (The name is no secret either: the TV announces it on the network.)
        if (s.uri == "/manifest.webmanifest") {
            val template = ctx.assets.open("web/manifest.webmanifest").bufferedReader().use { it.readText() }
            val bytes = PhoneIcon.manifest(template, PhoneIcon.label(ctx)).toByteArray()
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/manifest+json", bytes.inputStream(), bytes.size.toLong())
                .apply { addHeader("Cache-Control", "max-age=604800") }
        }
        // The Home Screen icon's picture, with this TV's name written on it (PhoneIcon). The app's own
        // icon-*.png, which it shows inside, stay as they are.
        PhoneIcon.PICTURES[s.uri]?.let { size ->
            val bytes = PhoneIcon.picture(ctx, size, PhoneIcon.label(ctx))
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/png", bytes.inputStream(), bytes.size.toLong())
                .apply { addHeader("Cache-Control", "max-age=604800") }
        }
        // Home Screen icons: no secrets in them, and iOS may fetch them without cookies.
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
            Notices.post(ctx, NoticeSource.SYSTEM, "TV linked", "${b.optString("from").ifBlank { "Another TV" }} can now see what’s playing here.")
            return json(r)
        }
        // A linked hub TV: its link key opens only status, screen, remote keys and typing, the keys every TV
        // shares, this TV's health report and an update check (TvLinks.LINK_ROUTES).
        if (s.uri in TvLinks.LINK_ROUTES && TvLinks.isLinkKey(ctx, s.parameters["k"]?.firstOrNull())) return handle(s)
        if (s.uri != "/oauth/callback") return null
        val state = s.parameters["state"]?.firstOrNull()
        val attempt = state?.let { CompanionHub.googleAttempts[it] }
        if (attempt == null) {
            // Used, replaced or too late; one the phone started points back to the phone.
            if (state != null && PhoneSignIn.known(state)) return phonePage(PhonePage.Old)
            return page("Sign-in expired", "On the TV, open the screen you were connecting and scan the new QR code.")
        }
        if (attempt.fromPhone) return phoneCallback(s, attempt)
        s.parameters["error"]?.firstOrNull()?.let {
            return page("Sign-in cancelled", "You can try again from the TV.")
        }
        val code = s.parameters["code"]?.firstOrNull() ?: return page("Missing code", "Try again from the TV.")
        CompanionHub.googleAttempts.remove(attempt.state) // one use only
        CompanionHub.googleCodes.tryEmit(code to attempt)
        if (attempt.grant == GoogleAuth.Grant.CALENDAR) {
            return page("Google Calendar connected ✓", "Today’s events will appear on your TV in a moment.")
        }
        if (attempt.grant == GoogleAuth.Grant.PHOTOS) {
            return page("Google Photos connected ✓", "Look at your TV: scan the next QR code to pick photos.")
        }
        if (attempt.grant == GoogleAuth.Grant.NEST) {
            return page("Cameras connected ✓", "Look at your TV: Woodruff Family is loading your cameras.")
        }
        return page("You’re signed in ✓", "Look at your TV: Woodruff Family is loading your Google Drive.")
    }

    /**
     * Google's answer for a sign-in started from the phone: finished here and now (no screen needed), and its page,
     * once the TV has finished (or as long as it can wait), says so and points back to the phone.
     */
    private fun phoneCallback(s: IHTTPSession, attempt: GoogleAuth.Attempt): Response {
        // One use: taken here, unless a newer attempt for the same sign-in just replaced it.
        if (!CompanionHub.take(attempt)) return phonePage(PhonePage.Old)
        if (s.parameters["error"]?.firstOrNull() != null) {
            PhoneSignIn.cancelled(attempt.state)
            return phonePage(PhonePage.Cancelled)
        }
        val code = s.parameters["code"]?.firstOrNull() ?: return phonePage(PhonePage.NotFinished(PhoneSignIn.incomplete(ctx, attempt.state)))
        // Too late (the phone stopped waiting and said so): nothing is used.
        val job = PhoneSignIn.finish(ctx, code, attempt) ?: return phonePage(PhonePage.Old)
        return when (val o = PhoneSignIn.outcome(job)) {
            PhoneSignIn.Outcome.SignedIn -> phonePage(PhonePage.Done(attempt.grant))
            is PhoneSignIn.Outcome.Failed -> phonePage(PhonePage.NotFinished(o.reason))
            PhoneSignIn.Outcome.Slow -> phonePage(PhonePage.Finishing)
        }
    }

    private fun phonePage(p: PhonePage): Response = phonePage(p, TvLinks.name(ctx)).let { (title, body) -> page(title, body) }

    fun handle(s: IHTTPSession): Response? = when {
        // The phone's Family Memories tab.
        s.uri.startsWith("/api/memories") -> memories.handle(s) { body(s) }
        // The phone's Watch tab.
        s.uri == "/api/watch/home" || s.uri.startsWith("/api/library") || s.uri == "/api/poster" ||
            s.uri == "/api/continue/remove" || s.uri == "/api/tvs/watch" -> watching.handle(s) { body(s) }
        s.uri == "/api/notify" && s.method == Method.POST -> {
            val b = body(s)
            val title = b.optString("title").ifBlank { b.optString("message") }.ifBlank { "Message from your phone" }
            val text = b.optString("body").ifBlank { if (b.has("title")) b.optString("message") else "" }
            // Shortcut links copied from Settings now end in &tvs=all (every TV); older ones reach this TV only.
            val to = targets(b, TvTargets.HERE)
            if (to.here) Notices.post(ctx, NoticeSource.PHONE, title, text, b.optString("link").ifBlank { null }, chime = Chime.Sound.ANNOUNCE)
            val others = TvLinks.announce(ctx, to.others, title, text, null)
            json(JSONObject().put("ok", true).put("tvs", others + (if (to.here) 1 else 0)))
        }
        // A message relayed by the hub TV (the one holding the links), with the announcement chime.
        // Shown once per id: the same message relayed twice, or by two TVs, appears once.
        s.uri == "/api/notice" && s.method == Method.POST -> {
            val b = body(s)
            val title = b.optString("title").trim()
            require(title.isNotEmpty()) { "Empty message" }
            val from = b.optString("from").ifBlank { null }
            val text = b.optString("body").trim().ifEmpty { from?.let { "From $it" }.orEmpty() }
            // Labelled as on the TV that sent it: from a phone, or from Woodruff (a TV's own announcement).
            val source = if (b.optString("source") == NoticeSource.SYSTEM.name) NoticeSource.SYSTEM else NoticeSource.PHONE
            if (Notices.firstTime(b.optString("id"))) Notices.post(ctx, source, title, text, chime = Chime.Sound.ANNOUNCE)
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
        // Play on TV: a video on this TV's storage (Photos & Files), or something in Continue watching
        // that isn't on Google Drive ("key": a path or a web address), from "position" when it's given.
        s.uri == "/api/play" && s.method == Method.POST -> {
            val b = body(s)
            val key = b.optString("key").ifBlank { b.optString("path") }
            val ref = com.woodruff.tvexplorer.player.MediaRef.parse(key)
            val (item, name) = when (ref) {
                is com.woodruff.tvexplorer.player.MediaRef.Local -> {
                    val f = File(ref.path).canonicalFile
                    require(f.isFile) { "That video isn’t on ${TvLinks.name(ctx)} any more." }
                    f.path to f.name
                }
                else -> key to (Uri.parse(key).lastPathSegment ?: key)
            }
            // A resume starts where this TV last stopped it (the player reads it), not where the phone was told.
            val position = if (!WatchStart.resumes(b) && b.has("position")) b.optLong("position").coerceAtLeast(0) else null
            started(launch(PlayerActivity.intent(ctx, listOf(item), listOf(b.optString("title").ifBlank { name }), item, position), hold = true))
        }
        // A Drive video from the phone, the hub, or another TV for Watch together. A resume starts where
        // anyone in the family last stopped ([WatchStart]); a spot given otherwise is where it starts.
        // The answer says whether it's on screen ("started") or waiting for Woodruff to be opened here.
        s.uri == "/api/watch" && s.method == Method.POST -> {
            val b = body(s)
            val key = b.optString("key")
            val ref = com.woodruff.tvexplorer.player.MediaRef.parse(key)
            require(ref is com.woodruff.tvexplorer.player.MediaRef.Drive) { "Only Google Drive videos can be watched together" }
            WatchLibrary.changed()
            val start = WatchStart.of(ctx, b, b.optString("title").ifBlank { "Video" })
            started(launch(PlayerActivity.intent(ctx, listOf(start.key), listOf(start.title), start.key, start.positionMs), hold = true))
        }
        // A message the hub pinned: "at" is when it was pinned there (a hub from before sends none),
        // so a TV shows it once, also when the hub sends it again later for a TV that was off.
        s.uri == "/api/board" && s.method == Method.POST -> {
            val b = body(s)
            require(b.optString("title").isNotBlank()) { "Empty message" }
            com.woodruff.tvexplorer.notify.Board.receive(
                ctx, b.optString("title"), b.optString("body"), b.optString("from").ifBlank { null },
                b.optLong("at").takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
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
            com.woodruff.tvexplorer.nest.Doorbell.ring(
                ctx, b.optString("camera"), b.optString("label"), relay = false, kind = b.optString("kind").ifBlank { "doorbell" },
                // The visit (one ring per visit, however it's heard) and the TV that passed it on.
                session = b.optString("session").ifBlank { null }, from = b.optString("from").ifBlank { null },
            )
            ok()
        }
        // ---- Several TVs (this one is the hub) ------------------------------------------------
        s.uri == "/api/status" && s.method == Method.GET -> json(TvLinks.status(ctx))
        // This TV's health report (also for a linked hub, with its link key): words and facts, no secrets.
        s.uri == "/api/health" && s.method == Method.GET -> json(Health.report(ctx, probe = true).toJson())
        // Check for an update now, for the phone or a linked hub: it follows the test TV's day, as this TV's own checks
        // do (only Settings → Updates → Check for updates, on the TV itself, skips that).
        s.uri == "/api/update/check" && s.method == Method.POST -> {
            body(s)
            com.woodruff.tvexplorer.update.Updater.checkNow(ctx, eager = false)
            ok()
        }
        s.uri == "/api/tvs" && s.method == Method.GET -> {
            val list = runBlocking { TvLinks.statuses(ctx) }
            json(JSONObject().put("tvs", JSONArray().apply { list.forEachIndexed { i, t -> put(tvJson(t, i == 0)) } }))
        }
        // A TV's whole health report for the phone's TV page: this TV's own, or a linked TV's (asked
        // with its link key, about 4 s at most). A linked TV that's off, turns down its link, runs an
        // older Woodruff or doesn't answer: 409, with its last report if this TV kept one.
        s.uri == "/api/tvs/health" && s.method == Method.GET -> {
            val id = s.parameters["id"]?.firstOrNull().orEmpty()
            if (id == "this") json(Health.report(ctx, probe = true).toJson())
            else {
                val tv = TvLinks.find(id) ?: throw IllegalArgumentException("Unknown TV")
                val k = runBlocking { TvHealth.check(ctx, tv) }
                val report = k.report
                if ((k.kind == TvHealthKind.GOOD || k.kind == TvHealthKind.ATTENTION) && report != null) {
                    json(report.toJson().put("at", k.checkedAt ?: report.at).put("via", TvLinks.name(ctx)))
                } else {
                    val view = TvHealth.view(tv).copy(kind = if (k.kind == TvHealthKind.CHECKING) TvHealthKind.OFF else k.kind)
                    NanoHTTPD.newFixedLengthResponse(
                        Response.Status.CONFLICT, "application/json",
                        JSONObject().put("status", view.kind.json).put("message", aboutTv(view) ?: "")
                            .put("version", k.version ?: JSONObject.NULL).put("seen", TvLinks.seenAt(tv.id) ?: JSONObject.NULL)
                            .put("last", report?.toJson()?.put("at", k.checkedAt ?: report.at) ?: JSONObject.NULL)
                            .put("lastAt", k.checkedAt ?: JSONObject.NULL).toString(),
                    )
                }
            }
        }
        s.uri == "/api/tvs/update/check" && s.method == Method.POST -> {
            val id = body(s).optString("id")
            if (id == "this") com.woodruff.tvexplorer.update.Updater.checkNow(ctx, eager = false)
            else {
                val tv = TvLinks.find(id) ?: throw IllegalArgumentException("Unknown TV")
                try {
                    TvLinks.checkForUpdates(tv)
                } catch (e: java.io.IOException) {
                    throw IllegalArgumentException("${tv.name} didn’t respond")
                }
            }
            ok()
        }
        // Links again a TV whose link stopped working, with the code it shows (finding 55): one entry, not two.
        s.uri == "/api/tvs/relink" && s.method == Method.POST -> {
            val b = body(s)
            val tv = try {
                runBlocking { TvLinks.relink(ctx, b.optString("id"), b.optString("code")) }
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException(e.message ?: "Couldn’t reach that TV.")
            }
            json(JSONObject().put("ok", true).put("name", tv.name).put("id", tv.id))
        }
        s.uri == "/api/tvs/screen" && s.method == Method.GET -> {
            val tv = TvLinks.find(s.parameters["id"]?.firstOrNull().orEmpty()) ?: throw IllegalArgumentException("Unknown TV")
            val jpeg = runCatching { TvLinks.screen(tv) }.getOrNull()
            if (jpeg == null) NanoHTTPD.newFixedLengthResponse(Response.Status.CONFLICT, "application/json",
                JSONObject().put("message", "${tv.name} isn’t showing Woodruff Family, or is off.").toString())
            else NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", jpeg.inputStream(), jpeg.size.toLong())
                .apply { addHeader("Cache-Control", "no-store") }
        }
        // ↺10, 30↻, Next (and Play/Pause) for a linked TV, from the phone's controls.
        s.uri == "/api/tvs/media" && s.method == Method.POST -> {
            val b = body(s)
            val tv = TvLinks.find(b.optString("id")) ?: throw IllegalArgumentException("That TV isn’t linked any more.")
            val action = com.woodruff.tvexplorer.nowplaying.MediaAction.of(b.optString("action")) ?: throw IllegalArgumentException("Unknown action")
            val answer = try {
                TvLinks.media(tv, action.wire)
            } catch (e: TvLinks.Refused) {
                throw IllegalArgumentException(e.message ?: "${tv.name} can’t do that now")
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException("${tv.name} didn’t respond")
            }
            json(JSONObject().put("ok", true).put("playing", if (answer.has("playing")) answer.opt("playing") else JSONObject.NULL))
        }
        s.uri == "/api/tvs/key" && s.method == Method.POST -> {
            val b = body(s)
            val tv = TvLinks.find(b.optString("id")) ?: throw IllegalArgumentException("Unknown TV")
            try {
                TvLinks.press(tv, b.optString("key"), b.optInt("repeat"))
            } catch (e: TvLinks.Refused) {
                throw IllegalArgumentException("${tv.name}: ${e.message}")
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException("${tv.name} didn’t respond")
            }
            ok()
        }
        // The phone's keyboard on a linked TV, through this one (finding 42): the text box open there, and the
        // text typed into it. A TV that can't take typing from here isn't "gone": the answer says why instead.
        s.uri == "/api/tvs/type" && s.method == Method.GET -> {
            val tv = TvLinks.find(s.parameters["id"]?.firstOrNull().orEmpty()) ?: throw IllegalArgumentException("That TV isn’t linked any more.")
            try {
                json(JSONObject().put("field", TvLinks.field(tv) ?: JSONObject.NULL))
            } catch (e: TvLinks.TypingRefused) {
                json(JSONObject().put("field", JSONObject.NULL).put("note", cantType(tv)))
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException("${tv.name} didn’t respond")
            }
        }
        s.uri == "/api/tvs/type" && s.method == Method.POST -> {
            val b = body(s)
            val tv = TvLinks.find(b.optString("id")) ?: throw IllegalArgumentException("That TV isn’t linked any more.")
            try {
                TvLinks.type(tv, if (b.has("field")) b.optLong("field") else null, b.optString("text").take(MAX_TYPED), b.optBoolean("submit"))
            } catch (e: TvLinks.TypingRefused) {
                throw IllegalArgumentException(cantType(tv))
            } catch (e: TvLinks.Refused) {
                throw IllegalArgumentException(e.message ?: "No text box is open on ${tv.name}")
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException("${tv.name} didn’t respond")
            }
            ok()
        }

        // ---- TV screen & remote ------------------------------------------------------------
        s.uri == "/api/screen" && s.method == Method.GET -> {
            // ?w= asks for a smaller picture (the hub's thumbnail of this TV); the phone's is the widest.
            val jpeg = TvScreen.frame(s.parameters["w"]?.firstOrNull()?.toIntOrNull() ?: TvScreen.WIDTH)
            if (jpeg == null) {
                NanoHTTPD.newFixedLengthResponse(Response.Status.CONFLICT, "application/json",
                    JSONObject().put("message", "Woodruff Family isn’t on the TV screen right now. Press Home to open it.").toString())
            } else {
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", jpeg.inputStream(), jpeg.size.toLong())
                    .apply { addHeader("Cache-Control", "no-store") }
            }
        }
        s.uri == "/api/key" && s.method == Method.POST -> {
            val b = body(s)
            val key = b.optString("key")
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
                // Brings Woodruff Family up on its Home screen (from another app too, if allowed): the
                // open main window, whatever was over it closing, never a second one.
                launch(com.woodruff.tvexplorer.MainIntents.open(ctx, "home"))
            } else if (key in Handoff.REMOTE_APPS) {
                // The phone remote's Netflix and YouTube buttons.
                openApp(Handoff.REMOTE_APPS.getValue(key))
            } else if (key == "playpause" && !TvScreen.onScreen) {
                // Another app is in front (Netflix, YouTube…): play or pause what it's playing.
                NowPlaying.playPause(ctx)
            } else {
                // "repeat": an arrow held on the phone (1, 2, 3… after its first press).
                require(TvScreen.press(key, b.optInt("repeat"))) { "Woodruff Family isn’t on the TV screen. Press Home first." }
            }
            ok()
        }
        // The phone's controls for what this TV plays (also from a linked hub, for its phone): ↺10, 30↻
        // and Next where what's playing can (NowPlaying says which), Play/Pause as the remote's key.
        s.uri == "/api/media" && s.method == Method.POST -> {
            val action = com.woodruff.tvexplorer.nowplaying.MediaAction.of(body(s).optString("action")) ?: throw IllegalArgumentException("Unknown action")
            val now = NowPlaying.current(ctx)
            val cmd = com.woodruff.tvexplorer.nowplaying.PlayerCommand.of(action)
            if (cmd == null) {
                if (!TvScreen.onScreen || NowPlaying.ownPlayer == null) NowPlaying.playPause(ctx)
                else require(TvScreen.press("playpause")) { "Nothing is playing on ${TvLinks.name(ctx)}" }
                json(JSONObject().put("ok", true).put("playing", now?.let { !it.playing } ?: JSONObject.NULL))
            } else when (val r = NowPlaying.command(ctx, cmd)) {
                is com.woodruff.tvexplorer.nowplaying.CommandResult.Done -> json(JSONObject().put("ok", true).put("playing", r.playing ?: JSONObject.NULL))
                com.woodruff.tvexplorer.nowplaying.CommandResult.NothingPlaying -> throw IllegalArgumentException("Nothing is playing on ${TvLinks.name(ctx)}")
                com.woodruff.tvexplorer.nowplaying.CommandResult.CantDo -> throw IllegalArgumentException("That doesn’t work with what’s playing on ${TvLinks.name(ctx)}")
            }
        }

        // ---- Phone keyboard (also for a linked hub, for its phone: typing is pressing this TV's keys) ----------
        // Only while Woodruff is in front, as with the remote's keys: a text box left open behind another app or one of
        // Android's screens is never typed into, nor its Done pressed, where nobody can see (the phone says why).
        s.uri == "/api/type" && s.method == Method.GET -> json(
            if (!TvScreen.canPress()) JSONObject().put("field", JSONObject.NULL).put("note", NOT_IN_FRONT)
            else JSONObject().put("field", RemoteInput.target?.let { f ->
                JSONObject().put("id", f.id).put("label", f.label).put("secret", f.secret).put("multiline", f.multiline)
                    .put("text", if (f.secret) "" else f.text)
            } ?: JSONObject.NULL),
        )
        s.uri == "/api/type" && s.method == Method.POST -> {
            val b = body(s)
            require(TvScreen.canPress()) { NOT_IN_FRONT }
            val id = if (b.has("id")) b.optLong("id") else null
            val open = RemoteInput.fields.value
            require(open.isNotEmpty()) { "No text box is open on ${TvLinks.name(ctx)}" }
            // Text meant for a box that has closed never lands in another one opened since (finding 42).
            require(id == null || open.any { it.id == id }) { "That text box isn’t open on ${TvLinks.name(ctx)} any more." }
            require(RemoteInput.type(id, b.optString("text").take(MAX_TYPED), b.optBoolean("submit"))) { "No text box is open on ${TvLinks.name(ctx)}" }
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
        // A phone's messages after the last one it has (?after=, with the inbox's ?epoch= it was numbered in), never
        // taken: one whose reply didn't reach the phone comes again (finding 43). Without ?after=, as before numbers.
        s.uri == "/api/inbox" && s.method == Method.GET -> json(com.woodruff.tvexplorer.phones.PhoneInbox.since(
            ctx, s.parameters["id"]?.firstOrNull().orEmpty(),
            after = s.parameters["after"]?.firstOrNull()?.toLongOrNull(), epoch = s.parameters["epoch"]?.firstOrNull(),
        ))
        s.uri == "/api/announce" && s.method == Method.POST -> {
            val b = body(s)
            val title = b.optString("title").trim()
            require(title.isNotEmpty()) { "Write a title first" }
            val text = b.optString("body").trim()
            val from = b.optString("from").ifBlank { null }
            // The phone's "TV screen" switch means every TV (this one and each linked TV); "tvs"
            // can name fewer.
            val to = targets(b, if (b.optBoolean("tv", true)) TvTargets.ALL else TvTargets.NONE)
            if (to.here) Notices.post(ctx, NoticeSource.PHONE, title, if (from != null && text.isEmpty()) "From $from" else text, chime = Chime.Sound.ANNOUNCE)
            // Sent in the background: the phone never waits on a TV that's off.
            val tvs = TvLinks.announce(ctx, to.others, title, text, from) + (if (to.here) 1 else 0)
            // Pinned: stays on Home on this TV and every linked TV until dismissed there.
            if (b.optBoolean("pin")) com.woodruff.tvexplorer.notify.Board.pin(ctx, title, text, from, relay = true)
            val phones = b.optJSONArray("phones")
            val sent = when {
                b.optString("phones") == "all" -> Phones.announce(ctx, null, title, text, from)
                phones != null && phones.length() > 0 -> Phones.announce(ctx, (0 until phones.length()).map { phones.getString(it) }, title, text, from)
                else -> 0
            }
            json(JSONObject().put("ok", true).put("phones", sent).put("tvs", tvs))
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

        // ---- Signing this TV in from its phone app (PhoneSignIn) ---------------------------------------
        // This TV's own pairing key only: never in LINK_ROUTES, since sign-ins aren't shared between TVs.
        s.uri == "/api/signin" && s.method == Method.POST -> {
            val grant = HealthGrant.of(body(s).optString("grant"))?.let(SignIns::googleGrant) ?: throw IllegalArgumentException("Unknown sign-in")
            when (val r = PhoneSignIn.start(ctx, grant, TransferService.state.value.base)) {
                is PhoneSignIn.Start.Ready -> json(JSONObject().put("state", r.state).put("authUrl", r.authUrl).put("expiresAt", r.expiresAt)
                    .put("again", r.again).put("tv", r.tv))
                is PhoneSignIn.Start.Needs -> NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    JSONObject().put("need", r.need).put("message", r.message).toString())
                is PhoneSignIn.Start.CantStart -> NanoHTTPD.newFixedLengthResponse(Response.Status.CONFLICT, "application/json",
                    JSONObject().put("message", r.message).toString())
            }
        }
        s.uri == "/api/signin/status" && s.method == Method.GET -> json(PhoneSignIn.status(
            ctx, s.parameters["state"]?.firstOrNull().orEmpty(), HealthGrant.of(s.parameters["grant"]?.firstOrNull())?.let(SignIns::googleGrant),
        ))
        s.uri == "/api/settings" && s.method == Method.GET -> json(settingsJson(s))
        // Its body is read too (unread, it would be taken as the start of the phone's next request on this connection).
        s.uri == "/api/crash/clear" && s.method == Method.POST -> {
            body(s)
            com.woodruff.tvexplorer.util.CrashLog.clear(ctx)
            // Seen and cleared: that crash no longer makes this TV "need attention".
            Health.crashesCleared(ctx)
            json(settingsJson(s))
        }
        // The phone's Smoothness Report (Settings › TVs): plain text. X-Recording-Left: the seconds the
        // detailed recording still runs, 0 when none is running.
        s.uri == "/api/diagnostics" && s.method == Method.GET -> {
            val left = (com.woodruff.tvexplorer.util.MainThreadWatch.state().leftMs + 999) / 1000
            // A report that fails to build says so, rather than taking the app down with it.
            val text = runCatching { com.woodruff.tvexplorer.util.FrameStats.report(ctx) }
                .getOrElse { "Couldn’t make the smoothness report: $it" }
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", text)
                .apply { addHeader("Cache-Control", "no-store"); addHeader("X-Recording-Left", left.toString()) }
        }
        // Record on the phone: like OK on the TV's Smoothness row, counts afresh and records 3 minutes in detail.
        s.uri == "/api/diagnostics/record" && s.method == Method.POST -> {
            body(s) // read, so the connection can carry the next request
            com.woodruff.tvexplorer.util.FrameStats.reset()
            json(JSONObject().put("ok", true).put("seconds", com.woodruff.tvexplorer.util.MainThreadWatch.RECORD_MS / 1000))
        }
        s.uri == "/api/settings/google" && s.method == Method.POST -> {
            val b = body(s)
            val id = b.optString("client_id").trim()
            val secret = b.optString("client_secret").trim()
            require(id.endsWith(".apps.googleusercontent.com")) { "The Client ID ends in .apps.googleusercontent.com" }
            require(secret.isNotEmpty()) { "Enter the Client secret" }
            // Saved here and copied to every linked TV.
            SharedKeys.set(ctx, SharedKey.GOOGLE) { GoogleAuth.saveClient(ctx, id, secret) }
            CompanionHub.settingsChanged.tryEmit(Unit)
            json(settingsJson(s))
        }
        s.uri == "/api/settings/tmdb" && s.method == Method.POST -> {
            // Checked with TMDB first (one light request): a key it refuses isn't saved, and the phone
            // says why. One that can't be checked now is saved, and the phone says it's checked later.
            val key = body(s).optString("key").trim()
            val check = if (key.isEmpty()) null else runBlocking { Tmdb.check(key) }
            if (check == Tmdb.KeyCheck.REFUSED) throw IllegalArgumentException(Tmdb.REFUSED_WORDS)
            // Saved here and copied to every linked TV.
            SharedKeys.set(ctx, SharedKey.TMDB) { Tmdb.saveKey(ctx, key) }
            CompanionHub.settingsChanged.tryEmit(Unit)
            json(settingsJson(s).apply { if (check == Tmdb.KeyCheck.UNCHECKED) put("note", Tmdb.UNCHECKED_WORDS) })
        }
        // The keys every TV shares (SharedKeys): a linked TV asks what this TV has (with ?id=…, that
        // key itself) and sends a newer one, which is kept only if it is newer.
        s.uri == "/api/keys" && s.method == Method.GET ->
            json(SharedKeys.json(ctx, withValue = SharedKey.of(s.parameters["id"]?.firstOrNull())))
        s.uri == "/api/keys" && s.method == Method.POST -> {
            val b = body(s)
            val key = SharedKey.of(b.optString("id")) ?: throw IllegalArgumentException("Unknown key")
            SharedKeys.receive(ctx, key, b.optString("value").ifBlank { null }, b.optLong("at"))
            json(SharedKeys.json(ctx))
        }
        // The TMDB key alone, as a hub on 2.0.157 (before the other keys were shared) asks for it.
        s.uri == "/api/tmdb" && s.method == Method.GET -> {
            val tmdb = SharedKeys.json(ctx, withValue = SharedKey.TMDB).getJSONObject(SharedKey.TMDB.id)
            json(JSONObject().put("fp", tmdb.optString("fp")).put("at", tmdb.optLong("at"))
                .apply { if (s.parameters["key"]?.firstOrNull() == "1") put("key", tmdb.optString("value")) })
        }
        s.uri == "/api/tmdb" && s.method == Method.POST -> {
            val b = body(s)
            SharedKeys.receive(ctx, SharedKey.TMDB, b.optString("key").ifBlank { null }, b.optLong("at"))
            json(JSONObject().put("fp", SharedKeys.version(ctx, SharedKey.TMDB).fingerprint).put("at", SharedKeys.at(ctx, SharedKey.TMDB)))
        }
        s.uri == "/api/settings/nest" && s.method == Method.POST -> {
            val b = body(s)
            if (b.has("project")) SharedKeys.set(ctx, SharedKey.CAMERAS) { GoogleAuth.saveNestProject(ctx, b.optString("project")) }
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
                throw IllegalArgumentException(e.message ?: "Couldn’t sign in to ${provider.label}")
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
        // The sign-ins as Settings → Accounts says them (offline never changes them), so the phone's Connections
        // words are the TV's: "Signed in", "Sign-in ended", "Keys not accepted", "Not signed in", "Not set up".
        val drive = SignIns.account(ctx, HealthGrant.DRIVE).state
        val cameras = SignIns.account(ctx, HealthGrant.CAMERAS).state
        return JSONObject()
            // For iPhone Shortcuts / automations: the pairing key travels in the URL. tvs=all: the
            // message shows on every linked TV too (links copied before reach only this TV).
            .put("notifyUrl", "http://$host/api/notify?k=${PairingKey.get(ctx)}&tvs=all")
            .put("shareUrl", "http://$host/api/share?k=${PairingKey.get(ctx)}")
            .put("google", JSONObject()
                .put("clientId", clientId ?: "")
                .put("hasSecret", GoogleAuth.isConfigured(ctx))
                .put("connected", GoogleAuth.isConnected(ctx))
                .put("state", drive.json).put("text", drive.words(HealthGrant.DRIVE))
                .put("redirectUri", GoogleAuth.REDIRECT_URI))
            .put("tmdb", Tmdb.isConfigured(ctx))
            .put("weather", com.woodruff.tvexplorer.weather.Weather.place(ctx) ?: "")
            .put("version", com.woodruff.tvexplorer.BuildConfig.VERSION_NAME)
            // This TV's last crash for the phone (shown, copied, maybe pasted somewhere public): nothing secret in it.
            .put("crash", com.woodruff.tvexplorer.util.CrashLog.last(ctx)?.let { com.woodruff.tvexplorer.tvs.scrubbed(it) } ?: "")
            .put("awayUrl", TransferService.awayUrl() ?: "")
            .put("nest", JSONObject()
                .put("project", GoogleAuth.nestProject(ctx) ?: "")
                .put("connected", GoogleAuth.isConnected(ctx, GoogleAuth.Grant.NEST))
                .put("state", cameras.json).put("text", cameras.words(HealthGrant.CAMERAS))
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
    /** Opens [app] on this TV, or says why it can't (not installed, or not allowed over another app). */
    private fun openApp(app: Handoff.RemoteApp) {
        val intent = Handoff.launchIntent(ctx, app) ?: throw IllegalArgumentException("${app.label} isn’t installed on this TV")
        require(com.woodruff.tvexplorer.notify.AppVisibility.foreground || android.provider.Settings.canDrawOverlays(ctx)) {
            "To open ${app.label} from the phone, turn on Pop-ups over other apps in Woodruff’s Settings on the TV, or open Woodruff first."
        }
        launch(intent)
    }

    /**
     * Starts [intent] on this TV; true when it's on screen. Without the pop-up permission and with
     * none of Woodruff's screens up, it becomes a notice instead; with [hold], the video also waits to
     * start the moment Woodruff is opened here ([PendingPlay]), which is what that notice and the phone say.
     */
    private fun launch(intent: Intent, hold: Boolean = false): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (PendingPlay.mayStartNow(ctx)) {
            PendingPlay.clear()
            ctx.startActivity(intent)
            return true
        }
        if (hold) PendingPlay.hold(ctx, intent)
        Notices.post(ctx, NoticeSource.PHONE, "Ready to play", "Open Woodruff Family to start it, or allow \"Display over other apps\" in Settings.")
        return false
    }

    /** "started": on screen now, or waiting on the TV ("message" says what to do there). */
    private fun started(on: Boolean) = json(
        JSONObject().put("ok", true).put("started", on)
            .apply { if (!on) put("message", "It’s waiting on the TV: open Woodruff Family there.") },
    )

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

    /**
     * One TV for the phone's TV list: what it plays, its version, when it last answered ([TvStatus.seen]),
     * whether it turns down its link ("refused": Link again) and its health in a few words ("good",
     * "attention", "old" for a Woodruff from before health reports, else "unknown").
     */
    private fun tvJson(t: com.woodruff.tvexplorer.tvs.TvStatus, me: Boolean): JSONObject {
        val health = when {
            t.refused || !t.online -> null
            !t.reportsHealth -> com.woodruff.tvexplorer.tvs.HealthSummary("old", 0, null)
            else -> t.health
        }
        return JSONObject().put("id", if (me) "this" else t.id).put("name", t.name).put("online", t.online)
            .put("onScreen", t.onScreen).put("playing", t.playing?.toJson() ?: JSONObject.NULL)
            .put("version", t.version ?: JSONObject.NULL)
            .put("seen", t.seen ?: JSONObject.NULL)
            .put("link", if (t.refused) "refused" else "ok")
            .put("health", health?.toJson() ?: JSONObject().put("status", "unknown").put("count", 0).put("first", JSONObject.NULL))
    }

    /** Why the linked TV [tv] can't be typed into from the phone: its link stopped working, or its Woodruff is older. */
    private fun cantType(tv: com.woodruff.tvexplorer.tvs.LinkedTv) =
        if (TvLinks.refuses(tv.id)) "${tv.name}’s link stopped working. Link it again in Settings → TVs."
        else "${tv.name} needs a newer Woodruff for typing."

    /**
     * The TVs a message is for, from its optional "tvs": "all", "this", or TVs by id or name (a list,
     * or comma-separated, e.g. ?tvs=this,Den). Without it, [default].
     */
    private fun targets(b: JSONObject, default: TvTargets): TvTargets {
        val asked = when (val v = b.opt("tvs")) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            is String -> v.split(',')
            else -> null
        }
        return TvLinks.targets(ctx, asked, default) ?: throw IllegalArgumentException("No TV by that name")
    }

    /** Only files on shared storage, never app-private data. */
    private fun sharedPath(path: String): String {
        val f = File(path).canonicalFile
        require(f.path.startsWith("/storage/") || f.path.startsWith("/mnt/media_rw/")) { "Choose something on the TV’s storage" }
        return f.path
    }

    private fun ok() = json(JSONObject().put("ok", true))
    private fun json(o: JSONObject) = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    /** A plain page for the phone's browser (Google's answer lands on one). Its words may hold a TV's name: escaped. */
    private fun page(title: String, body: String) = NanoHTTPD.newFixedLengthResponse(
        Response.Status.OK, "text/html; charset=utf-8",
        """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Woodruff Family</title><body style="margin:0;padding:28px;font:17px/1.5 -apple-system,system-ui,sans-serif;background:#0b0d12;color:#f2f4f7">
        <h1 style="font-size:24px;margin:0 0 10px">${html(title)}</h1><p style="color:#9aa4b2">${html(body)}</p></body>""",
    )

    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** The most text the phone's keyboard sends to a text box at once (a long note; a request stays well under 64 KB). */
private const val MAX_TYPED = 20_000

/** Why the phone can't type into this TV now: Woodruff isn't in front (its remote keys say the same). */
private const val NOT_IN_FRONT = "Woodruff Family isn’t on the TV screen. Press Home first."

private val PUBLIC_ASSETS = mapOf(
    "/icon-180.png" to "image/png",
    "/icon-512.png" to "image/png",
    "/emblem.png" to "image/png",
    "/icon-192.png" to "image/png",
)

/** Process-wide companion state shared by the server and the screens. */
object CompanionHub {
    /** Google sign-ins waiting for the phone, by `state` (Drive and cameras can be pending at once). */
    val googleAttempts = java.util.concurrent.ConcurrentHashMap<String, com.woodruff.tvexplorer.cloud.GoogleAuth.Attempt>()

    /**
     * Starts waiting for [a]; replaces any older attempt for the same kind of sign-in. A phone that was waiting on
     * the one replaced is told ([PhoneSignIn.replaced]): only the newest attempt's answer counts.
     */
    @Synchronized
    fun expect(a: com.woodruff.tvexplorer.cloud.GoogleAuth.Attempt) {
        val gone = googleAttempts.values.filter { it.grant == a.grant && it.state != a.state }
        gone.forEach { googleAttempts.remove(it.state, it) }
        googleAttempts[a.state] = a
        gone.forEach { if (it.fromPhone) PhoneSignIn.replaced(it.state) }
    }

    /**
     * Takes [a] for its answer, once: false when it's no longer waiting (replaced meanwhile). Under the same lock as
     * [expect], so a phone never hears "replaced" about an attempt whose answer is being used.
     */
    @Synchronized
    fun take(a: com.woodruff.tvexplorer.cloud.GoogleAuth.Attempt): Boolean = googleAttempts.remove(a.state, a)

    val googleCodes = MutableSharedFlow<Pair<String, com.woodruff.tvexplorer.cloud.GoogleAuth.Attempt>>(extraBufferCapacity = 4)
    /** Emitted when keys or accounts are changed from the phone, so open screens refresh. */
    val settingsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val changes: SharedFlow<Unit> = settingsChanged.asSharedFlow()
}

/**
 * A video the phone (or a linked TV) started while none of Woodruff's screens was up and Woodruff isn't
 * allowed to pop up over other apps: it waits, and starts the moment Woodruff is opened on this TV,
 * within 10 minutes (later than that, nobody expects it any more). The phone says so ("Ready on Den…
 * open Woodruff Family there"), and so does the TV's own notice. Only the newest one waits.
 *
 * Opened is whichever of Woodruff's screens comes back to the front: the TV's app row brings Woodruff
 * back as it was (MainIntents), so that may be a paused film or the photo viewer rather than the main
 * window, and a notice's tap brings the main window. The video starts over that screen, in Woodruff's
 * own task, so Back returns to it; once. Woodruff's screens put back after an update come back first
 * ([com.woodruff.tvexplorer.update.Restore], the video over them). The doorbell's camera, which pops up
 * by itself on a ring, and the recovery screen don't count as opening Woodruff.
 */
internal object PendingPlay {
    private const val KEEP_MS = 10 * 60_000L
    private class Waiting(val intent: Intent, val at: Long)
    @Volatile private var waiting: Waiting? = null
    /** The app whose screens are watched: once per run (each test has an app of its own). */
    @Volatile private var watched: java.lang.ref.WeakReference<android.app.Application>? = null

    /**
     * Whether Woodruff may bring a screen up now: one of its own screens is up ([onScreenNow]: an app on
     * screen may open its screens), or it may pop up over other apps. Otherwise a start waits ([hold]).
     */
    fun mayStartNow(c: Context): Boolean = onScreenNow() || runCatching { android.provider.Settings.canDrawOverlays(c) }.getOrDefault(false)

    /**
     * One of Woodruff's screens is up: the main window, or the player, the photo viewer, the slideshow or
     * the doorbell's camera (not only the main window: a video started from the phone during a film used
     * to wait for the film to end). Never the recovery screen, over which nothing of the app starts.
     */
    internal fun onScreenNow(): Boolean =
        (com.woodruff.tvexplorer.notify.AppVisibility.foreground || com.woodruff.tvexplorer.remote.TvScreen.onScreen) &&
            !com.woodruff.tvexplorer.update.RecoveryView.inFront

    fun hold(c: Context, intent: Intent) {
        waiting = Waiting(intent, android.os.SystemClock.elapsedRealtime())
        watch(c)
    }

    fun clear() { waiting = null }

    /** Whether something is waiting now (for tests and the log). */
    val holding: Boolean get() = waiting?.let { fresh(it.at, android.os.SystemClock.elapsedRealtime()) } == true

    /** Whether a start held at [at] still waits at [now]. Visible for tests. */
    internal fun fresh(at: Long, now: Long): Boolean = now - at in 0..KEEP_MS

    /**
     * Whether [activity] coming to the front is Woodruff being opened: one of the screens a person opens or
     * comes back to (the main window, a film, the photo viewer, Woodruff's slideshow). Not the doorbell's
     * camera, which opens by itself on a ring; not the recovery screen; not a window on its way out (the
     * second main window the app row may start, which closes at once). Visible for tests.
     */
    internal fun opensWoodruff(activity: android.app.Activity): Boolean =
        !activity.isFinishing && !com.woodruff.tvexplorer.update.RecoveryView.inFront && (
            activity is com.woodruff.tvexplorer.MainActivity ||
                activity is com.woodruff.tvexplorer.player.PlayerActivity ||
                activity is com.woodruff.tvexplorer.player.ImageViewerActivity ||
                activity is com.woodruff.tvexplorer.screensaver.SlideshowActivity
            )

    /**
     * [activity] came to the front: if that's Woodruff being opened ([opensWoodruff]) and a video waits (for
     * 10 minutes at most), it starts now, over [activity] in its task. Main thread. Visible for tests.
     */
    internal fun cameUp(activity: android.app.Activity) {
        if (!opensWoodruff(activity)) return
        val w = waiting ?: return
        waiting = null
        if (!fresh(w.at, android.os.SystemClock.elapsedRealtime())) return
        // Started from the screen on top, in its task (not as a task of its own): Back returns to that screen.
        runCatching { activity.startActivity(Intent(w.intent).setFlags(w.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv())) }
    }

    /** Watches for Woodruff's screens coming to the front (once per run), without changing them. */
    private fun watch(c: Context) {
        val app = c.applicationContext as? android.app.Application ?: return
        synchronized(this) {
            if (watched?.get() === app) return
            watched = java.lang.ref.WeakReference(app)
        }
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) = cameUp(activity)
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
}
