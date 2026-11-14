package com.woodruff.tvexplorer.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/**
 * Why the player is trying the same film again instead of playing it: what the loading ring says
 * meanwhile (specs/player-feedback.md §3.2), and whether trying again can help at all.
 */
internal enum class Stall {
    /** No connection: the Wi-Fi dropped, a name won't resolve, the connection timed out. */
    NETWORK,

    /** The server is busy or failing (HTTP 429, 5xx): it passes by itself, and the TV's own connection is fine. */
    BUSY,

    /** Google Drive turned the request down (HTTP 401 / 403) while the TV is still signed in to it. */
    REFUSED,

    /**
     * Google Drive won't send this film for now: its daily limit ("downloadQuotaExceeded"). It clears by
     * itself, usually within a day, so it's said as that and tried again every few minutes, not every 10 s.
     */
    LIMIT,

    /**
     * Google ended the TV's Drive sign-in: nothing from Drive plays until someone signs in again,
     * so the player stops trying and says how to fix it.
     */
    SIGNED_OUT,
}

/**
 * What a playback error means for the family: a connection or server trouble the player waits out
 * on the same film ([stallOf]), or a film that can't play here, said in plain words ([why]) as the
 * player moves on to the next one. Never Media3's error code names.
 */
internal object PlaybackErrors {
    /**
     * Whether an error is the connection's or the server's rather than the film's, so it's worth
     * staying on the film and trying again, and which kind; null when the film itself can't play.
     * [http] is the server's answer, if one came; [drive]: the film is in Google Drive, the one
     * server whose refusals pass (a sign-in renewed, a quota reset). A link sent from the phone
     * that refuses won't change its mind. [reason]: Google's own word for a refusal ([reasonOf]).
     */
    fun stallOf(http: Int?, errorCode: Int, drive: Boolean, reason: String? = null): Stall? = when {
        drive && http == 403 && isDailyLimit(reason) -> Stall.LIMIT
        drive && http == 403 && isRateLimit(reason) -> Stall.BUSY
        http == 401 || http == 403 -> if (drive) Stall.REFUSED else null
        http == 429 || (http != null && http in 500..599) -> Stall.BUSY
        http != null -> null
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> Stall.NETWORK
        else -> null
    }

    /**
     * Why a film can't play, for "Can’t play {title}: {why}." (specs/player-feedback.md §3.8).
     * [outOfMemory]: the TV ran out of memory somewhere in the error's causes, which a big remux can
     * make look like a decoder or a damaged file, so it's said first.
     */
    fun why(errorCode: Int, http: Int?, drive: Boolean, outOfMemory: Boolean): String = when {
        http == 404 && drive -> "it’s no longer in Google Drive"
        outOfMemory -> "this TV ran out of memory"
        errorCode in DECODER_ERRORS -> "this TV can’t play its video format"
        errorCode in CONTAINER_ERRORS -> "the file seems to be damaged"
        else -> "this TV couldn’t open it"
    }

    /** The whole message, said over the film for 6 s as the player moves on. */
    fun cantPlay(title: String?, why: String): String = "Can’t play ${title?.ifBlank { null } ?: "this file"}: $why."

    /**
     * Google's own reason in a refusal's answer ("downloadQuotaExceeded", "userRateLimitExceeded",
     * "forbidden"…), when the answer gave one: never shown, only read.
     */
    @OptIn(UnstableApi::class)
    fun reasonOf(error: Throwable?): String? {
        val refused = generateSequence(error) { it.cause }.take(10)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull() ?: return null
        val body = refused.responseBody
        if (body.isEmpty()) return null
        return runCatching {
            org.json.JSONObject(String(body, Charsets.UTF_8)).getJSONObject("error").getJSONArray("errors").getJSONObject(0).getString("reason")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Google's daily limit for a file. */
    fun isDailyLimit(reason: String?): Boolean = reason.equals("downloadQuotaExceeded", ignoreCase = true)

    /** Google limiting how fast requests come ("rateLimitExceeded", "userRateLimitExceeded"): it passes by itself. */
    fun isRateLimit(reason: String?): Boolean = reason?.contains("ratelimit", ignoreCase = true) == true

    /**
     * Whether Drive's refusal can be of the token it was given, so a new one is worth asking for: a 401,
     * or a 403 that isn't one of Google's limits. Google sometimes stops taking a token before it runs
     * out and says so with a 403: offered it again, it refused every try until the token ran out by the
     * TV's clock, up to an hour of "Google Drive turned this down".
     */
    fun tokenRefused(http: Int?, reason: String?): Boolean =
        http == 401 || (http == 403 && !isDailyLimit(reason) && !isRateLimit(reason))

    /** Whether the TV ran out of memory anywhere in [error]'s causes. */
    fun outOfMemory(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(10).any { it is OutOfMemoryError }

    private val DECODER_ERRORS = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )

    private val CONTAINER_ERRORS = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    )
}
