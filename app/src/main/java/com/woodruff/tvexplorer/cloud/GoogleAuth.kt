package com.woodruff.tvexplorer.cloud

import android.content.Context
import android.util.Base64
import com.woodruff.tvexplorer.settings.SecretStore
import kotlinx.coroutines.Dispatchers
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
     * What a sign-in grants. Each has its own tokens, so connecting cameras never touches the
     * Drive sign-in and vice versa. Both use the same OAuth client.
     */
    enum class Grant(prefix: String, val scope: String) {
        DRIVE("google", "https://www.googleapis.com/auth/drive.readonly"),
        /** Google Home / Nest cameras through Device Access (Smart Device Management API). */
        NEST("nest", "https://www.googleapis.com/auth/sdm.service"),
        /** Google Photos Picker: only the photos the owner picks, one selection at a time. */
        PHOTOS("photos", "https://www.googleapis.com/auth/photospicker.mediaitems.readonly");

        val refreshKey = "$prefix.refresh"
        val accessKey = "$prefix.access"
        val expiresKey = "$prefix.expires"
    }

    private val K_REFRESH = Grant.DRIVE.refreshKey
    private fun allTokens() = Grant.entries.flatMap { listOf(it.refreshKey, it.accessKey, it.expiresKey) }.toTypedArray()

    private fun s(c: Context) = SecretStore.get(c)

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

    /** Device Access project ID (a UUID from console.nest.google.com/device-access). */
    fun nestProject(c: Context): String? = s(c).get(K_NEST_PROJECT)?.takeIf { it.isNotBlank() }

    fun saveNestProject(c: Context, id: String?) {
        val clean = id?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) require(Regex("[0-9a-fA-F-]{20,64}").matches(clean)) { "The Project ID looks like 1a2b3c4d-…, from the Device Access Console" }
        if (clean != nestProject(c)) s(c).remove(Grant.NEST.refreshKey, Grant.NEST.accessKey, Grant.NEST.expiresKey)
        s(c).put(K_NEST_PROJECT, clean)
    }

    fun saveClient(c: Context, id: String, secret: String) {
        val st = s(c)
        val changed = st.get(K_CLIENT_ID) != id.trim() || st.get(K_CLIENT_SECRET) != secret.trim()
        st.put(K_CLIENT_ID, id.trim())
        st.put(K_CLIENT_SECRET, secret.trim())
        // Tokens belong to a client; a different client means signing in again.
        if (changed) st.remove(*allTokens())
    }

    /** Separate setters for editing one value at a time on the TV. */
    fun saveClientId(c: Context, id: String) = saveClient(c, id, s(c).get(K_CLIENT_SECRET).orEmpty())
    fun saveClientSecret(c: Context, secret: String) = saveClient(c, clientId(c).orEmpty(), secret)

    /** A sign-in attempt: [state] ties the phone's callback to this TV; [verifier] is the PKCE secret (null = no PKCE). */
    data class Attempt(val state: String, val verifier: String?, val authUrl: String, val grant: Grant = Grant.DRIVE)

    /**
     * [tvBase] is where the phone should send the code back, e.g. "http://192.168.1.20:8091".
     * It travels inside `state`, which the relay page reads.
     */
    fun newAttempt(c: Context, tvBase: String, grant: Grant = Grant.DRIVE): Attempt {
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
            return Attempt(state, null, "https://nestservices.google.com/partnerconnections/$project/auth?" + form(*common), grant)
        }
        val verifier = random(64)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = "https://accounts.google.com/o/oauth2/v2/auth?" + form(
            *common,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )
        return Attempt(state, verifier, url, grant)
    }

    /** Swaps the one-time code for tokens and stores them. */
    suspend fun exchange(c: Context, code: String, attempt: Attempt) = withContext(Dispatchers.IO) {
        val st = s(c)
        val params = mutableListOf(
            "code" to code,
            "client_id" to (st.get(K_CLIENT_ID) ?: throw IOException("Google isn't set up")),
            "client_secret" to (st.get(K_CLIENT_SECRET) ?: ""),
            "redirect_uri" to REDIRECT_URI,
            "grant_type" to "authorization_code",
        )
        attempt.verifier?.let { params += "code_verifier" to it }
        val json = post(*params.toTypedArray())
        val refresh = json.optString("refresh_token").ifEmpty { throw IOException("Google didn't return a refresh token. Try signing in again.") }
        storeAccess(c, json, attempt.grant)
        st.put(attempt.grant.refreshKey, refresh)
    }

    /** A valid access token, refreshing it when it's about to expire. Null when signed out. */
    suspend fun token(c: Context, grant: Grant = Grant.DRIVE): String? = withContext(Dispatchers.IO) { blockingToken(c, grant) }

    /** Same as [token] for background threads (the video player's data source). */
    @Synchronized
    fun blockingToken(c: Context, grant: Grant = Grant.DRIVE): String? {
        val st = s(c)
        val refresh = st.get(grant.refreshKey) ?: return null
        val access = st.get(grant.accessKey)
        val expires = st.get(grant.expiresKey)?.toLongOrNull() ?: 0L
        if (access != null && System.currentTimeMillis() < expires - 60_000) return access
        return try {
            val json = post(
                "client_id" to (st.get(K_CLIENT_ID) ?: return null),
                "client_secret" to (st.get(K_CLIENT_SECRET) ?: ""),
                "refresh_token" to refresh,
                "grant_type" to "refresh_token",
            )
            storeAccess(c, json, grant)
            json.getString("access_token")
        } catch (e: RevokedException) {
            // Approval expired (7-day limit in Testing mode) or was revoked: sign in again.
            st.remove(grant.refreshKey, grant.accessKey, grant.expiresKey)
            null
        } catch (e: IOException) {
            null
        }
    }

    /** Forgets tokens on this TV and asks Google to revoke them. Keeps the client ID/secret. */
    suspend fun signOut(c: Context, grant: Grant = Grant.DRIVE) = withContext(Dispatchers.IO) {
        val refresh = s(c).get(grant.refreshKey)
        s(c).remove(grant.refreshKey, grant.accessKey, grant.expiresKey)
        if (refresh != null) runCatching {
            (URL("https://oauth2.googleapis.com/revoke?token=${enc(refresh)}").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; outputStream.close(); responseCode; disconnect()
            }
        }
    }

    fun resetClient(c: Context) = s(c).remove(K_CLIENT_ID, K_CLIENT_SECRET, *allTokens())

    private fun storeAccess(c: Context, json: JSONObject, grant: Grant) {
        s(c).put(grant.accessKey, json.getString("access_token"))
        s(c).put(grant.expiresKey, (System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000).toString())
    }

    private class RevokedException(msg: String) : IOException(msg)

    private fun post(vararg params: Pair<String, String>): JSONObject {
        val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(form(*params).toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            if (code in 200..299) return json
            val err = json.optString("error")
            val desc = json.optString("error_description").ifEmpty { err.ifEmpty { "HTTP $code" } }
            if (err == "invalid_grant") throw RevokedException(desc)
            if (err == "invalid_client") throw IOException("Google rejected the client ID or secret. Re-enter them in Google Drive setup.")
            throw IOException("Google sign-in failed: $desc")
        } finally {
            conn.disconnect()
        }
    }

    private fun form(vararg params: Pair<String, String>) = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private fun random(n: Int): String {
        val r = SecureRandom()
        return (1..n).map { CHARS[r.nextInt(CHARS.length)] }.joinToString("")
    }
}
