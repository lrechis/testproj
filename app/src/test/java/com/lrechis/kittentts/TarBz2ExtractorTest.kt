package com.lrechis.kittentts

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class TarBz2ExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Builds an in-memory .tar.bz2. A null payload creates a directory entry. */
    private fun archive(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, payload) in entries) {
                val entry = TarArchiveEntry(if (payload == null && !name.endsWith("/")) "$name/" else name)
                if (payload != null) entry.size = payload.size.toLong()
                tar.putArchiveEntry(entry)
                if (payload != null) tar.write(payload)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun extractsFilesAndDirectories() {
        val data = archive(
            "kitten-nano-en-v0_8-int8" to null,
            "kitten-nano-en-v0_8-int8/tokens.txt" to "a 1\nb 2\n".toByteArray(),
            "kitten-nano-en-v0_8-int8/espeak-ng-data" to null,
            "kitten-nano-en-v0_8-int8/espeak-ng-data/phontab" to ByteArray(3000) { (it % 251).toByte() },
        )
        val dest = temp.newFolder("out")
        var lastProgress = 0L

        val files = TarBz2Extractor.extract(ByteArrayInputStream(data), dest, onProgress = { lastProgress = it })

        assertEquals(2, files)
        assertEquals(3000L + 8L, lastProgress)
        val root = File(dest, "kitten-nano-en-v0_8-int8")
        assertEquals("a 1\nb 2\n", File(root, "tokens.txt").readText())
        val phontab = File(root, "espeak-ng-data/phontab").readBytes()
        assertEquals(3000, phontab.size)
        assertEquals((2999 % 251).toByte(), phontab[2999])
    }

    @Test
    fun rejectsPathTraversal() {
        val data = archive("../evil.txt" to "boom".toByteArray())
        val dest = temp.newFolder("out")
        try {
            TarBz2Extractor.extract(ByteArrayInputStream(data), dest)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains(".."))
        }
        assertFalse(File(dest.parentFile, "evil.txt").exists())
    }

    @Test
    fun treatsAbsolutePathsAsRelative() {
        val data = archive("/etc/passwd" to "x".toByteArray())
        val dest = temp.newFolder("out")

        TarBz2Extractor.extract(ByteArrayInputStream(data), dest)

        assertTrue(File(dest, "etc/passwd").isFile)
    }

    @Test
    fun skipsSymbolicLinks() {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            val link = TarArchiveEntry("link", TarConstants.LF_SYMLINK)
            link.linkName = "../../outside"
            tar.putArchiveEntry(link)
            tar.closeArchiveEntry()
            val file = TarArchiveEntry("real.txt")
            file.size = 2
            tar.putArchiveEntry(file)
            tar.write("ok".toByteArray())
            tar.closeArchiveEntry()
        }
        val dest = temp.newFolder("out")

        val files = TarBz2Extractor.extract(ByteArrayInputStream(bytes.toByteArray()), dest)

        assertEquals(1, files)
        assertFalse(File(dest, "link").exists())
        assertEquals("ok", File(dest, "real.txt").readText())
    }

    @Test
    fun cancellationStopsExtraction() {
        val data = archive("big.bin" to ByteArray(500_000))
        val dest = temp.newFolder("out")
        try {
            TarBz2Extractor.extract(ByteArrayInputStream(data), dest, shouldCancel = { true })
            fail("expected CancelledException")
        } catch (e: CancelledException) {
            // expected
        }
    }

    @Test
    fun enforcesMaximumSize() {
        val data = archive("big.bin" to ByteArray(200_000))
        val dest = temp.newFolder("out")
        try {
            TarBz2Extractor.extract(ByteArrayInputStream(data), dest, maxBytes = 100_000)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("larger"))
        }
    }

    @Test
    fun normalizesEntryNames() {
        assertEquals("a/b.txt", TarBz2Extractor.normalizeEntryName("./a/b.txt"))
        assertEquals("a/b.txt", TarBz2Extractor.normalizeEntryName("/a//b.txt"))
        assertEquals("a/b", TarBz2Extractor.normalizeEntryName("a\\b"))
        assertNull(TarBz2Extractor.normalizeEntryName("./"))
        assertNull(TarBz2Extractor.normalizeEntryName("."))
    }

    @Test
    fun locateRootFindsDirectOrNestedModel() {
        val direct = temp.newFolder("direct")
        File(direct, "model.onnx").writeText("m")
        assertEquals(direct, TarBz2Extractor.locateRoot(direct, "model.onnx"))

        val nested = temp.newFolder("nested")
        val inner = File(nested, "kitten-mini-en-v0_8").apply { mkdirs() }
        File(inner, "model.onnx").writeText("m")
        File(nested, "README.md").writeText("stray file is ignored")
        assertEquals(inner, TarBz2Extractor.locateRoot(nested, "model.onnx"))

        val missing = temp.newFolder("missing")
        File(missing, "other").mkdirs()
        assertNull(TarBz2Extractor.locateRoot(missing, "model.onnx"))

        val ambiguous = temp.newFolder("ambiguous")
        for (name in listOf("a", "b")) {
            val dir = File(ambiguous, name).apply { mkdirs() }
            File(dir, "model.onnx").writeText("m")
        }
        assertNull(TarBz2Extractor.locateRoot(ambiguous, "model.onnx"))
    }
}
