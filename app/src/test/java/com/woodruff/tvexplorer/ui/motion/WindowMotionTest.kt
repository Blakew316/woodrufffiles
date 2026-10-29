package com.woodruff.tvexplorer.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The window animations (res/anim, res/interpolator) are XML the system runs, so they can't read
 * [TvMotion]: they repeat its numbers, and this keeps them the same.
 */
class WindowMotionTest {
    private val res = listOf("src/main/res", "app/src/main/res").map(::File).firstOrNull { it.isDirectory }
        ?: error("res folder not found from ${File(".").absolutePath}")

    @Test fun aDialogComesInFromJustLargerAndLeavesTheSameWay() {
        val enter = anim("tv_dialog_enter")
        scale(enter, TvMotion.DialogScale, 1f, TvMotion.PresentMs)
        fade(enter, 0f, 1f, TvMotion.PresentFadeMs)
        val exit = anim("tv_dialog_exit")
        scale(exit, 1f, TvMotion.DialogDismissScale, TvMotion.DismissMs)
        fade(exit, 1f, 0f, TvMotion.DismissMs)
        assertEquals("top", exit.android("zAdjustment"))
    }

    @Test fun aPageGrowsInFromJustSmaller() {
        val enter = anim("tv_page_enter")
        scale(enter, TvMotion.PageScale, 1f, TvMotion.PresentMs)
        fade(enter, 0f, 1f, TvMotion.PresentFadeMs)
        val exit = anim("tv_page_exit")
        scale(exit, 1f, TvMotion.PageScale, TvMotion.DismissMs)
        fade(exit, 1f, 0f, TvMotion.DismissMs)
        assertEquals("top", exit.android("zAdjustment"))
    }

    @Test fun aSideSheetSlidesInFromTheRightEdge() {
        val shift = "${TvMotion.SheetShiftPercent}%"
        val enter = anim("tv_sheet_enter")
        slide(enter, shift, "0", TvMotion.PresentMs)
        fade(enter, 0f, 1f, TvMotion.PresentFadeMs)
        val exit = anim("tv_sheet_exit")
        slide(exit, "0", shift, TvMotion.DismissMs)
        fade(exit, 1f, 0f, TvMotion.DismissMs)
        assertEquals("top", exit.android("zAdjustment"))
    }

    @Test fun theScreenUnderneathStaysPutForAsLong() {
        for ((name, ms) in listOf("tv_hold_enter" to TvMotion.PresentMs, "tv_hold_exit" to TvMotion.DismissMs)) {
            val hold = anim(name)
            assertEquals(name, "alpha", hold.tagName)
            assertEquals(name, ms, hold.android("duration").toInt())
            assertEquals(name, 1f, hold.android("fromAlpha").toFloat(), 0f)
            assertEquals(name, 1f, hold.android("toAlpha").toFloat(), 0f)
        }
    }

    @Test fun theirCurveIsGlideEase() {
        val curve = xml(File(res, "interpolator/tv_glide.xml"))
        assertEquals("pathInterpolator", curve.tagName)
        val x1 = curve.android("controlX1").toFloat()
        val y1 = curve.android("controlY1").toFloat()
        val x2 = curve.android("controlX2").toFloat()
        val y2 = curve.android("controlY2").toFloat()
        for (i in 1..19) {
            val x = i / 20f
            assertEquals("at $x", TvMotion.GlideEase.transform(x), bezier(x1, y1, x2, y2, x), 0.001f)
        }
        // Every part of every window animation runs on it.
        for (file in File(res, "anim").listFiles()!!.filter { it.name.startsWith("tv_") && !it.name.startsWith("tv_hold") }) {
            val parts = children(xml(file))
            assertTrue(file.name, parts.isNotEmpty())
            for (part in parts) assertEquals("${file.name} ${part.tagName}", "@interpolator/tv_glide", part.android("interpolator"))
        }
    }

    private fun anim(name: String): Element = xml(File(res, "anim/$name.xml"))

    private fun xml(file: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement

    private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)

    private fun children(set: Element): List<Element> =
        (0 until set.childNodes.length).map { set.childNodes.item(it) }.filterIsInstance<Element>()

    private fun part(set: Element, tag: String): Element {
        assertEquals("set", set.tagName)
        assertEquals("false", set.android("shareInterpolator"))
        return children(set).single { it.tagName == tag }
    }

    private fun scale(set: Element, from: Float, to: Float, ms: Int) {
        val s = part(set, "scale")
        for (axis in listOf("X", "Y")) {
            assertEquals(from, s.android("from${axis}Scale").toFloat(), 0.0001f)
            assertEquals(to, s.android("to${axis}Scale").toFloat(), 0.0001f)
            assertEquals("50%", s.android("pivot$axis"))
        }
        assertEquals(ms, s.android("duration").toInt())
    }

    private fun fade(set: Element, from: Float, to: Float, ms: Int) {
        val a = part(set, "alpha")
        assertEquals(from, a.android("fromAlpha").toFloat(), 0f)
        assertEquals(to, a.android("toAlpha").toFloat(), 0f)
        assertEquals(ms, a.android("duration").toInt())
    }

    private fun slide(set: Element, from: String, to: String, ms: Int) {
        val t = part(set, "translate")
        assertEquals(from, t.android("fromXDelta"))
        assertEquals(to, t.android("toXDelta"))
        assertEquals(ms, t.android("duration").toInt())
    }

    /** A cubic Bézier easing from (0,0) to (1,1) at [x], as Android's PathInterpolator draws it. */
    private fun bezier(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        fun at(a: Float, b: Float, t: Float) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * b + t * t * t
        var lo = 0f
        var hi = 1f
        repeat(40) {
            val mid = (lo + hi) / 2
            if (at(x1, x2, mid) < x) lo = mid else hi = mid
        }
        return at(y1, y2, (lo + hi) / 2)
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
