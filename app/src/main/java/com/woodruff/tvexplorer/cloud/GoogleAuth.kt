package com.woodruff.tvexplorer.cloud

import android.content.Context
import android.util.Base64
import com.woodruff.tvexplorer.settings.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google sign-in for Drive, done on the user's phone.
 *
 * Why not Play services sign-in: Google won't show its consent screen on many TVs (it fails with
 * "Error 400: invalid_request"), and the TV "device code" flow doesn't allow reading existing
 * Drive files. So the TV shows a QR code; the phone signs in with Google; Google redirects to a
 * tiny static page on GitHub Pages ([REDIRECT_URI], from docs/oauth.html), which forwards the
 * one-time code over the LAN to the phone-connection server on the TV (see CompanionRoutes),
 * which swaps it for tokens.
 *
 * Uses the owner's own "Web application" OAuth client. The client ID/secret are entered once
 * from the phone and stored with the tokens in this app's private storage.
 */
object GoogleAuth {
    const val REDIRECT_URI = "https://blakew316.github.io/woodrufffiles/oauth.html"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    // Everything lives encrypted in SecretStore (see Settings). Key names:
    const val K_CLIENT_ID = "google.client_id"
    const val K_CLIENT_SECRET = "google.client_secret"
    const val K_NEST_PROJECT = "nest.project_id"

    /**
     * Drive's hidden app folder, asked for with the Drive sign-in: only this app sees it, and it's
     * the one place in Drive the app can write (Continue watching shared between TVs, [FamilySync]).
     */
    const val APPDATA = "https://www.googleapis.com/auth/drive.appdata"

    /**
     * What a sign-in grants. Each has its own tokens, so connecting cameras never touches the
     * Drive sign-in and vice versa. Both use the same OAuth client.
     */
    enum class Grant(prefix: String, val scope: String) {
        // The family's files stay read-only; appdata is the app's own hidden folder (see APPDATA).
        DRIVE("google", "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/drive.appdata"),
        /** Google Home / Nest cameras through Device Access (Smart Device Management API). */
        // Pub/Sub too, for doorbell rings (they only arrive as Pub/Sub events).
        NEST("nest", "https://www.googleapis.com/auth/sdm.service https://www.googleapis.com/auth/pubsub"),
        /** Google Photos Picker: only the photos the owner picks, one selection at a time. */
        PHOTOS("photos", "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"),
        /** Read-only Google Calendar, for Today's Activities. */
        CALENDAR("calendar", "https://www.googleapis.com/auth/calendar.readonly");

        val refreshKey = "$prefix.refresh"
        val accessKey = "$prefix.access"
        val expiresKey = "$prefix.expires"
        /** The scopes Google actually granted (its consent screen lets each one be unticked). */
        val scopesKey = "$prefix.scopes"
        /** Everything stored for this sign-in, removed together when it ends. */
        val stored: Array<String> get() = arrayOf(refreshKey, accessKey, expiresKey, scopesKey)
    }

    private val K_REFRESH = Grant.DRIVE.refreshKey
    private fun allTokens() = Grant.entries.flatMap { it.stored.toList() }.toTypedArray()

    private fun s(c: Context) = SecretStore.get(c)

    private val _driveSignOuts = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /**
     * The Drive sign-in was ended on this TV: signed out, or the keys changed or removed (from the
     * Drive screen, Settings or the phone), so the Drive library forgets that account. Not when
     * Google ends it (an approval that expired): the same account usually signs back in then.
     */
    val driveSignOuts: SharedFlow<Unit> = _driveSignOuts.asSharedFlow()

    private val _driveSignIns = MutableStateFlow(0)
    /** Counts Drive sign-ins finished on this TV, so a screen showing the QR code knows when it's done. */
    val driveSignIns: StateFlow<Int> = _driveSignIns.asStateFlow()

