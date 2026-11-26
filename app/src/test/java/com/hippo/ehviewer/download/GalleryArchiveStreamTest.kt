// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryArchiveStreamTest {
    private val first = ByteArray(100) { it.toByte() }
    private val second = byteArrayOf(9, 8, 7, 6)
    private fun archive(): ByteArray {
        val pages = linkedMapOf("00000001.jpg" to first, "00000002.png" to second)
        val output = ByteArrayOutputStream()
        GalleryArchive.write(pages.keys.toList(), output) { pages.getValue(it).inputStream() }
        return output.toByteArray()
    }

    @Test fun acceptsReadOnlyNonSeekableStreamAndClosesIt() {
        var closed = false
        val stream = object : FilterInputStream(archive().inputStream()) {
            override fun markSupported() = false
            override fun mark(readlimit: Int) {
                error("Cannot seek provider stream")
            }
            override fun reset() {
                error("Cannot reopen provider stream")
            }
            override fun close() {
                closed = true
                super.close()
            }
        }
        val output = ByteArrayOutputStream()
        assertTrue(GalleryArchive.copyPage(stream, "00000002.png", output, GalleryArchive.digest(second.inputStream())))
        assertArrayEquals(second, output.toByteArray())
        assertTrue(closed)
    }

    @Test fun rejectsWrongEntryNameDigestAndCorruptedCompressedContent() {
        val bytes = archive()
        assertThrows(IllegalStateException::class.java) {
            GalleryArchive.copyPage(bytes.inputStream(), "00000001.png", ByteArrayOutputStream(), GalleryArchive.digest(first.inputStream()))
        }
        assertThrows(IllegalStateException::class.java) {
            GalleryArchive.copyPage(bytes.inputStream(), "00000001.jpg", ByteArrayOutputStream(), "0".repeat(64))
        }
        val corrupt = bytes.clone().also { it[45] = (it[45].toInt() xor 0x7f).toByte() }
        assertThrows(Exception::class.java) {
            GalleryArchive.copyPage(corrupt.inputStream(), "00000001.jpg", ByteArrayOutputStream(), GalleryArchive.digest(first.inputStream()))
        }
        assertFalse(GalleryArchive.copyPage(bytes.inputStream(), "00000003.jpg", ByteArrayOutputStream(), GalleryArchive.digest(first.inputStream())))
    }

    @Test fun verifiedPageGoesToDownloadRootCacheAndIsNotExtractedAgain() {
        val root = MemoryGalleryFile("root")
        val gallery = root.createDirectory("cosplay")!!.createDirectory("123")!!
        val archiveFile = gallery.put("123.cbz", archive())
        val original = archiveFile.data.clone()
        val cache = GalleryReadCache { root }
        val digest = GalleryArchive.digest(second.inputStream())
        val page = cache.page("root", 123, "00000002.png", digest) {
            check(GalleryArchive.copyPage(archiveFile.openInputStream(), "00000002.png", it, digest))
        }
        assertArrayEquals(second, page.openInputStream().use { it.readBytes() })
        assertNotNull(root.findFile("temp_read")!!.findFile("123")!!.findFile("00000002.png"))
        assertTrue(gallery.children.keys == setOf("123.cbz"))
        assertArrayEquals(original, archiveFile.data)
        assertTrue(page === cache.page("root", 123, "00000002.png", digest) { error("Cached page must not reopen archive") })
    }

    @Test fun providerReadFailureDoesNotPublishPartialFileAndCanRetry() {
        val root = MemoryGalleryFile("root")
        val cache = GalleryReadCache { root }
        val digest = GalleryArchive.digest(first.inputStream())
        val denied = object : InputStream() {
            override fun read(): Int = throw FileNotFoundException("EACCES")
        }
        assertThrows(FileNotFoundException::class.java) {
            cache.page("root", 123, "00000001.jpg", digest) {
                check(GalleryArchive.copyPage(denied, "00000001.jpg", it, digest))
            }
        }
        val directory = root.findFile("temp_read")!!.findFile("123") as MemoryGalleryFile
        assertTrue(directory.children.isEmpty())
        val page = cache.page("root", 123, "00000001.jpg", digest) {
            check(GalleryArchive.copyPage(archive().inputStream(), "00000001.jpg", it, digest))
        }
        assertArrayEquals(first, page.openInputStream().use { it.readBytes() })
    }
}
