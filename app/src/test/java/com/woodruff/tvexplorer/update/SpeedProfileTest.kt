package com.woodruff.tvexplorer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedProfileTest {
    // What a release holds: the APK under each of its names, and a speed profile per profile format.
    private val release = listOf(
        "WoodruffFamily.apk", "WoodruffFilms.apk", "WoodruffPictures.apk", "WoodruffShows.apk", "TVExplorer.apk",
        "WoodruffFamily-api28.dm", "WoodruffFamily-api31.dm",
    )

    @Test fun eachAndroidVersionGetsItsFormat() {
        assertEquals("WoodruffFamily-api28.dm", Updater.profileFor(release, 28))
        assertEquals("WoodruffFamily-api28.dm", Updater.profileFor(release, 30))
        assertEquals("WoodruffFamily-api31.dm", Updater.profileFor(release, 31))
        assertEquals("WoodruffFamily-api31.dm", Updater.profileFor(release, 36))
    }

    @Test fun noneBeforeAndroid9() {
        assertNull(Updater.profileFor(release, 24))
        assertNull(Updater.profileFor(release, 27))
    }

    @Test fun releasesWithoutOne() {
        // Older releases and rollbacks of them: the update installs without.
        assertNull(Updater.profileFor(release.filter { it.endsWith(".apk") }, 31))
        assertNull(Updater.profileFor(emptyList(), 31))
        // Only Android 12's: Android 9 to 11 go without rather than take a format they can't read.
        assertNull(Updater.profileFor(listOf("WoodruffFamily.apk", "WoodruffFamily-api31.dm"), 30))
    }

    @Test fun aLastVersionInTheNameIsKept() {
        val gap = listOf("WoodruffFamily-api28-30.dm", "WoodruffFamily-api33.dm")
        assertEquals("WoodruffFamily-api28-30.dm", Updater.profileFor(gap, 29))
        assertEquals("WoodruffFamily-api28-30.dm", Updater.profileFor(gap, 30))
        assertNull(Updater.profileFor(gap, 31))
        assertEquals("WoodruffFamily-api33.dm", Updater.profileFor(gap, 34))
    }

    @Test fun orderAndOtherFilesDontMatter() {
        assertEquals("WoodruffFamily-api31.dm", Updater.profileFor(release.reversed(), 33))
        val others = listOf(
            "WoodruffFamily-api.dm", "WoodruffFamily-apiX.dm", "Other-api31.dm", "WoodruffFamily-api31.dm.part",
            "WoodruffFamily-api24.dm", "WoodruffFamily-api99999999999.dm",
        )
        assertNull(Updater.profileFor(others, 33))
        assertEquals("WoodruffFamily-api31.dm", Updater.profileFor(others + "WoodruffFamily-api31.dm", 33))
    }
}
