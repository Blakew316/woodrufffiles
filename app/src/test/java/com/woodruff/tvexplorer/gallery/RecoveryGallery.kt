package com.woodruff.tvexplorer.gallery

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.BuildConfig
import com.woodruff.tvexplorer.update.RecoveryView
import com.woodruff.tvexplorer.update.Updater
import com.woodruff.tvexplorer.update.Updater.State
import com.woodruff.tvexplorer.update.Updater.Step
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The recovery screen, after a version closed twice in a row while starting: as it opens (looking for
 * a fix, Checking… focused), with a fix ready (Install the fix focused), and with What happened open
 * on a sample crash. It's always dark, so the same picture goes under both names. The fix shown is the
 * build after this one, so the numbers on screen agree. Only runs with `-Pgallery`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class RecoveryGallery {
    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }

    @After fun restore() {
        RecoveryView.resetForTest()
        Updater.resetForTest()
    }

    @Test fun drawRecovery() {
        RecoveryView.lookForFix = { _, _ -> }
        RecoveryView.load = { it.run() }
        Updater.showForTest(State(Step.CHECKING))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        File(activity.filesDir, "early_crash_report.txt").writeText(SAMPLE)
        RecoveryView.show(activity)
        settle()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        save(root, "recovery")

        val fix = (BuildConfig.VERSION_NAME.substringAfterLast('.').toIntOrNull() ?: 0) + 1
        Updater.showForTest(State(Step.READY, fix))
        settle()
        save(root, "recovery-ready")

        // What happened, on a TV with no fix out yet.
        Updater.showForTest(State(Step.UP_TO_DATE, fix - 1))
        for (key in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)) {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            settle()
        }
        save(root, "recovery-details")
        File(activity.filesDir, "early_crash_report.txt").delete()
    }

    /** Let the focus grow (150 ms) and every frame settle. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
    }

    private fun save(root: View, name: String) {
        for (scheme in listOf("dark", "light")) root.captureRoboImage(File(out, "tv-$name-$scheme.png").path)
    }

    private companion object {
        val SAMPLE = """
            Version ${BuildConfig.VERSION_NAME} · Nov 2, 9:41 PM · thread main
            java.lang.IllegalStateException: Camera list changed while it was drawn
            	at androidx.compose.runtime.ComposerImpl.apply(Composer.kt:4203)
            	at androidx.compose.runtime.ComposerImpl.doCompose(Composer.kt:3712)
            	at com.woodruff.tvexplorer.ui.screens.HomeScreenKt.CameraPanel(HomeScreen.kt:612)
            	at com.woodruff.tvexplorer.ui.screens.HomeScreenKt.access${'$'}CameraPanel(HomeScreen.kt:1)
            	at androidx.compose.runtime.internal.ComposableLambdaImpl.invoke(ComposableLambda.kt:109)
            	at com.woodruff.tvexplorer.ui.screens.HomeScreenKt${'$'}HomeScreen${'$'}2${'$'}1${'$'}3.invoke(HomeScreen.kt:240)
            	at android.view.Choreographer.doFrame(Choreographer.java:1018)
            Caused by: java.util.ConcurrentModificationException
            	at java.util.ArrayList${'$'}Itr.next(ArrayList.java:1052)
            	at com.woodruff.tvexplorer.nest.Nest.cameraNames(Nest.kt:233)
            	... 14 more
        """.trimIndent()
    }
}
