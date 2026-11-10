/* Copyright 2026 haorengg12. Licensed under GPL-3.0-or-later. */
package com.hippo.ehviewer.download

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryReadCacheTest {
    private val image = byteArrayOf(1, 2, 3, 4)
    private val digest get() = GalleryArchive.digest(ByteArrayInputStream(image))

    @Test
    fun extractedPageGoesOnlyToRootTempReadGid() {
        val root = MemoryGalleryFile("root")
        val gallery = root.createDirectory("cosplay")!!.createDirectory("12345")!!
        gallery.put("12345.cbz", byteArrayOf(7))
        val cache = GalleryReadCache { root }
        val page = cache.page("root", 12345, "00000001.jpg", digest) { it.write(image) }
        assertTrue(page === root.children.getValue("temp_read").children.getValue("12345").children["00000001.jpg"])
        assertEquals(setOf("12345.cbz"), gallery.children.keys)
        cache.page("root", 12345, "00000001.jpg", digest) { error("Valid cache should be reused") }
    }

    @Test
    fun startupClearsAllCacheContentsAndPreservesOtherDirectories() {
        val root = MemoryGalleryFile("root")
        val oldCache = root.createDirectory("temp_read")!!
        oldCache.createDirectory("1")!!.put("00000001.jpg")
        oldCache.createDirectory("nested")!!.createDirectory("deeper")!!.put("unfinished.part")
        val gallery = root.createDirectory("cosplay")!!.createDirectory("12345")!!
        gallery.put("12345.cbz")
        root.put("notes.txt")
        val cache = GalleryReadCache { root }
        cache.clearAtStartup("root")
        assertTrue(oldCache.children.isEmpty())
        assertNotNull(gallery.findFile("12345.cbz"))
        assertNotNull(root.findFile("notes.txt"))
    }

    @Test
    fun earlyReadAndBackgroundStartupCleanupDoNotDeleteNewImages() {
        val root = MemoryGalleryFile("root")
        root.createDirectory("temp_read")!!.createDirectory("old")!!.put("stale.jpg")
        val cache = GalleryReadCache { root }
        val fresh = cache.page("root", 12, "00000001.jpg", digest) { it.write(image) }
        cache.clearAtStartup("root")
        assertTrue(fresh === root.children.getValue("temp_read").children.getValue("12").findFile("00000001.jpg"))
        assertNull(root.children.getValue("temp_read").findFile("old"))
        GalleryReadCache { root }.clearAtStartup("root")
        assertTrue(root.children.getValue("temp_read").children.isEmpty())
    }

    @Test
    fun startupCleanupIsIndependentForEveryRecordedRoot() {
        val roots = listOf("old", "new").associateWith { MemoryGalleryFile(it) }
        roots.values.forEach { it.createDirectory("temp_read")!!.createDirectory("12")!!.put("old.jpg") }
        val cache = GalleryReadCache { roots[it] }
        roots.keys.forEach(cache::clearAtStartup)
        roots.values.forEach { assertTrue(it.children.getValue("temp_read").children.isEmpty()) }
    }

    @Test
    fun permissionAndCleanupFailureCanRetryWithoutUsingStaleImages() {
        val root = MemoryGalleryFile("root")
        val stale = root.createDirectory("temp_read")!!.createDirectory("12")!!.put("00000001.jpg")
        stale.failDelete = true
        val cache = GalleryReadCache { root }
        assertThrows(IllegalStateException::class.java) { cache.clearAtStartup("root") }
        assertThrows(IllegalStateException::class.java) { cache.page("root", 12, "00000001.jpg", digest) { it.write(image) } }
        stale.failDelete = false
        root.writable = false
        assertThrows(IllegalStateException::class.java) { cache.clearAtStartup("root") }
        root.writable = true
        cache.clearAtStartup("root")
        assertTrue(root.children.getValue("temp_read").children.isEmpty())
    }

    @Test
    fun interruptedOrInvalidExtractionNeverPublishesPartialImage() {
        val root = MemoryGalleryFile("root")
        val cache = GalleryReadCache { root }
        val directory = cache.directory("root", 12) as MemoryGalleryFile
        directory.failOutput = true
        assertThrows(IOException::class.java) { cache.page("root", 12, "00000001.jpg", digest) { it.write(image) } }
        assertTrue(directory.children.isEmpty())
        directory.failOutput = false
        assertThrows(IOException::class.java) {
            cache.page("root", 12, "00000001.jpg", digest) {
                it.write(1)
                throw IOException("Interrupted")
            }
        }
        assertThrows(IllegalStateException::class.java) { cache.page("root", 12, "00000001.jpg", digest) { it.write(9) } }
        assertTrue(directory.children.isEmpty())
        directory.failRename = true
        assertThrows(IllegalStateException::class.java) { cache.page("root", 12, "00000001.jpg", digest) { it.write(image) } }
        assertTrue(directory.children.isEmpty())
        directory.failRename = false
        val valid = cache.page("root", 12, "00000001.jpg", digest) { it.write(image) }
        assertTrue(valid.openInputStream().readBytes().contentEquals(image))
    }

    @Test
    fun invalidCacheFileCannotBeMistakenForAValidExtractedPage() {
        val root = MemoryGalleryFile("root")
        val cache = GalleryReadCache { root }
        val directory = cache.directory("root", 12) as MemoryGalleryFile
        directory.put("00000001.jpg", byteArrayOf(9))
        var extracted = false
        val page = cache.page("root", 12, "00000001.jpg", digest) {
            extracted = true
            it.write(image)
        }
        assertTrue(extracted)
        assertTrue(page.openInputStream().readBytes().contentEquals(image))
    }

    @Test
    fun repeatedConcurrentReadsExtractOnlyOnce() {
        val root = MemoryGalleryFile("root")
        val cache = GalleryReadCache { root }
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (0..1).map {
                pool.submit<GalleryFile> {
                    start.await()
                    cache.page("root", 12, "00000001.jpg", digest) { output ->
                        calls.incrementAndGet()
                        output.write(image)
                    }
                }
            }
            start.countDown()
            assertTrue(jobs[0].get(5, TimeUnit.SECONDS) === jobs[1].get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun removingOneGalleryCachePreservesOtherGidsAndOriginalArchive() {
        val root = MemoryGalleryFile("root")
        val archive = root.createDirectory("cosplay")!!.createDirectory("12")!!.put("12.cbz")
        val cache = GalleryReadCache { root }
        cache.page("root", 12, "00000001.jpg", digest) { it.write(image) }
        cache.page("root", 13, "00000001.jpg", digest) { it.write(image) }
        assertTrue(cache.removeGallery("root", 12))
        assertNull(root.children.getValue("temp_read").findFile("12"))
        assertNotNull(root.children.getValue("temp_read").findFile("13"))
        assertTrue(archive === root.children.getValue("cosplay").children.getValue("12").findFile("12.cbz"))
        assertThrows(IllegalArgumentException::class.java) { cache.page("root", 12, "../escape.jpg", digest) {} }
        assertThrows(IllegalArgumentException::class.java) { cache.directory("root", -1) }
        assertFalse(root.children.containsKey("escape.jpg"))
    }
}
