package com.woodruff.tvexplorer.player

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The one try a Google Drive film gets straight away when the connection or Google fails it (a stall:
 * [PlaybackErrors.stallOf]), before the player stays on it and waits ([RetryWait]). Most of those are
 * a moment's hiccup, so the film is tried again from where it was, as it was.
 *
 * Drive refusing the access token itself (401) is different: offered the same token again, it refuses
 * again, every time, until the token runs out by the TV's clock. That can be most of an hour, for a
 * token kept while the TV's clock was off, or one Google stopped taking early. So that token is dropped
 * first ([forgetAccess], GoogleAuth's), and the try asks Google for a new one. A sign-in Google has
 * ended comes to light in that renewal: the try fails, and the player, waiting now, finds the sign-in
 * gone and says how to sign in again.
 *
 * Once per film, until it plays ([played]). Called on the main thread; dropping the token can wait on a
 * renewal already under way, so it never happens there ([io]).
 */
internal class RetryAtOnce(
    private val scope: CoroutineScope,
    /** The player in use now; null once it's let go. A try for a player let go meanwhile is dropped. */
    private val current: () -> Player?,
    private val io: CoroutineContext = Dispatchers.IO,
    private val forgetAccess: () -> Unit,
) {
    /** How a failed film is tried again at once. */
    enum class Way {
        /** From where it was, as it was. */
        AS_IT_WAS,

        /** From where it was, once the access token Drive refused is dropped: the try asks Google for a new one. */
        NEW_TOKEN,
    }

    /** The film that had its try, until it plays. */
    private var tried: String? = null

    /**
     * Tries [p]'s film [key] again at once, if it hasn't had its try: true when that's under way, false
     * when the player should wait instead ([way]). [http] is the server's answer, if one came; [drive]:
     * the film is in Google Drive.
     */
    fun tryAgain(p: Player, key: String?, http: Int?, drive: Boolean): Boolean {
        val way = way(http, drive, tried = key == tried) ?: return false
        tried = key
        when (way) {
            Way.AS_IT_WAS -> p.prepare()
            Way.NEW_TOKEN -> scope.launch {
                withContext(io) { forgetAccess() }
                // Unless it was let go meanwhile, or something else has started it already (Play pressed).
                if (current() === p && p.playbackState == Player.STATE_IDLE) p.prepare()
            }
        }
        return true
    }

    /** The film plays: trouble later on gets a try of its own. */
    fun played() {
        tried = null
    }

    companion object {
        /**
         * How a stalled film is tried again at once: only a Drive film (a link sent from the phone that
         * fails won't change its mind a moment later), only once ([tried]), and with a new token when
         * Drive refused the one it had ([http] 401). Null: not at once; the player waits.
         */
        fun way(http: Int?, drive: Boolean, tried: Boolean): Way? = when {
            !drive || tried -> null
            http == 401 -> Way.NEW_TOKEN
            else -> Way.AS_IT_WAS
        }
    }
}
