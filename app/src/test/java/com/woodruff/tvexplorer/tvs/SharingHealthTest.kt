package com.woodruff.tvexplorer.tvs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.GoogleAuth.Grant
import com.woodruff.tvexplorer.server.CompanionRoutes
import com.woodruff.tvexplorer.server.answerFor
import com.woodruff.tvexplorer.server.bodyText
import com.woodruff.tvexplorer.server.phoneRequest
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.StandInKeystore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A Drive sign-in that doesn't let this TV use the family's shared folder: the health report (the TV's, the hub's list,
 * the phone's TV page) says so once Google has said it (the scopes it granted are kept without it), never on a guess,
 * and the phone's Connections says what the TV's Settings → Accounts says. Over the real encrypted store.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SharingHealthTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val secrets = StandInKeystore(app)

    @After fun putBack() {
        secrets.putBack()
        Health.forget()
    }

    /** Drive signed in, with the scopes Google granted kept as [scopes] (null: from before they were kept). */
    private fun signedIn(scopes: String?) = SecretStore.get(app).run {
        put(GoogleAuth.K_CLIENT_ID, "123-abc.apps.googleusercontent.com")
        put(GoogleAuth.K_CLIENT_SECRET, "secret")
        put(Grant.DRIVE.refreshKey, "refresh-drive")
        if (scopes != null) put(Grant.DRIVE.scopesKey, scopes)
    }

    private fun shareItem() = Health.report(app, fresh = true).attention.firstOrNull { it.id == "signin.share" }

    private fun google(): JSONObject =
        JSONObject(routes.answerFor(phoneRequest("/api/settings")).bodyText()).getJSONObject("google")

    private val routes by lazy { CompanionRoutes(app) }

    @Test fun aSignInWithoutTheSharedFolderNeedsAttention() {
        secrets.use()
        // The box for the shared folder unticked on Google's page: only the files were granted.
        signedIn("https://www.googleapis.com/auth/drive.readonly")
        assertTrue(FamilySync.sharingRefused(app))
        val item = shareItem()!!
        assertEquals("signin", item.fix)
        assertEquals("drive", item.grant)
        // Drive's own row is as it was: signed in (or offline, as a test's TV is), nothing for a person to fix there.
        assertFalse(Health.report(app, fresh = true).signIns.getValue(HealthGrant.DRIVE).attention)
        // The phone's Connections: the TV's Settings words, and Sign In Again.
        val g = google()
        assertEquals("Sign in to Google Drive again to share progress between TVs.", g.getString("share"))
        assertTrue(g.getBoolean("shareSignIn"))
    }

    @Test fun aSignInWithTheSharedFolderIsAllGood() {
        secrets.use()
        signedIn(Grant.DRIVE.scope)
        assertFalse(FamilySync.sharingRefused(app))
        assertEquals(null, shareItem())
        val g = google()
        assertFalse(g.getBoolean("shareSignIn"))
    }

    @Test fun notWhileItIsntKnown() {
        secrets.use()
        // From before the scopes were kept: its next token says, so the report doesn't guess.
        signedIn(null)
        assertFalse(FamilySync.sharingRefused(app))
        assertEquals(null, shareItem())
    }

    @Test fun norWithSharingTurnedOff() {
        secrets.use()
        signedIn("https://www.googleapis.com/auth/drive.readonly")
        // (Each test starts with its settings as new: on again for the next.)
        FamilySync.setEnabled(app, false)
        assertFalse(FamilySync.sharingRefused(app))
        assertEquals(null, shareItem())
        assertEquals("", google().getString("share"))
    }
}
