package com.woodruff.tvexplorer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test fun releaseTags() {
        assertEquals(41, Updater.buildNumber("v2.0.41"))
        assertEquals(7, Updater.buildNumber("2.0.7"))
        assertEquals(120, Updater.buildNumber(" v3.1.120 "))
    }

    @Test fun rejectsOtherTags() {
        assertNull(Updater.buildNumber("latest"))
        assertNull(Updater.buildNumber("v2.0"))
        assertNull(Updater.buildNumber("v2.0.41-beta"))
        assertNull(Updater.buildNumber(""))
    }
}