    /** One-time move from the 1.2–1.4 plain prefs into encrypted storage, so nobody re-enters keys. */
    fun migrate(c: Context) {
        val old = c.getSharedPreferences("google_oauth", Context.MODE_PRIVATE)
        if (old.all.isEmpty()) return
        val st = s(c)
        old.getString("client_id", null)?.let { st.put(K_CLIENT_ID, it) }
        old.getString("client_secret", null)?.let { st.put(K_CLIENT_SECRET, it) }
        old.getString("refresh", null)?.let { st.put(K_REFRESH, it) }
        old.edit().clear().apply()
    }

    fun isConfigured(c: Context) = !clientId(c).isNullOrBlank() && !s(c).get(K_CLIENT_SECRET).isNullOrBlank()
    fun isConnected(c: Context, grant: Grant = Grant.DRIVE) = s(c).get(grant.refreshKey) != null
    fun clientId(c: Context): String? = s(c).get(K_CLIENT_ID)
    fun clientSecret(c: Context): String? = s(c).get(K_CLIENT_SECRET)

    /**
     * Whether Google granted [scope] with [grant]'s sign-in. False when it isn't known: a sign-in
     * from before the scopes were kept counts once its next token arrives (within the hour).
     */
    fun hasScope(c: Context, scope: String, grant: Grant = Grant.DRIVE): Boolean =
        s(c).get(grant.scopesKey)?.split(' ')?.contains(scope) == true

    /** Device Access project ID (a UUID from console.nest.google.com/device-access). */
    fun nestProject(c: Context): String? = s(c).get(K_NEST_PROJECT)?.takeIf { it.isNotBlank() }

