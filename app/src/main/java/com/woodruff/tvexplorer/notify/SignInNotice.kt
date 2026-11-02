package com.woodruff.tvexplorer.notify

import android.content.Context
import com.woodruff.tvexplorer.cloud.GoogleAuth

/**
 * The calm note that Google really ended one of this TV's sign-ins (it expired or was revoked):
 * which one, and where to sign in again. Posted once per ending, however many things find out (the
 * player, the doorbell, a screen) and however often the TV is offline afterwards, as a quiet notice
 * ([Notices.post] with quiet): Woodruff's own banner if Woodruff is on screen, otherwise kept unread
 * for Notifications and Home's At a glance. Never for a network failure: only a renewal that Google
 * refused ends a sign-in ([GoogleAuth.refresh]).
 */
object SignInNotice {
    /** Which endings were told of, one per sign-in ("DRIVE:1767312600000,NEST:…"), in the notices' settings. */
    private const val K_TOLD = "signin.ended"

    /** [grant]'s sign-in ended at [at] (ms): its notice, unless this ending's was posted already. Any thread. */
    fun ended(c: Context, grant: GoogleAuth.Grant, at: Long) {
        val prefs = Notices.prefs(c)
        synchronized(this) {
            val told = prefs.getString(K_TOLD, null)
            if (wasTold(told, grant, at)) return
            // apply(): written to disk on SharedPreferences' own thread.
            prefs.edit().putString(K_TOLD, withTold(told, grant, at)).apply()
        }
        val (title, body) = words(grant)
        Notices.post(c, NoticeSource.SYSTEM, title, body, quiet = true)
    }

    /**
     * The notice for [grant]: its title, then what happened and where to sign in again, on the TV or (each of the
     * four, from this TV's own phone app: its sign-in sheet) the phone (offline-states.md §3.4, phone-signin.md §5.2).
     */
    internal fun words(grant: GoogleAuth.Grant): Pair<String, String> = when (grant) {
        GoogleAuth.Grant.DRIVE -> "Sign in to Google Drive again" to
            "Google ended this TV’s sign-in. Open Streaming and scan the code with your phone. Your library and progress are kept.$FROM_PHONE"
        GoogleAuth.Grant.PHOTOS -> "Connect Google Photos again" to "Google ended this TV’s sign-in. Settings → Accounts → Google Photos.$FROM_PHONE"
        GoogleAuth.Grant.CALENDAR -> "Connect Google Calendar again" to "Google ended this TV’s sign-in. Settings → Accounts → Google Calendar.$FROM_PHONE"
        GoogleAuth.Grant.NEST -> "Connect your cameras again" to
            "Google ended this TV’s sign-in. Open the Command Center and scan the code with your phone.$FROM_PHONE"
    }

    /** Every sign-in can be made again from this TV's phone app, as well as on the TV. */
    private const val FROM_PHONE = " Or do it from the phone app."

    /** Whether [told] (see [K_TOLD]) already has [grant]'s ending at [at]. Visible for tests. */
    internal fun wasTold(told: String?, grant: GoogleAuth.Grant, at: Long): Boolean =
        parse(told)[grant.name] == at

    /** [told] with [grant]'s ending at [at] in place of any earlier one of its. Visible for tests. */
    internal fun withTold(told: String?, grant: GoogleAuth.Grant, at: Long): String =
        (parse(told) + (grant.name to at)).entries.joinToString(",") { (g, t) -> "$g:$t" }

    private fun parse(told: String?): Map<String, Long> =
        told.orEmpty().split(',').mapNotNull { part ->
            val g = part.substringBefore(':', "")
            val t = part.substringAfter(':', "").toLongOrNull()
            if (g.isEmpty() || t == null) null else g to t
        }.toMap()
}
