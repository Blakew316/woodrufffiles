package com.woodruff.tvexplorer.util

/**
 * Where the main thread was, sampled while one piece of work kept it busy (see [MainThreadWatch]).
 * Each sample is counted two ways: by the first Woodruff frame from the top of the stack together
 * with the two frames above it (which of our code, and what it was busy calling), and by the top
 * three frames, whoever's they are. New signatures stop being added after [maxKinds], so a recording
 * can't grow without bound.
 */
class HotSpots(private val ours: String = "com.woodruff.", private val maxKinds: Int = 2_000) {
    /** A signature (top frame first) and how many samples had it. No frames: no Woodruff code on the stack. */
    class Spot(val frames: List<StackTraceElement>, val samples: Int)

    private val byOurs = HashMap<List<StackTraceElement>, IntArray>()
    private val byTop = HashMap<List<StackTraceElement>, IntArray>()
    var samples = 0
        private set

    @Synchronized
    fun add(stack: Array<StackTraceElement>) {
        if (stack.isEmpty()) return
        samples++
        val i = stack.indexOfFirst { it.className.startsWith(ours) }
        // slice copies just those frames, so a kept signature doesn't hold on to the whole stack.
        count(byOurs, if (i < 0) emptyList() else stack.slice(maxOf(0, i - 2)..i))
        count(byTop, stack.slice(0 until minOf(3, stack.size)))
    }

    private fun count(map: HashMap<List<StackTraceElement>, IntArray>, key: List<StackTraceElement>) {
        val n = map[key] ?: (if (map.size < maxKinds) IntArray(1).also { map[key] = it } else return)
        n[0]++
    }

    /** The [n] signatures with the most samples, by Woodruff code. */
    @Synchronized
    fun topOurs(n: Int): List<Spot> = top(byOurs, n)

    /** The [n] signatures with the most samples, by the top of the stack. */
    @Synchronized
    fun topOfStack(n: Int): List<Spot> = top(byTop, n)

    @Synchronized
    fun clear() {
        byOurs.clear()
        byTop.clear()
        samples = 0
    }

    private fun top(map: HashMap<List<StackTraceElement>, IntArray>, n: Int): List<Spot> =
        map.entries.sortedWith(compareByDescending<Map.Entry<List<StackTraceElement>, IntArray>> { it.value[0] }.thenBy { it.key.joinToString() })
            .take(n).map { Spot(it.key, it.value[0]) }
}

/** Main-thread work that ran longer than the recording's threshold, added up by kind (see [messageKind]). */
class LongWork(private val maxKinds: Int = 500) {
    class Kind(val name: String, val times: Int, val totalNs: Long, val maxNs: Long)

    // Per kind: times, total ns, longest ns.
    private val kinds = HashMap<String, LongArray>()

    @Synchronized
    fun add(kind: String, ns: Long) {
        val k = kinds[kind] ?: (if (kinds.size < maxKinds) LongArray(3).also { kinds[kind] = it } else return)
        k[0]++
        k[1] += ns
        if (ns > k[2]) k[2] = ns
    }

    /** The [n] kinds that took the most time in all. */
    @Synchronized
    fun top(n: Int): List<Kind> =
        kinds.entries.sortedWith(compareByDescending<Map.Entry<String, LongArray>> { it.value[1] }.thenBy { it.key })
            .take(n).map { Kind(it.key, it.value[0].toInt(), it.value[1], it.value[2]) }

    @Synchronized
    fun clear() = kinds.clear()
}

// Object ids ("@1a2b3c", " {1a2b3c}") and package names, which differ between runs or just add length.
private val OBJECT_ID = Regex("""@[0-9a-f]+|\s*\{[0-9a-f]+}""")
private val PACKAGE = Regex("""\b(?:[a-z][a-z0-9_]*\.)+(?=[A-Z])""")

/**
 * A kind of main-thread message, from the line Android's Looper logs as it starts one: ">>>>> Dispatching
 * to " + its Handler + " " + its callback + ": " + its number. Object ids and packages come out, so the
 * same kind of work adds up: "Handler (android.view.Choreographer$FrameHandler) {a1b2c3}
 * android.view.Choreographer$FrameDisplayEventReceiver@d4e5f6: 0" becomes
 * "Choreographer$FrameHandler: Choreographer$FrameDisplayEventReceiver" (the frame itself). A message
 * without a callback keeps its number: "ViewRootImpl$ViewRootHandler #6".
 */
internal fun messageKind(line: String): String {
    var s = line.removePrefix(">>>>> Dispatching to ").removePrefix("<<<<< Finished to ")
    var what: String? = null
    val colon = s.lastIndexOf(": ")
    if (colon > 0 && s.substring(colon + 2).toIntOrNull() != null) {
        what = s.substring(colon + 2)
        s = s.substring(0, colon)
    }
    s = s.replace(OBJECT_ID, "").replace(PACKAGE, "").trim()
    if (!s.startsWith("Handler (")) return s
    val close = s.indexOf(')')
    if (close < 0) return s
    val handler = s.substring("Handler (".length, close)
    val callback = s.substring(close + 1).trim()
    return when {
        callback.isNotEmpty() && callback != "null" -> "$handler: $callback"
        what != null -> "$handler #$what"
        else -> handler
    }
}

/**
 * A stack frame without its package, short enough for a phone: "LayoutNode.remeasure:1123" when the
 * file is the class's own (LayoutNode.kt), else "ComposerImpl.doCompose (Composer.kt:3000)".
 */
internal fun frameText(e: StackTraceElement): String {
    val simple = e.className.substringAfterLast('.')
    val file = e.fileName
    val line = e.lineNumber
    // A class's own file: Foo.kt or Foo.java for Foo, its nested classes and its Kotlin file class FooKt
    // (and Compose's Foo.android.kt).
    val own = file != null && file.substringBefore('.') == simple.substringBefore('$').removeSuffix("Kt")
    val where = when {
        e.isNativeMethod -> " (native)"
        file == null -> " (?)"
        own -> if (line >= 0) ":$line" else ""
        line >= 0 -> " ($file:$line)"
        else -> " ($file)"
    }
    return "$simple.${e.methodName}$where"
}
