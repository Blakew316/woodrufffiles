package com.woodruff.tvexplorer.util

import com.woodruff.tvexplorer.tvs.ExitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android's reasons for closing Woodruff: the ones worth telling, and where a native crash happened. */
class ExitReasonsTest {
    @Test fun onlyNotableReasonsAreKept() {
        assertEquals(ExitKind.LOW_MEMORY, exitKind(3))
        assertEquals(ExitKind.CRASH, exitKind(4))
        assertEquals(ExitKind.NATIVE_CRASH, exitKind(5))
        assertEquals(ExitKind.ANR, exitKind(6))
        assertEquals(ExitKind.INIT_FAILURE, exitKind(7))
        // Exited by itself, signalled, a permission change, the user stopping it, an update…: routine.
        for (routine in listOf(0, 1, 2, 8, 9, 10, 11, 12, 13, 14, 15, 16)) assertNull(exitKind(routine))
    }

    @Test fun aNativeCrashInTheCameraOrVideoCodeIsToldApart() {
        assertTrue(mentionsMediaCode("backtrace: #00 pc 0004 /data/app/~~x/lib/arm/libjingle_peerconnection_so.so".toByteArray()))
        assertTrue(mentionsMediaCode("#03 pc 00012 /system/lib/libstagefright.so (android::MediaCodec::onMessage)".toByteArray()))
        assertTrue(mentionsMediaCode(byteArrayOf(0x0a, 0x12) + "/vendor/lib/libcodec2_vndk.so".toByteArray()))
        assertFalse(mentionsMediaCode("#00 pc 0004 /apex/com.android.runtime/lib/bionic/libc.so (abort+164)".toByteArray()))
    }
}
