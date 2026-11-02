package com.woodruff.tvexplorer.update

import android.app.Activity
import android.graphics.Typeface
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.update.Updater.State
import com.woodruff.tvexplorer.update.Updater.Step
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The recovery screen ([RecoveryView]) on a 960x540 dp TV: the spec's words for every step of the
 * update, the remote's moves, each pill's action, Back, the details, and how it holds up when its
 * fonts, its logo or even building it fails. Nothing here checks for updates or installs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class RecoveryViewTest {
    private val looks = mutableListOf<Boolean>()
    private var installs = 0
    private var left = 0
    private var reopened = 0
    private var blocked = false
    private lateinit var activity: Activity

    @Before fun standIns() {
        Updater.resetForTest()
        RecoveryView.lookForFix = { _, manual -> looks += manual }
        RecoveryView.install = { installs++ }
        RecoveryView.blockedByProfile = { blocked }
        RecoveryView.leave = { left++ }
        RecoveryView.tryAgain = { reopened++ }
        RecoveryView.load = { it.run() }
    }

    @After fun restore() {
        RecoveryView.resetForTest()
        Updater.resetForTest()
    }

    private fun open(state: State = State(Step.CHECKING)): RecoveryView {
        Updater.showForTest(state)
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        RecoveryView.show(activity)
        idle()
        return activity.findViewById<View>(android.R.id.content).let { (it as android.view.ViewGroup).getChildAt(0) } as RecoveryView
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun press(code: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        idle()
    }

    private fun show(state: State) {
        Updater.showForTest(state)
        idle()
    }

    @Test fun itOpensLookingForAFixWithTheFirstPillFocused() {
        val v = open()
        assertEquals("Looking for a fixed version…", v.statusView.text)
        assertEquals("Checking…", v.first.text)
        assertTrue(v.first.isFocused)
        assertEquals(RecoveryView.TITLE, v.titleView.text)
        assertEquals(RecoveryView.BODY, v.bodyView.text)
        // The check starts as it opens (the Updater spaces them out).
        assertEquals(listOf(false), looks)
    }

    @Test fun theStatusAndTheFirstPillFollowEveryStep() {
        val v = open()
        val accent = 0xFF6FB3E6.toInt()
        val muted = 0xFF93A6BC.toInt()
        fun check(s: State, status: String, color: Int, pill: String) {
            show(s)
            assertEquals(status, v.statusView.text.toString())
            assertEquals(status, color, v.statusView.currentTextColor)
            assertEquals(status, pill, v.first.text.toString())
        }
        check(State(Step.DOWNLOADING, 173, progress = 0.4f), "Downloading the fix… 40%", accent, "Checking…")
        check(State(Step.READY, 173), "A fixed version (2.0.173) is ready.", accent, "Install the fix")
        check(State(Step.INSTALLING, 173), "Installing… Woodruff opens again by itself.", accent, "Installing…")
        check(State(Step.UP_TO_DATE, 172), "No fix yet. Woodruff checks again by itself every few minutes.", muted, "Check for a fix")
        check(State(Step.ERROR, offline = true), "Can’t check right now: this TV is offline.", muted, "Check for a fix")
        check(State(Step.ERROR, message = "Couldn't connect · trying again soon"), "Couldn’t check for a fix. Try again in a moment.", muted, "Check for a fix")
        check(State(Step.READY, 173, message = "the TV is out of space"), "The fix didn’t install: the TV is out of space.", muted, "Install the fix")
        check(State(Step.READY, 173, message = "this TV doesn't allow it"), "The fix didn’t install: this TV doesn’t allow it.", muted, "Install the fix")
        check(State(Step.IDLE), "Looking for a fixed version…", accent, "Checking…")
    }

    @Test fun leftAndRightMoveBetweenTheThreeAndNothingTrapsOrLosesTheFocus() {
        val v = open(State(Step.UP_TO_DATE, 172))
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left at the first pill stays", v.first.isFocused)
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Up and Down do nothing", v.first.isFocused)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(v.again.isFocused)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(v.details.isFocused)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right at the last pill stays", v.details.isFocused)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(v.details.isFocused)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(v.again.isFocused)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(v.first.isFocused)
    }

    @Test fun okDoesNothingWhileItsCheckingOrInstalling() {
        val v = open()
        looks.clear()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        show(State(Step.DOWNLOADING, 173, progress = 0.1f))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        show(State(Step.INSTALLING, 173))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(looks.isEmpty())
        assertEquals(0, installs)
        // It stays focusable meanwhile.
        assertTrue(v.first.isFocused)
    }

    @Test fun checkForAFixLooksAtOnce() {
        open(State(Step.UP_TO_DATE, 172))
        looks.clear()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(true), looks)
        show(State(Step.ERROR, offline = true))
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf(true, true), looks)
    }

    @Test fun installTheFixInstalls() {
        open(State(Step.READY, 173))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, installs)
    }

    @Test fun aProfileThatForbidsInstallingAppsSaysSo() {
        val v = open(State(Step.READY, 173))
        blocked = true
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(0, installs)
        assertEquals("This TV’s profile doesn’t allow installing apps. Switch to the main profile, then try again.", v.statusView.text.toString())
        assertEquals("Install the fix", v.first.text.toString())
        // Until something changes: a check, or another try that's allowed.
        show(State(Step.CHECKING, 173))
        assertEquals("Looking for a fixed version…", v.statusView.text.toString())
        show(State(Step.READY, 173))
        assertEquals("A fixed version (2.0.173) is ready.", v.statusView.text.toString())
        blocked = false
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, installs)
    }

    @Test fun whatHappenedShowsTheSummaryAndTheFocusStaysOnIt() {
        val dir = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>().filesDir
        File(dir, "early_crash_report.txt").writeText(SAMPLE)
        try {
            val v = open(State(Step.UP_TO_DATE, 172))
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals("Hide details", v.details.text.toString())
            assertTrue(v.details.isFocused)
            assertEquals(View.VISIBLE, v.detailsGroup.visibility)
            assertEquals(View.GONE, v.logoView.visibility)
            assertEquals(View.GONE, v.bodyView.visibility)
            assertEquals(RecoveryView.summary(SAMPLE), v.box.text.toString())
            assertTrue(v.box.text.startsWith("Version ${com.woodruff.tvexplorer.BuildConfig.VERSION_NAME} · Nov 2, 9:41 PM · while starting\n"))
            // Everything fits on the screen, details and all, without scrolling.
            val d = activity.resources.displayMetrics.density
            assertTrue("column ${v.column.height / d} dp tall", v.column.height <= (540 - 2 * 24) * d)
            assertTrue(v.column.top >= 24 * d)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals("What happened", v.details.text.toString())
            assertEquals(View.GONE, v.detailsGroup.visibility)
            assertEquals(View.VISIBLE, v.logoView.visibility)
            assertEquals(View.VISIBLE, v.bodyView.visibility)
            assertTrue(v.details.isFocused)
        } finally {
            File(dir, "early_crash_report.txt").delete()
        }
    }

    @Test fun withNoReportSavedTheDetailsSaySo() {
        val v = open(State(Step.UP_TO_DATE, 172))
        assertEquals("Woodruff closed while starting, twice. No details were saved.", v.box.text.toString())
    }

    @Test fun backLeavesWoodruff() {
        open()
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(1, left)
    }

    @Test fun backReallyMovesWoodruffBehind() {
        RecoveryView.resetForTest()
        RecoveryView.lookForFix = { _, manual -> looks += manual }
        RecoveryView.load = { it.run() }
        open()
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(shadowOf(activity).isTaskMovedToBack)
    }

    @Test fun tryOpeningAgainStartsOver() {
        open()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, reopened)
    }

    @Test fun aLongReasonTakesTwoLinesAtMost() {
        val v = open(State(Step.READY, 173, message = "the TV couldn't install it ".repeat(12).trim()))
        v.statusView.measure(View.MeasureSpec.makeMeasureSpec(v.statusView.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        v.statusView.layout(0, 0, v.statusView.measuredWidth, v.statusView.measuredHeight)
        assertTrue(v.statusView.lineCount <= 2)
        assertEquals(2, v.statusView.maxLines)
    }

    @Test fun withoutItsFontsOrLogoItStillWorks() {
        RecoveryView.font = { _, _ -> throw android.content.res.Resources.NotFoundException("no font") }
        RecoveryView.logo = { throw OutOfMemoryError("no logo") }
        val v = open(State(Step.READY, 173))
        assertSame(Typeface.DEFAULT_BOLD, v.titleView.typeface)
        assertEquals(View.GONE, v.logoView.visibility)
        assertTrue(v.first.isFocused)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, installs)
    }

    @Test fun ifBuildingItFailsThePlainestScreenStillOffersTryOpeningAgain() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        RecoveryView.show(activity) { throw IllegalStateException("can't build it") }
        idle()
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.view.ViewGroup
        val words = content.getChildAt(0) as TextView
        assertTrue(words.text.startsWith(RecoveryView.TITLE))
        assertTrue(words.text.endsWith(RecoveryView.BODY))
        val again = content.getChildAt(1) as TextView
        assertEquals(RecoveryView.TRY_AGAIN, again.text.toString())
        assertTrue(again.isFocused)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, reopened)
        // It still looks for a fix.
        assertEquals(listOf(false), looks)
    }

    @Test fun theSummaryShowsWhenWhereAndWoodruffsOwnPart() {
        val lines = RecoveryView.summary(SAMPLE).lines()
        assertEquals("Version ${com.woodruff.tvexplorer.BuildConfig.VERSION_NAME} · Nov 2, 9:41 PM · while starting", lines[0])
        assertEquals("java.lang.IllegalStateException: Camera list changed while drawing", lines[1])
        assertEquals(RecoveryView.BOX_LINES, lines.size)
        // Where it failed first, then Woodruff's own frames and the cause, in their order.
        assertEquals("at androidx.compose.runtime.ComposerImpl.apply(Composer.kt:4203)", lines[2])
        assertTrue(lines.drop(3).count { it.contains("com.woodruff") } >= 4)
        assertTrue(lines.any { it.startsWith("Caused by") })
        assertTrue(lines.all { it.length <= RecoveryView.LINE_CHARS })
        assertTrue(lines.any { it.endsWith("…") })
    }

    @Test fun aShortReportShowsWhatThereIs() {
        val lines = RecoveryView.summary("Version 2.0.172 · Nov 2, 9:41 PM · thread pool-3\njava.lang.NullPointerException\n\tat a.b(C.kt:1)").lines()
        assertEquals(listOf("Version 2.0.172 · Nov 2, 9:41 PM · while starting", "java.lang.NullPointerException", "at a.b(C.kt:1)"), lines)
        assertEquals(RecoveryView.NO_DETAILS, RecoveryView.summary(null))
        assertEquals(RecoveryView.NO_DETAILS, RecoveryView.summary("Version 2.0.172 · Nov 2, 9:41 PM · thread main"))
    }

    @Test fun itsBuiltFromNothingThatCouldHaveBroken() {
        val source = listOf("src/main/java", "app/src/main/java").map { File(it, "com/woodruff/tvexplorer/update/RecoveryView.kt") }.first { it.isFile }
        val imports = source.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
        assertNotNull(imports.firstOrNull())
        val banned = listOf("androidx.compose", "androidx.tv", "coil", "com.woodruff.tvexplorer.ui", "com.woodruff.tvexplorer.settings", "com.woodruff.tvexplorer.notify", "com.woodruff.tvexplorer.server", "androidx.lifecycle", "androidx.activity")
        for (i in imports) assertFalse(i, banned.any { i.startsWith(it) })
        val allowed = listOf("android.", "androidx.core.content.res.ResourcesCompat", "com.woodruff.tvexplorer.BuildConfig", "com.woodruff.tvexplorer.R", "com.woodruff.tvexplorer.util.CrashLog", "com.woodruff.tvexplorer.util.EarlyCrashes", "kotlinx.coroutines.")
        for (i in imports) assertTrue("not on the list: $i", allowed.any { i.startsWith(it) })
        // Nor by full name in the code.
        val code = source.readText()
        for (b in listOf("Palette", "SecretStore", "MainViewModel", "TransferService", "Notices", "androidx.compose", "coil.")) assertFalse(b, code.contains(b))
    }

    private companion object {
        val SAMPLE = """
            Version ${com.woodruff.tvexplorer.BuildConfig.VERSION_NAME} · Nov 2, 9:41 PM · thread main
            java.lang.IllegalStateException: Camera list changed while drawing
            	at androidx.compose.runtime.ComposerImpl.apply(Composer.kt:4203)
            	at androidx.compose.runtime.ComposerImpl.doCompose(Composer.kt:3712)
            	at androidx.compose.runtime.CompositionImpl.applyChanges(Composition.kt:1022)
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
