package com.woodruff.tvexplorer

import java.io.File

/**
 * Woodruff's own Kotlin sources, for tests that hold a rule across the whole app rather than one screen
 * (every way into the main window goes through MainIntents; every pop-up's root has the gate): each file's
 * path under the source root, and its code with the comments taken out, so a rule never trips over a KDoc
 * that names what it forbids.
 */
internal object SourceScan {
    /** The app's source root, from the module's directory (where Gradle runs the tests) or the project's. */
    private val root: File = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    /** Every Kotlin file of the app, by its path under the source root ("ui/AppRoot.kt" style, from the package). */
    val files: List<Pair<String, String>> by lazy {
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath.substringAfter("com/woodruff/tvexplorer/") to withoutComments(it.readText()) }
            .sortedBy { it.first }
            .toList()
    }

    /** Where [pattern] matches in the app's code: "path:line: the line" for each, for a failure that says where. */
    fun matches(pattern: Regex): List<String> = files.flatMap { (path, code) ->
        code.lines().mapIndexedNotNull { i, line -> if (pattern.containsMatchIn(line)) "$path:${i + 1}: ${line.trim()}" else null }
    }

    /**
     * [source] with its line and block comments (nested, as Kotlin allows) taken out, its line breaks kept so
     * line numbers still match. Strings and characters are left as they are, so "https://…" stays code.
     */
    fun withoutComments(source: String): String {
        val out = StringBuilder(source.length)
        val n = source.length
        var i = 0
        fun at(s: String) = source.startsWith(s, i)
        while (i < n) {
            when {
                at("//") -> while (i < n && source[i] != '\n') i++
                at("/*") -> {
                    var depth = 0
                    while (i < n) {
                        when {
                            at("/*") -> { depth++; i += 2 }
                            at("*/") -> { depth--; i += 2; if (depth == 0) break }
                            else -> { if (source[i] == '\n') out.append('\n'); i++ }
                        }
                    }
                }
                at("\"\"\"") -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
                    out.append(source, i, end)
                    i = end
                }
                source[i] == '"' -> {
                    val start = i++
                    while (i < n && source[i] != '"' && source[i] != '\n') i += if (source[i] == '\\') 2 else 1
                    i = minOf(i + 1, n)
                    out.append(source, start, i)
                }
                source[i] == '\'' -> {
                    // A character: 'a', '\n', 'A'.
                    val close = source.indexOf('\'', i + if (source.getOrNull(i + 1) == '\\') 3 else 2)
                    val end = if (close < 0) i + 1 else close + 1
                    out.append(source, i, end)
                    i = end
                }
                else -> out.append(source[i++])
            }
        }
        return out.toString()
    }
}
