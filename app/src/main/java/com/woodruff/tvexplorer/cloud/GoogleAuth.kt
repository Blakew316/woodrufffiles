package com.woodruff.tvexplorer.cloud

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google sign-in for Drive, via Google Play services' AuthorizationClient. No client ID is
 * compiled in: Google matches this app's package name + signing-key SHA-1 to the OAuth client
 * the owner registered in their Google Cloud project (see README).
 *
 * Play services caches and refreshes the access token, so asking again is cheap and silent
 * once the user has agreed.
 */
object GoogleAuth {
    private val SCOPES = listOf(Scope("https://www.googleapis.com/auth/drive.readonly"))
    private const val PREFS = "google"

    sealed interface Outcome {
        data class Token(val accessToken: String) : Outcome
        /** The user must see Google's consent screen; launch this, then call [fromResultIntent]. */
        data class NeedsConsent(val intent: PendingIntent) : Outcome
    }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(SCOPES).build()

    suspend fun authorize(context: Context): Outcome {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        return toOutcome(result)
    }

    fun fromResultIntent(context: Context, data: Intent?): Outcome {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        return toOutcome(result)
    }

    /** For background threads (e.g. the video player's data source). Null if consent is needed. */
    fun blockingToken(context: Context): String? = runCatching {
        val r = Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 20, TimeUnit.SECONDS)
        if (r.hasResolution()) null else r.accessToken
    }.getOrNull()

    suspend fun token(context: Context): String? = runCatching {
        (authorize(context) as? Outcome.Token)?.accessToken
    }.getOrNull()

    /** Whether the user has connected Drive in this app (they can hide it again with Disconnect). */
    fun isConnected(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("connected", false)

    fun setConnected(context: Context, connected: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("connected", connected).apply()

    private fun toOutcome(r: AuthorizationResult): Outcome {
        val pending = r.pendingIntent
        if (r.hasResolution() && pending != null) return Outcome.NeedsConsent(pending)
        return Outcome.Token(r.accessToken ?: throw IllegalStateException("Google returned no access token"))
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

}
