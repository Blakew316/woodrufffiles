package com.woodruff.tvexplorer.cloud

import android.content.Context
import android.util.Base64
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
 * one-time code over the LAN to [SignInServer] on the TV. The TV then swaps it for tokens.
 *
 * Uses the owner's own "Web application" OAuth client. The client ID/secret are entered once
 * from the phone and stored with the tokens in this app's private storage.
 */
object GoogleAuth {
    const val REDIRECT_URI = "https://blakew316.github.io/woodrufffiles/oauth.html"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val PREFS = "google_oauth"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfigured(c: Context) = prefs(c).getString("client_id", null) != null && prefs(c).getString("client_secret", null) != null
    fun isConnected(c: Context) = prefs(c).getString("refresh", null) != null
    fun clientId(c: Context): String? = prefs(c).getString("client_id", null)

    fun saveClient(c: Context, id: String, secret: String) {
        prefs(c).edit().putString("client_id", id.trim()).putString("client_secret", secret.trim()).remove("refresh").remove("access").apply()
    }

    /** A sign-in attempt: [state] ties the phone's callback to this TV; [verifier] is the PKCE secret. */
    data class Attempt(val state: String, val verifier: String, val authUrl: String)

    /**
     * [tvBase] is where the phone should send the code back, e.g. "http://192.168.1.20:8091".
     * It travels inside `state`, which the relay page reads.
     */
    fun newAttempt(c: Context, tvBase: String): Attempt {
        val nonce = random(24)
        val state = b64(JSONObject().put("r", tvBase).put("n", nonce).toString().toByteArray())
        val verifier = random(64)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = "https://accounts.google.com/o/oauth2/v2/auth?" + form(
            "client_id" to (clientId(c) ?: ""),
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "scope" to SCOPE,
            "access_type" to "offline",
            "prompt" to "consent",
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )
        return Attempt(state, verifier, url)
    }

    /** Swaps the one-time code for tokens and stores them. */
    suspend fun exchange(c: Context, code: String, attempt: Attempt) = withContext(Dispatchers.IO) {
        val p = prefs(c)
        val json = post(
            "code" to code,
            "client_id" to (p.getString("client_id", null) ?: throw IOException("Google Drive isn't set up")),
            "client_secret" to (p.getString("client_secret", null) ?: ""),
            "redirect_uri" to REDIRECT_URI,
            "grant_type" to "authorization_code",
            "code_verifier" to attempt.verifier,
        )
        val refresh = json.optString("refresh_token").ifEmpty { throw IOException("Google didn't return a refresh token. Try signing in again.") }
        storeAccess(c, json)
        p.edit().putString("refresh", refresh).apply()
    }

    /** A valid access token, refreshing it when it's about to expire. Null when signed out. */
    suspend fun token(c: Context): String? = withContext(Dispatchers.IO) { blockingToken(c) }

    /** Same as [token] for background threads (the video player's data source). */
    @Synchronized
    fun blockingToken(c: Context): String? {
        val p = prefs(c)
        val refresh = p.getString("refresh", null) ?: return null
        val access = p.getString("access", null)
        if (access != null && System.currentTimeMillis() < p.getLong("expires", 0) - 60_000) return access
        return try {
            val json = post(
                "client_id" to (p.getString("client_id", null) ?: return null),
                "client_secret" to (p.getString("client_secret", null) ?: ""),
                "refresh_token" to refresh,
                "grant_type" to "refresh_token",
            )
            storeAccess(c, json)
            json.getString("access_token")
        } catch (e: RevokedException) {
            // Approval expired (7-day limit in Testing mode) or was revoked: sign in again.
            p.edit().remove("refresh").remove("access").apply()
            null
        } catch (e: IOException) {
            null
        }
    }

    /** Forgets tokens on this TV and asks Google to revoke them. Keeps the client ID/secret. */
    suspend fun signOut(c: Context) = withContext(Dispatchers.IO) {
        val refresh = prefs(c).getString("refresh", null)
        prefs(c).edit().remove("refresh").remove("access").apply()
        if (refresh != null) runCatching {
            (URL("https://oauth2.googleapis.com/revoke?token=${enc(refresh)}").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; outputStream.close(); responseCode; disconnect()
            }
        }
    }

    fun resetClient(c: Context) = prefs(c).edit().clear().apply()

    private fun storeAccess(c: Context, json: JSONObject) {
        prefs(c).edit()
            .putString("access", json.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
            .apply()
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
