package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.screens.SettingsAt
import com.woodruff.tvexplorer.ui.screens.openSettingsAt
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Settings › Accounts › Keys & sign-out, dark and light: with the keys saved, and the same TV just after a power cut
 * while its Keystore is still waking, when the saved keys can't be read yet. Then they read "Saved" (never "Not set"),
 * and OK on one says to try again in a moment rather than offer to type it again, which would end every sign-in. Once
 * it wakes, the page shows the keys at once. Over the real encrypted store and a stand-in Keystore.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class KeysGallery {
    @get:Rule val compose = createComposeRule()

    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** The TV's Keystore: has the key, but says it has none while [missing] (just after the TV turned on). */
    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        @Volatile var missing = false
        override fun find(): SecretKey? = if (missing) null else key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private val vault = Vault()
    private var swapped = false
    private var before: SecretStore? = null

    private fun store() = SecretStore(app, vault, onMainThread = { true }, clock = { 0L }, pause = {})

    @After fun reset() {
        if (swapped) SecretStore.swapForTest(before)
        leaveNoSecrets(app)
        Palette.light = false
    }

    /** This TV set up with sample keys (none real), read by the app from a store over [vault]. */
    private fun keysSaved(waking: Boolean) {
        store().apply {
            put(GoogleAuth.K_CLIENT_ID, "123456789012-sample.apps.googleusercontent.com")
            put(GoogleAuth.K_CLIENT_SECRET, "sample-secret")
            put(GoogleAuth.K_NEST_PROJECT, "1a2b3c4d-1111-2222-3333-444455556666")
            put("tmdb.key", "sample-tmdb-key")
        }
        vault.missing = waking
        before = SecretStore.swapForTest(store())
        swapped = true
    }

    @Test fun drawKeys() = draw(waking = false, "tv-settings-keys")

    @Test fun drawKeysWhileTheKeystoreWakes() = draw(waking = true, "tv-settings-keys-waking")

    private fun draw(waking: Boolean, name: String) {
        keysSaved(waking)
        val vm = MainViewModel(app)
        openSettingsAt(vm, SettingsAt.KEYS)
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        compose.mainClock.advanceTimeBy(1_500)
        for (light in listOf(false, true)) {
            compose.runOnIdle { Palette.light = light }
            settle()
            capture(name, light)
        }
        if (!waking) return
        // OK on the Client ID: what it says, instead of a box to type the key in again (pressed in each theme, as
        // the words go after a few seconds).
        for (light in listOf(false, true)) {
            compose.runOnIdle { Palette.light = light; vm.dismissMessage() }
            settle()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            settle(times = 8)
            capture("$name-ok", light)
        }
        // The Keystore wakes: the page shows the keys at once, without being left and opened again.
        vault.missing = false
        compose.runOnIdle { vm.dismissMessage() }
        SecretStore.keyBack(app)
        settle()
        assertTrue("the Client ID is shown", compose.onAllNodesWithText("123456789012-sampl…").fetchSemanticsNodes().isNotEmpty())
    }

    private fun capture(name: String, light: Boolean) {
        compose.onRoot().captureRoboImage(File(out, "$name-${if (light) "light" else "dark"}.png").path)
    }

    /** Let recomposition, focus, fades and frames run after each change. */
    private fun settle(times: Int = 25) {
        repeat(times) {
            Thread.sleep(60)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }
}
