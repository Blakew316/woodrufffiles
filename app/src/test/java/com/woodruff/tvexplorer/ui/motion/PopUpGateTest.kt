package com.woodruff.tvexplorer.ui.motion

import com.woodruff.tvexplorer.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every pop-up Woodruff opens (a Compose Dialog: title pages, Search, the filters, every DialogFrame and so
 * every menu and confirmation) has [dpadGate] on its root. That gate is what makes the pop-up one of
 * Woodruff's windows ([DialogWindows]): without it, held arrows aren't gated there, the remote's Search
 * button falls through to the TV's assistant, the phone's remote can't reach it, and Settings → Smoothness
 * never sees its frames. Read from the app's code, so a pop-up added later is held to it too.
 */
class PopUpGateTest {
    @Test fun everyPopUpsRootHasTheGate() {
        val popUps = mutableListOf<String>()
        val ungated = mutableListOf<String>()
        for ((path, code) in SourceScan.blanked) {
            for (m in Regex("""(?<![\w])Dialog\(""").findAll(code)) {
                // A call that opens a window composes it in a trailing block: Dialog(…) { … }.
                if (code.substring(maxOf(0, m.range.first - 4), m.range.first) == "fun ") continue
                val args = SourceScan.closing(code, m.range.last)
                if (args < 0) continue
                val block = (args + 1 until code.length).firstOrNull { !code[it].isWhitespace() }?.takeIf { code[it] == '{' } ?: continue
                val content = code.substring(block, SourceScan.closing(code, block).takeIf { it > 0 } ?: code.length)
                val at = "$path:${code.substring(0, m.range.first).count { it == '\n' } + 1}"
                popUps += at
                if (!content.contains(".dpadGate()")) ungated += at
            }
        }
        // Title pages, the filters (Streaming's and Family Memories'), Search and DialogFrame: at least these.
        assertTrue("found $popUps", popUps.size >= 5)
        assertEquals("give each of these pop-ups Modifier.dpadGate() on its root", emptyList<String>(), ungated)
    }
}