    fun saveNestProject(c: Context, id: String?) {
        val clean = id?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) require(Regex("[0-9a-fA-F-]{20,64}").matches(clean)) { "The Project ID looks like 1a2b3c4d-…, from the Device Access Console" }
        if (clean != nestProject(c)) s(c).remove(*Grant.NEST.stored)
        s(c).put(K_NEST_PROJECT, clean)
    }

    fun saveClient(c: Context, id: String, secret: String) {
        val st = s(c)
        val changed = st.get(K_CLIENT_ID) != id.trim() || st.get(K_CLIENT_SECRET) != secret.trim()
        st.put(K_CLIENT_ID, id.trim())
        st.put(K_CLIENT_SECRET, secret.trim())
        // Tokens belong to a client; a different client means signing in again.
        if (changed) {
            st.remove(*allTokens())
            _driveSignOuts.tryEmit(Unit)
        }
    }

    /** Separate setters for editing one value at a time on the TV. */
    fun saveClientId(c: Context, id: String) = saveClient(c, id, s(c).get(K_CLIENT_SECRET).orEmpty())
    fun saveClientSecret(c: Context, secret: String) = saveClient(c, clientId(c).orEmpty(), secret)

    /**
     * A sign-in attempt: [state] ties the phone's callback to this TV; [verifier] is the PKCE secret (null = no PKCE).
     * [fromPhone]: started from this TV's own phone app (its sign-in sheet), not from a QR code on the TV. Its code
     * is swapped for tokens in the background ([com.woodruff.tvexplorer.server.PhoneSignIn]), so no screen has to be
     * open on the TV, and the page Google's answer lands on points back to the phone.
     */
    data class Attempt(val state: String, val verifier: String?, val authUrl: String, val grant: Grant = Grant.DRIVE, val fromPhone: Boolean = false)

    /**
     * [tvBase] is where the phone should send the code back, e.g. "http://192.168.1.20:8091".
     * It travels inside `state`, which the relay page reads. [fromPhone]: see [Attempt.fromPhone].
     */
    fun newAttempt(c: Context, tvBase: String, grant: Grant = Grant.DRIVE, fromPhone: Boolean = false): Attempt {
        val nonce = random(24)
        val state = b64(JSONObject().put("r", tvBase).put("n", nonce).toString().toByteArray())
        val common = arrayOf(
            "client_id" to (clientId(c) ?: ""),
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "scope" to grant.scope,
            "access_type" to "offline",
            "prompt" to "consent",
            "state" to state,
        )
        if (grant == Grant.NEST) {
            // Device Access sign-in goes through Google's "Partner Connections" page, where the
            // owner also picks which home and cameras to share. It's a confidential-client flow
            // (client secret), without PKCE.
            val project = nestProject(c) ?: throw IOException("Enter the Device Access Project ID first")
            return Attempt(state, null, "https://nestservices.google.com/partnerconnections/$project/auth?" + form(*common), grant, fromPhone)
        }
        val verifier = random(64)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = "https://accounts.google.com/o/oauth2/v2/auth?" + form(
            *common,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )
        return Attempt(state, verifier, url, grant, fromPhone)
    }

    /** Swaps the one-time code for tokens and stores them. */
    suspend fun exchange(c: Context, code: String, attempt: Attempt) {
        withContext(Dispatchers.IO) {
            val st = s(c)
            val params = mutableListOf(
                "code" to code,
                "client_id" to (st.get(K_CLIENT_ID) ?: throw IOException("Google isn't set up")),
                "client_secret" to (st.get(K_CLIENT_SECRET) ?: ""),
                "redirect_uri" to REDIRECT_URI,
                "grant_type" to "authorization_code",
            )
            attempt.verifier?.let { params += "code_verifier" to it }
            val json = post(params)
            val refresh = json.optString("refresh_token").ifEmpty { throw IOException("Google didn't return a refresh token. Try signing in again.") }
            // Under the grant's lock: a renewal of the old sign-in that's under way can't end this new one.
            synchronized(lock(attempt.grant)) {
                storeAccess(Stored(st), json, attempt.grant, System.currentTimeMillis())
                // Google always says what it granted; without that, take it as what was asked for.
                if (json.optString("scope").isBlank()) st.put(attempt.grant.scopesKey, attempt.grant.scope)
                st.put(attempt.grant.refreshKey, refresh)
            }
        }
        if (attempt.grant == Grant.DRIVE) {
            _driveSignIns.value = _driveSignIns.value + 1
            // Now with the shared folder, usually: swap progress with the other TVs.
            FamilySync.signedIn(c)
        }
    }

    /**
     * What asking for a sign-in's access token came to. A renewal that can't get through to Google
     * (the Wi-Fi still reconnecting after standby, a name that won't resolve, a timeout, Google's
     * own outage) is [Offline] and keeps the sign-in: it works again as soon as Google answers. Only
     * Google saying the approval has ended makes it [SignedOut], and only that should ask the family
     * to sign in again.
     */
    sealed interface TokenResult {
        /** A valid access token. */
        data class Ok(val token: String) : TokenResult

        /**
         * No sign-in for this grant on this TV: never made, signed out here, or ended by Google
         * (the 7-day expiry of a consent screen in "Testing", or access revoked in the Google
         * account). Signing in again is the fix.
         */
        data object SignedOut : TokenResult

        /**
         * Couldn't get through to Google just now. The sign-in is kept: try again later, at once
         * when the network comes back ([com.woodruff.tvexplorer.util.NetworkWatch]).
         */
        data class Offline(val cause: Throwable) : TokenResult

        /**
         * Google answered, but won't renew it for a reason signing in again doesn't fix: the
         * Client ID or secret were changed or deleted in Google Cloud, or aren't on this TV.
         * [message] says what to do, in the family's words.
         */
        data class Rejected(val message: String) : TokenResult
    }

    /**
     * [TokenResult] as an exception, for code that reports problems by throwing. All are
     * IOExceptions, as the API clients' own failures are; [Offline] is a
     * [com.woodruff.tvexplorer.util.NetworkFailure], so
     * [com.woodruff.tvexplorer.util.isNetworkFailure] tells it apart from a sign-in that ended.
     */
    sealed class AuthException(message: String, cause: Throwable? = null) : IOException(message, cause) {
        /** See [TokenResult.SignedOut]; the message is the caller's ("Connect your cameras again"). */
        class SignedOut(message: String) : AuthException(message)
        /** See [TokenResult.Offline]. */
        class Offline(cause: Throwable) : AuthException("Can't reach Google right now.", cause), com.woodruff.tvexplorer.util.NetworkFailure
        /** See [TokenResult.Rejected]. */
        class Rejected(message: String) : AuthException(message)
    }

    private val _expirations = MutableSharedFlow<Grant>(extraBufferCapacity = 8)
    /**
     * A sign-in Google has ended (expired or revoked), as soon as renewing it finds out: the screens
     * using it ask to sign in again. Never for a network failure, and not for signing out on the TV
     * (see [driveSignOuts]).
     */
    val expirations: SharedFlow<Grant> = _expirations.asSharedFlow()

    /** A valid access token, refreshing it when it's about to expire. Null when there's none just now; [tokenResult] says why. */
    suspend fun token(c: Context, grant: Grant = Grant.DRIVE): String? = (tokenResult(c, grant) as? TokenResult.Ok)?.token

    /** Same as [token] for background threads (the video player's data source). */
    fun blockingToken(c: Context, grant: Grant = Grant.DRIVE): String? = (blockingTokenResult(c, grant) as? TokenResult.Ok)?.token

    /** A valid access token, or why there isn't one (see [TokenResult]). */
    suspend fun tokenResult(c: Context, grant: Grant = Grant.DRIVE): TokenResult = withContext(Dispatchers.IO) { blockingTokenResult(c, grant) }

    /**
     * Same as [tokenResult] for background threads. A token that's still good is handed out without
     * waiting on anything (the player asks for one on every Drive request); a renewal holds only its
     * own grant's lock, so the doorbell renewing the cameras' sign-in never holds up a film.
     */
    fun blockingTokenResult(c: Context, grant: Grant = Grant.DRIVE): TokenResult {
        val store = Stored(s(c))
        val result = current(store, grant, System.currentTimeMillis())?.let { TokenResult.Ok(it) }
            ?: synchronized(lock(grant)) { refresh(store, grant, System.currentTimeMillis(), ::post) }
        lastAsks[grant] = LastAsk(result is TokenResult.Offline, result is TokenResult.Rejected, System.currentTimeMillis(),
            com.woodruff.tvexplorer.util.NetworkWatch.online.value)
        return result
    }

    /**
     * How the last ask for a sign-in's token went: whether Google couldn't be reached ([offline],
     * with whether the TV had a network then: [networkUp]) or turned down the keys ([rejected]),
     * and when. The TV health report tells "Can't reach Google" and "Keys not accepted" by it.
     */
    data class LastAsk(val offline: Boolean, val rejected: Boolean, val at: Long, val networkUp: Boolean)

    private val lastAsks = java.util.concurrent.ConcurrentHashMap<Grant, LastAsk>()

    /** How the last ask for [grant]'s token went in this run of the app; null before the first. */
    fun lastAsk(grant: Grant): LastAsk? = lastAsks[grant]

    /**
     * [grant]'s access token, or an [AuthException] saying why not: [signedOut] is the message for a
     * sign-in that has ended ("Connect your cameras again"). Blocking: for background threads.
     */
    fun requireToken(c: Context, grant: Grant, signedOut: String): String = when (val r = blockingTokenResult(c, grant)) {
        is TokenResult.Ok -> r.token
        TokenResult.SignedOut -> throw AuthException.SignedOut(signedOut)
        is TokenResult.Offline -> throw AuthException.Offline(r.cause)
        is TokenResult.Rejected -> throw AuthException.Rejected(r.message)
    }

    /**
     * Drops [grant]'s saved access token, so the next request renews it. For an API that answered
     * 401 to a token that looked good: the TV's clock was off when it was saved, or access was just
     * revoked (renewing then finds that out and ends the sign-in).
     */
    fun forgetAccess(c: Context, grant: Grant = Grant.DRIVE) {
        synchronized(lock(grant)) { s(c).remove(grant.accessKey, grant.expiresKey) }
    }

    /** One lock per grant: renewing one sign-in never waits on another's. */
    private val locks = Grant.entries.associateWith { Any() }
    private fun lock(grant: Grant): Any = locks.getValue(grant)

    /** Where tokens are kept: the encrypted [SecretStore] (a plain map in tests). */
    internal interface Tokens {
        fun get(name: String): String?
        fun put(name: String, value: String)
        fun remove(vararg names: String)
    }

    private class Stored(private val st: SecretStore) : Tokens {
        override fun get(name: String) = st.get(name)
        override fun put(name: String, value: String) = st.put(name, value)
        override fun remove(vararg names: String) = st.remove(*names)
    }

    /** The saved access token while it has more than a minute left; null when it needs renewing, or there's no sign-in. */
    private fun current(store: Tokens, grant: Grant, now: Long): String? {
        store.get(grant.refreshKey) ?: return null
        val access = store.get(grant.accessKey) ?: return null
        val expires = store.get(grant.expiresKey)?.toLongOrNull() ?: 0L
        return access.takeIf { now < expires - 60_000 }
    }

    /**
     * Renews [grant]'s access token through [call] (the token endpoint) and keeps it in [store]. Only
     * Google refusing the refresh token itself (invalid_grant) ends the sign-in: anything that keeps
     * the answer from arriving leaves it as it is, to work again once Google can be reached. Called
     * under the grant's lock.
     */
    internal fun refresh(store: Tokens, grant: Grant, now: Long, call: (List<Pair<String, String>>) -> JSONObject): TokenResult {
        val refresh = store.get(grant.refreshKey) ?: return TokenResult.SignedOut
        // Renewed by another thread while this one waited for the lock.
        current(store, grant, now)?.let { return TokenResult.Ok(it) }
        val clientId = store.get(K_CLIENT_ID)?.takeIf { it.isNotBlank() } ?: return TokenResult.Rejected(NOT_SET_UP)
        val json = try {
            call(listOf("client_id" to clientId, "client_secret" to (store.get(K_CLIENT_SECRET) ?: ""), "refresh_token" to refresh, "grant_type" to "refresh_token"))
        } catch (e: TokenError) {
            return when (e.kind) {
                TokenError.Kind.REVOKED -> {
                    // The approval expired (the 7-day limit in Testing mode) or was revoked: sign in
                    // again. Unless a new sign-in has replaced this one meanwhile.
                    if (store.get(grant.refreshKey) == refresh) {
                        store.remove(*grant.stored)
                        _expirations.tryEmit(grant)
                    }
                    TokenResult.SignedOut
                }
                TokenError.Kind.BAD_CLIENT, TokenError.Kind.OTHER -> TokenResult.Rejected(e.message ?: BAD_CLIENT)
                TokenError.Kind.NETWORK, TokenError.Kind.UNAVAILABLE -> TokenResult.Offline(e)
            }
        } catch (e: IOException) {
            // No answer came back at all.
            return TokenResult.Offline(e)
        }
        // A 200 without a token: no use, but nothing ended either.
        val access = json.optString("access_token").ifEmpty { return TokenResult.Offline(IOException("Google's answer had no access token")) }
        // Not for a sign-in that ended (or was replaced) while this was under way. A Keystore hiccup
        // saving it costs a renewal next time, not this token.
        if (store.get(grant.refreshKey) == refresh) {
            runCatching { storeAccess(store, json, grant, now) }.onFailure { android.util.Log.w("GoogleAuth", "couldn't save the access token", it) }
        }
        return TokenResult.Ok(access)
    }

    /** Forgets tokens on this TV and asks Google to revoke them. Keeps the client ID/secret. */
    suspend fun signOut(c: Context, grant: Grant = Grant.DRIVE) = withContext(Dispatchers.IO) {
        val refresh = s(c).get(grant.refreshKey)
        s(c).remove(*grant.stored)
        if (grant == Grant.DRIVE) _driveSignOuts.tryEmit(Unit)
        if (refresh != null) runCatching {
            (URL("https://oauth2.googleapis.com/revoke?token=${enc(refresh)}").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; outputStream.close(); responseCode; disconnect()
            }
        }
    }

    fun resetClient(c: Context) {
        s(c).remove(K_CLIENT_ID, K_CLIENT_SECRET, *allTokens())
        _driveSignOuts.tryEmit(Unit)
    }

    private fun storeAccess(store: Tokens, json: JSONObject, grant: Grant, now: Long) {
        store.put(grant.accessKey, json.getString("access_token"))
        store.put(grant.expiresKey, (now + json.optLong("expires_in", 3600) * 1000).toString())
        // What it's good for, from the sign-in and every refresh (an unchanged value isn't written again).
        json.optString("scope").takeIf { it.isNotBlank() }?.let { store.put(grant.scopesKey, it) }
    }

    /** No Client ID on this TV, though a sign-in is: the keys were removed, or can't be read. */
    private const val NOT_SET_UP = "Google isn't set up on this TV. Add the Client ID and secret in Settings → Accounts → Keys & sign-out."
    private const val BAD_CLIENT = "Google didn't accept this TV's Client ID or secret. Check them in Settings → Accounts → Keys & sign-out."

    /** A request to the token endpoint that didn't bring tokens, and which [kind] of failure it was. */
    internal class TokenError(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
        enum class Kind {
            /** No answer at all: no network yet, a name that won't resolve, a timeout, a dropped connection. */
            NETWORK,
            /** Google answered that it's busy or failing (5xx, 429, 408). */
            UNAVAILABLE,
            /** Google refused the refresh token or code: the approval expired or was revoked. */
            REVOKED,
            /** Google refused the OAuth client: a wrong, changed or deleted Client ID or secret. */
            BAD_CLIENT,
            /** Anything else Google refused. */
            OTHER,
        }
    }

    /** Which kind of failure an error answer from the token endpoint is, from its HTTP [code] and OAuth [error]. */
    internal fun tokenErrorKind(code: Int, error: String): TokenError.Kind = when {
        error == "invalid_grant" -> TokenError.Kind.REVOKED
        error == "invalid_client" || error == "unauthorized_client" -> TokenError.Kind.BAD_CLIENT
        code == 408 || code == 429 || code >= 500 -> TokenError.Kind.UNAVAILABLE
        else -> TokenError.Kind.OTHER
    }

    /** One request to Google's token endpoint; anything but tokens is a [TokenError]. */
    private fun post(params: List<Pair<String, String>>): JSONObject {
        val conn = try {
            (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        } catch (e: IOException) {
            throw TokenError(TokenError.Kind.NETWORK, NO_ANSWER, e)
        }
        try {
            val code: Int
            val body: String
            try {
                conn.outputStream.use { it.write(form(params).toByteArray()) }
                code = conn.responseCode
                body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (e: IOException) {
                // Whatever the network did, Google never answered: nothing about the sign-in changed.
                throw TokenError(TokenError.Kind.NETWORK, NO_ANSWER, e)
            }
            val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            if (code in 200..299) return json
            val err = json.optString("error")
            val desc = json.optString("error_description").ifEmpty { err.ifEmpty { "HTTP $code" } }
            val kind = tokenErrorKind(code, err)
            throw TokenError(
                kind,
                when (kind) {
                    TokenError.Kind.BAD_CLIENT -> BAD_CLIENT
                    TokenError.Kind.UNAVAILABLE -> "Google's sign-in service is busy. Try again in a moment."
                    else -> "Google sign-in failed: $desc"
                },
            )
        } finally {
            conn.disconnect()
        }
    }

    private const val NO_ANSWER = "Couldn't reach Google. Check the TV's internet connection and try again."

    private fun form(vararg params: Pair<String, String>) = form(params.asList())
    private fun form(params: List<Pair<String, String>>) = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private fun random(n: Int): String {
        val r = SecureRandom()
        return (1..n).map { CHARS[r.nextInt(CHARS.length)] }.joinToString("")
    }
}
