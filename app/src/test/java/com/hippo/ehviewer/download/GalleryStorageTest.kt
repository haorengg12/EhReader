/* Copyright 2026 haorengg12. Licensed under GPL-3.0-or-later. */
package com.hippo.ehviewer.download

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryStorageTest {
    @Test
    fun categoryAndIdentityDoNotDependOnTitles() {
        assertEquals("doujinshi/4301918", DownloadLayout.directory(4301918, 2))
        assertEquals("non-h/12", DownloadLayout.directory(12, 256))
        assertEquals("private/12", DownloadLayout.directory(12, 1024))
        listOf(0, -1, 3, 2048).forEach { assertEquals("unknown/12", DownloadLayout.directory(12, it)) }
        assertEquals("00000001.png", DownloadLayout.page(0, "PNG"))
        assertThrows(IllegalArgumentException::class.java) { DownloadLayout.directory(0, 1) }
    }

    @Test
    fun rejectPathsOutsideAuthorizedRoot() {
        listOf("/absolute", "../escape", "a/../b", "a//b", "a\\b", "C:/tmp", "a/./b", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { DownloadLayout.segments(it) }
        }
        assertEquals(listOf("manga", "12"), DownloadLayout.segments("manga/12"))
    }

    private fun archive(pages: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        GalleryArchive.write(pages.keys.toList(), output) { ByteArrayInputStream(pages.getValue(it)) }
    }.toByteArray()

    @Test
    fun cbzRoundTripPreservesPageOrderAndBytes() {
        val pages = linkedMapOf("00000001.jpg" to byteArrayOf(1, 2, 3), "00000002.png" to byteArrayOf(4, 5))
        val bytes = archive(pages)
        val expected = pages.mapValues { ByteArrayInputStream(it.value).use(GalleryArchive::digest) }
        assertEquals(expected, GalleryArchive.manifest(ByteArrayInputStream(bytes)))
        val output = ByteArrayOutputStream()
        assertTrue(
            GalleryArchive.copyPage(ByteArrayInputStream(bytes), 1) { extension ->
                assertEquals("png", extension)
                output
            },
        )
        assertTrue(pages.getValue("00000002.png").contentEquals(output.toByteArray()))
        assertFalse(GalleryArchive.copyPage(ByteArrayInputStream(bytes), 2) { ByteArrayOutputStream() })
    }

    @Test
    fun invalidOrMissingPagesNeverBecomeCompleteArchive() {
        assertThrows(IllegalStateException::class.java) { archive(linkedMapOf("00000002.jpg" to byteArrayOf(1))) }
        assertThrows(IllegalStateException::class.java) { GalleryArchive.validateNames(listOf("00000001.jpg", "00000001.png")) }
        assertThrows(IllegalStateException::class.java) { GalleryArchive.manifest(ByteArrayInputStream(archive(mapOf("00000001.jpg" to byteArrayOf())))) }
        val malicious = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("../00000001.jpg"))
                zip.write(1)
                zip.closeEntry()
            }
        }.toByteArray()
        assertThrows(IllegalStateException::class.java) { GalleryArchive.manifest(ByteArrayInputStream(malicious)) }
    }

    @Test
    fun storageFailurePropagatesAndSourceRemainsAvailable() {
        val original = byteArrayOf(1, 2, 3)
        val broken = object : OutputStream() {
            override fun write(value: Int): Unit = throw IOException("No space")
        }
        assertThrows(IOException::class.java) { GalleryArchive.write(listOf("00000001.jpg"), broken) { ByteArrayInputStream(original) } }
        assertTrue(original.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(1, GalleryArchive.manifest(ByteArrayInputStream(archive(mapOf("00000001.jpg" to original)))).size)
    }

    @Test
    fun corruptArchiveFailsIntegrityCheck() {
        val bytes = archive(mapOf("00000001.jpg" to ByteArray(100) { it.toByte() }))
        val damaged = bytes.clone().also { it[45] = (it[45].toInt() xor 0x7f).toByte() }
        assertThrows(Exception::class.java) { GalleryArchive.manifest(ByteArrayInputStream(damaged)) }
        assertThrows(IllegalStateException::class.java) { GalleryArchive.manifest(ByteArrayInputStream(bytes.copyOf(bytes.size - 12))) }
    }

    @Test
    fun randomAccessReaderChecksThePersistedPageDigest() {
        val content = byteArrayOf(1, 2, 3)
        val file = Files.createTempFile("ehreader-cbz-", ".cbz").toFile()
        try {
            file.writeBytes(archive(mapOf("00000001.jpg" to content)))
            ZipFile(file).use { zip ->
                val output = ByteArrayOutputStream()
                assertTrue(GalleryArchive.copyPage(zip, "00000001.jpg", output, GalleryArchive.digest(ByteArrayInputStream(content))))
                assertTrue(content.contentEquals(output.toByteArray()))
                assertThrows(IllegalStateException::class.java) { GalleryArchive.copyPage(zip, "00000001.jpg", ByteArrayOutputStream(), "incorrect") }
            }
        } finally {
            file.delete()
        }
    }
}
