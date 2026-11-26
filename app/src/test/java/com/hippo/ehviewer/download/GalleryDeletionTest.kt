// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadDirname
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDeletionTest {
    private class Fixture {
        val root = MemoryGalleryFile("root")
        val otherRoot = MemoryGalleryFile("other")
        val category = root.createDirectory("manga")!!
        val gallery = category.createDirectory("12")!!
        val cache = root.createDirectory("temp_read")!!
        val location = DownloadDirname(12, "manga/12", rootUri = "original")
        var record = true
        var commitFails = false

        init {
            gallery.put("12.cbz")
            gallery.createDirectory("attachments")!!.put("notes.txt")
            cache.createDirectory("12")!!.put("00000001.jpg")
            cache.createDirectory("13")!!.put("00000001.jpg")
            category.createDirectory("13")!!.put("13.cbz")
            otherRoot.createDirectory("manga")!!.createDirectory("12")!!.put("12.cbz")
        }

        fun delete(files: Boolean = true): GalleryDeletionResult = deleteGallery(
            12,
            location,
            files,
            { GalleryDeletionPlan.capture(it) { uri -> if (uri == "original") root else otherRoot } },
            {
                if (commitFails) throw IOException("Database failed")
                record.also { record = false }
            },
        )
    }

    @Test
    fun recordOnlyDoesNotEvenOpenFiles() {
        val fixture = Fixture()
        fixture.root.failList = true
        assertEquals(DeletionStatus.DELETED, fixture.delete(false).status)
        assertNotNull(fixture.gallery.findFile("12.cbz"))
        assertNotNull(fixture.cache.findFile("12"))
        assertFalse(fixture.record)
    }

    @Test
    fun wholeTargetAndOnlyItsCacheAreDeletedEvenOnFirstAccess() {
        val fixture = Fixture()
        assertEquals(DeletionStatus.DELETED, fixture.delete().status)
        assertNull(fixture.category.findFile("12"))
        assertNull(fixture.cache.findFile("12"))
        assertNotNull(fixture.category.findFile("13"))
        assertNotNull(fixture.cache.findFile("13"))
        assertNotNull(fixture.otherRoot.findFile("manga")!!.findFile("12"))
        assertNotNull(fixture.root.findFile("manga"))
    }

    @Test
    fun enumerationPermissionAndWrongPathFailuresDoNotModifyAnything() {
        val fixture = Fixture()
        fixture.cache.children.getValue("12").failList = true
        var result = fixture.delete()
        assertEquals(DeletionStatus.FAILED, result.status)
        assertFalse(result.filesMayBePartial)
        assertNotNull(fixture.gallery.findFile("12.cbz"))
        fixture.cache.children.getValue("12").failList = false
        fixture.root.writable = false
        assertEquals(DeletionStatus.FAILED, fixture.delete().status)
        fixture.root.writable = true
        fixture.location.dirname = "manga/13"
        result = fixture.delete()
        assertEquals(DeletionStatus.FAILED, result.status)
        assertTrue(fixture.record)
        assertNotNull(fixture.category.findFile("13"))
    }

    @Test
    fun partialCleanupRetainsRecordAndRetryCompletes() {
        val fixture = Fixture()
        val blocked = fixture.gallery.children.getValue("attachments").children.getValue("notes.txt")
        blocked.failDelete = true
        val result = fixture.delete()
        assertEquals(DeletionStatus.FAILED, result.status)
        assertTrue(result.filesMayBePartial)
        assertTrue(fixture.record)
        blocked.failDelete = false
        assertEquals(DeletionStatus.DELETED, fixture.delete().status)
        assertFalse(fixture.record)
    }

    @Test
    fun cacheFailureRetainsDownloadAndOriginalFiles() {
        val fixture = Fixture()
        fixture.cache.children.getValue("12").children.getValue("00000001.jpg").failDelete = true
        assertEquals(DeletionStatus.FAILED, fixture.delete().status)
        assertTrue(fixture.record)
        assertNotNull(fixture.gallery.findFile("12.cbz"))
    }

    @Test
    fun databaseFailureReportsDeletedFilesAndMissingPathsCanRetry() {
        val fixture = Fixture()
        fixture.commitFails = true
        val result = fixture.delete()
        assertEquals(DeletionStatus.FAILED, result.status)
        assertTrue(result.filesDeleted)
        assertTrue(fixture.record)
        assertNull(fixture.category.findFile("12"))
        fixture.commitFails = false
        assertEquals(DeletionStatus.DELETED, fixture.delete().status)
        assertEquals(DeletionStatus.SKIPPED, fixture.delete().status)
    }

    @Test
    fun unplannedFilesCannotBeRecursivelyRemoved() {
        val fixture = Fixture()
        val plan = GalleryDeletionPlan.capture(fixture.location) { fixture.root }
        fixture.gallery.put("new.txt")
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { plan.delete() }
        assertNotNull(fixture.gallery.findFile("new.txt"))
        assertTrue(fixture.record)
    }

    @Test
    fun symbolicLinksAndChangedParentIdentityAreNeverFollowed() {
        val fixture = Fixture()
        val file = fixture.gallery.children.getValue("attachments").children.getValue("notes.txt")
        file.isSymbolicLink = true
        assertEquals(DeletionStatus.FAILED, fixture.delete().status)
        assertNotNull(fixture.cache.findFile("12"))
        file.isSymbolicLink = false
        file.identity = "root/manga/12/attachments/notes.txt"
        val plan = GalleryDeletionPlan.capture(fixture.location) { fixture.root }
        file.identity = "other-root/notes.txt"
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { plan.delete() }
        assertNotNull(fixture.gallery.findFile("attachments")!!.findFile("notes.txt"))
    }

    @Test
    fun reservationExcludesSameGidAndReleasesAfterFailure() {
        val reservation = GalleryOperations.reserve(12)!!
        try {
            assertNull(GalleryOperations.reserve(12))
            assertNull(GalleryOperations.available(12) { error("Reader/add must not run") })
            GalleryOperations.reserve(13)!!.close()
            assertEquals("other", GalleryOperations.available(13) { "other" })
        } finally {
            reservation.close()
        }
        assertEquals("retry", GalleryOperations.available(12) { "retry" })
    }

    @Test
    fun reservationWaitsForAnAlreadyAdmittedShortWrite() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit {
                GalleryOperations.available(42) {
                    started.countDown()
                    finish.await()
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val delete = pool.submit<AutoCloseable?> { GalleryOperations.reserve(42) }
            assertFalse(delete.isDone)
            finish.countDown()
            writer.get(5, TimeUnit.SECONDS)
            delete.get(5, TimeUnit.SECONDS)!!.close()
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }
}
