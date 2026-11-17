package com.woodruff.tvexplorer.util

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings' Google Meet and AirScreen: the app, else its store page; on a TV with neither, nothing, never a crash. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HandoffLaunchTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun onlyWhatTheTvHas() {
        shadowOf(app).checkActivities(true)
    }

    @After fun asBefore() {
        shadowOf(app).checkActivities(false)
    }

    @Test fun noStoreAndNoBrowserIsNotACrash() {
        assertTrue(Handoff.launch(app, Handoff.AIRSCREEN) is OpenResult.Failed)
    }

    @Test fun withoutTheStoreAppItsWebPageOpens() {
        val browser = ComponentName("com.example.browser", "com.example.browser.Main")
        shadowOf(app.packageManager).apply {
            addActivityIfNotPresent(browser)
            addIntentFilterForActivity(browser, IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https") })
        }
        assertEquals(OpenResult.Opened, Handoff.launch(app, Handoff.MEET))
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Uri.parse("https://play.google.com/store/apps/details?id=${Handoff.MEET}"), opened.data)
    }
}
