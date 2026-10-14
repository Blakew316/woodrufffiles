package com.woodruff.tvexplorer.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileOperationsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, text: String = name) = File(dir, name).apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun uniqueTargetAddsCounterBeforeExtension() {
        val dir = tmp.newFolder()
        file(dir, "movie.mkv"); file(dir, "movie (1).mkv")
        assertEquals("movie (2).mkv", FileOperations.uniqueTarget(dir, "movie.mkv").name)
        assertEquals("fresh.txt", FileOperations.uniqueTarget(dir, "fresh.txt").name)
        file(dir, ".hidden")
        assertEquals(".hidden (1)", FileOperations.uniqueTarget(dir, ".hidden").name)
    }

    @Test fun copyNeverOverwritesAndKeepsSource() = runBlocking {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        val a = file(src, "a.txt", "new")
        file(dst, "a.txt", "old")
        FileOperations.copy(listOf(a), dst, move = false) {}
        assertEquals("old", File(dst, "a.txt").readText())
        assertEquals("new", File(dst, "a (1).txt").readText())
        assertTrue(a.exists())
    }

    @Test fun moveFolderRecursively() = runBlocking {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        file(src, "show/s01/e01.mp4", "x")
        FileOperations.copy(listOf(File(src, "show")), dst, move = true) {}
        assertEquals("x", File(dst, "show/s01/e01.mp4").readText())
        assertFalse(File(src, "show").exists())
    }

    @Test fun refusesToCopyFolderIntoItself() = runBlocking {
        val dir = tmp.newFolder("a")
        val child = File(dir, "b").apply { mkdirs() }
        try {
            FileOperations.copy(listOf(dir), child, move = false) {}
            fail("expected IOException")
        } catch (e: IOException) { /* expected */ }
    }

    @Test fun zipRoundTrip() = runBlocking {
        val src = tmp.newFolder("src")
        file(src, "album/01.flac", "one"); file(src, "album/cover.jpg", "img")
        val zip = File(tmp.root, "album.zip")
        FileOperations.zip(listOf(File(src, "album")), zip) {}
        val out = tmp.newFolder("out")
        FileOperations.unzip(zip, out) {}
        assertEquals("one", File(out, "album/01.flac").readText())
        assertEquals("img", File(out, "album/cover.jpg").readText())
    }

    @Test fun unzipBlocksPathTraversal() = runBlocking {
        val zip = File(tmp.root, "evil.zip")
        ZipOutputStream(FileOutputStream(zip)).use {
            it.putNextEntry(ZipEntry("../escaped.txt")); it.write("pwned".toByteArray()); it.closeEntry()
        }
        val out = tmp.newFolder("out")
        try {
            FileOperations.unzip(zip, out) {}
            fail("expected IOException")
        } catch (e: IOException) { /* expected */ }
        assertFalse(File(tmp.root, "escaped.txt").exists())
    }

    @Test fun validateNameRejectsSeparatorsAndDots() {
        listOf("", " ", ".", "..", "a/b").forEach {
            try { FileOperations.validateName(it); fail("accepted '$it'") } catch (e: IOException) { /* expected */ }
        }
        FileOperations.validateName("Holiday 2026.mp4")
    }
}
