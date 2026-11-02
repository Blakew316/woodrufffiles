package com.woodruff.tvexplorer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The repository is public, so no sample, placeholder, comment, test or page in it names someone in the family. This
 * can't list their names (that would publish them), so it looks for the shape such a sample takes: a room or a phone
 * named after someone ("Someone's Room", "SOMEONE’S ROOM", "Someone's iPhone"). Neutral ones pass: "Living Room",
 * "Kitchen iPad", "Den's TV", "Guest's Room".
 *
 * README.md is left to the docs step, which rewrites it (its rewrite names rooms and phones neutrally too).
 */
class PublicSamplesTest {
    @Test fun noRoomOrPhoneInTheRepositoryIsNamedAfterSomeone() {
        val root = listOf(File(".."), File(".")).map { it.canonicalFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, "app/src").isDirectory }
        val found = publicText(root).flatMap { f ->
            f.readLines().withIndex().flatMap { (i, line) -> namedAfterSomeone(line).map { "${f.relativeTo(root)}:${i + 1}: $it" } }
        }
        assertEquals("Rooms or phones named after someone (use neutral samples: Living Room, Den, Kitchen iPad):", emptyList<String>(), found)
    }

    @Test fun theShapeIsFoundAndNeutralNamesPass() {
        assertEquals(listOf("Someone's Room"), namedAfterSomeone("the TV's name, e.g. *Someone's Room*: its picture says"))
        assertEquals(listOf("SOMEONE’S ROOM"), namedAfterSomeone("its picture says SOMEONE’S ROOM where it says WOODRUFF FAMILY"))
        assertEquals(listOf("Somebody's iPhone", "Somebody’s iPad"), namedAfterSomeone("""{"name": "Somebody's iPhone"}, {"name": "Somebody’s iPad"}"""))
        assertEquals(listOf("Someone's Bedroom"), namedAfterSomeone("renamedIn(list, \"id\", \"Someone's Bedroom\")"))
        for (neutral in listOf(
            "Living Room", "Master Bedroom", "Kitchen iPad", "Living Room iPad", "\"Den's TV\" → (\"DEN’S\", \"TV\")", "Guest's Room",
            "GUEST’S ROOM", "the owner's phone", "Google's TV-specific sign-in", "Woodruff's own player", "the TV's room for a video",
        )) assertEquals(neutral, emptyList<String>(), namedAfterSomeone(neutral))
    }

    /**
     * The repository's own text files: the app's sources and tests, the gallery tools, the published pages, CI, and the
     * build files (this file aside: its examples are made up to be found).
     */
    private fun publicText(root: File): List<File> {
        val trees = listOf("app/src", "tools", "docs", ".github").map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().onEnter { it.name != "build" && it.name != "node_modules" }.filter { it.isFile }.toList() }
        val top = root.listFiles().orEmpty().filter { it.isFile && it.name != "README.md" && it.name != "local.properties" }
        return (trees + top).filter { it.extension.lowercase() in TEXT && it.name != "PublicSamplesTest.kt" }.sortedBy { it.path }
    }

    private companion object {
        val TEXT = setOf("kt", "kts", "java", "xml", "html", "js", "mjs", "py", "md", "json", "yml", "yaml", "properties", "txt", "css", "webmanifest", "pro")
    }
}

/**
 * The rooms and phones in [line] named after someone: a capitalised word's possessive ahead of Room, Bedroom, iPhone or
 * iPad (or the same in capitals, as a Home Screen icon writes it), unless that word is a neutral one.
 */
internal fun namedAfterSomeone(line: String): List<String> =
    SHAPES.flatMap { it.findAll(line).toList() }.sortedBy { it.range.first }
        .filter { it.groupValues[1].lowercase() !in NEUTRAL_OWNERS }.map { it.value }

private val SHAPES = listOf(
    Regex("""\b([A-Z][a-z]+)['’]s\s+(?:Room|Bedroom|iPhone|iPad)\b"""),
    Regex("""\b([A-Z]{2,})['’]S\s+(?:ROOM|BEDROOM|IPHONE|IPAD)\b"""),
)

/** Possessives that name no one: a room or phone for whoever is staying. */
private val NEUTRAL_OWNERS = setOf("guest")
