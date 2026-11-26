// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadDirname
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GalleryMaintenanceTest {
    private class Fixture {
        val root = MemoryGalleryFile("root")
        val locations = mutableMapOf<Long, DownloadDirname>()
        val identities = mutableMapOf<Long, RecoveryIdentity>()
        val downloads = mutableSetOf<Long>()
        val readers = mutableSetOf<Long>()
        var busy = false
        var cacheWritable = true
        val restored = arrayListOf<RecoveryCandidate>()
        val removed = arrayListOf<GalleryMaintenance.Entry>()
        fun directory(gid: Long, category: String = "cosplay"): MemoryGalleryFile = root.createDirectory(category)!!.createDirectory(gid.toString())!!
        fun engine(available: (DownloadDirname) -> Boolean = { false }) = GalleryMaintenance("root://selected", root, locations::get, { _, gid -> identities[gid] }, downloads::contains, readers::contains, { busy }, { true }, "cbz", existingAvailable = available, beforeDelete = { cacheWritable })
        suspend fun restore() = engine().restore { restored += it }
        fun clean() = engine().clean { removed += it }
        fun archive(directory: MemoryGalleryFile, gid: Long, pages: Int = 2) {
            val output = ByteArrayOutputStream()
            GalleryArchive.write((0 until pages).map { DownloadLayout.page(it, "jpg") }, output) { byteArrayOf(1, 2, 3).inputStream() }
            directory.put("$gid.cbz", output.toByteArray())
        }
    }

    @Test fun restoresCbzWithoutDownloadOrLocationRecord() = runBlocking {
        val f = Fixture()
        f.archive(f.directory(123), 123)
        f.identities[123] = RecoveryIdentity(123, "token", 2)
        val result = f.restore()
        assertEquals(1, result.restored)
        val item = f.restored.single()
        assertEquals("cosplay/123/123.cbz", item.location.actualPath)
        assertEquals("complete", item.location.phase)
        assertEquals(2, item.finished)
        assertEquals(2, item.location.manifest!!.lines().size)
    }

    @Test fun restoresNumberedImagesAndInfersPageCountFromComicIdentity() = runBlocking {
        val f = Fixture()
        val dir = f.directory(123)
        dir.put("00000001.jpg")
        dir.put("00000002.png")
        f.identities[123] = RecoveryIdentity(123, "token", 0)
        assertEquals(1, f.restore().restored)
        assertEquals("images", f.restored.single().location.format)
        assertEquals(2, f.restored.single().identity.pages)
        assertEquals("cosplay/123", f.restored.single().location.actualPath)
    }

    @Test fun restoresPartialTaskWithoutMarkingItCompleteOrChangingItsFormat() = runBlocking {
        val f = Fixture()
        val temp = f.directory(123).createDirectory("123_abcdef_tmp")!!
        temp.put("00000002.jpg")
        f.identities[123] = RecoveryIdentity(123, "token", 3, "images")
        assertEquals(1, f.restore().restored)
        val item = f.restored.single()
        assertEquals("downloading", item.location.phase)
        assertEquals("images", item.location.format)
        assertEquals(1, item.finished)
        assertEquals(3, item.location.expectedPages)
        assertNull(item.location.actualPath)
        assertTrue(temp.children.containsKey("00000002.jpg"))
    }

    @Test fun preservesInterruptedPublicationForVerifiedRetry() = runBlocking {
        val f = Fixture()
        val temp = f.directory(123).createDirectory("123_abcdef_tmp")!!
        temp.put("00000001.jpg")
        val digest = GalleryArchive.digest(temp.children.getValue("00000001.jpg").openInputStream())
        f.identities[123] = RecoveryIdentity(123, "token", 1, "cbz")
        f.locations[123] = DownloadDirname(123, "cosplay/123", "root://selected", tempPath = "cosplay/123/123_abcdef_tmp", phase = "publishing", expectedPages = 1, manifest = "00000001.jpg:$digest")
        assertEquals(1, f.restore().restored)
        assertEquals("publishing", f.restored.single().location.phase)
        assertEquals(f.locations[123]!!.manifest, f.restored.single().location.manifest)
    }

    @Test fun rejectsCorruptArchiveAndWrongPageCount() = runBlocking {
        val f = Fixture()
        f.directory(123).put("123.cbz", byteArrayOf(1, 2))
        f.identities[123] = RecoveryIdentity(123, "token", 2)
        f.archive(f.directory(456), 456, 1)
        f.identities[456] = RecoveryIdentity(456, "token", 2)
        assertEquals(2, f.restore().failed)
        assertTrue(f.restored.isEmpty())
    }

    @Test fun rejectsMixedFormatsDuplicatePagesAndDuplicateGalleryLocations() = runBlocking {
        val f = Fixture()
        val first = f.directory(123)
        f.archive(first, 123, 1)
        first.put("00000001.jpg")
        val second = f.directory(456)
        second.put("00000001.jpg")
        second.put("00000001.png")
        f.directory(789)
        f.directory(789, "manga")
        for (gid in listOf(123L, 456L, 789L)) f.identities[gid] = RecoveryIdentity(gid, "token", 1)
        assertEquals(4, f.restore().failed)
        assertTrue(f.restored.isEmpty())
    }

    @Test fun protectsUnknownCategoriesLegacyFlatDirectoriesAndReadCache() = runBlocking {
        val f = Fixture()
        f.root.createDirectory("123-title")!!.put(".ehviewer")
        f.directory(123, "temp_read").put("00000001.jpg")
        f.directory(456, "personal").put("00000001.jpg")
        assertEquals(0, f.restore().restored)
        assertEquals(0, f.clean().removed)
        assertEquals(3, f.root.children.size)
    }

    @Test fun reportsInaccessibleCategoryAndPreservesItsContents() = runBlocking {
        val f = Fixture()
        f.directory(123).put("00000001.jpg")
        f.root.children.getValue("cosplay").failList = true
        assertEquals(1, f.restore().failed)
        assertEquals(1, f.clean().failed)
    }

    @Test fun metadataAloneDoesNotAuthorizeDeletingUnknownFiles() {
        val f = Fixture()
        val dir = f.directory(123)
        dir.put("personal.txt")
        f.identities[123] = RecoveryIdentity(123, "token", 1, fromDisk = false)
        assertEquals(0, f.clean().removed)
        assertTrue(dir.children.containsKey("personal.txt"))
    }

    @Test fun metadataAloneDoesNotTurnUnrelatedFilesIntoDownloadTasks() = runBlocking {
        val f = Fixture()
        f.directory(123).put("personal.txt")
        f.identities[123] = RecoveryIdentity(123, "token", 2, fromDisk = false)
        assertEquals(1, f.restore().failed)
        assertTrue(f.restored.isEmpty())
        assertTrue(f.directory(123).children.containsKey("personal.txt"))
    }

    @Test fun anotherAvailableRootCanOnlyBeReboundToVerifiedIdenticalContent() = runBlocking {
        val f = Fixture()
        f.archive(f.directory(123), 123)
        f.identities[123] = RecoveryIdentity(123, "token", 2)
        f.restore()
        val candidate = f.restored.single().location
        f.restored.clear()
        f.locations[123] = candidate.copy(rootUri = "root://other", manifest = "different")
        assertEquals(1, f.engine { true }.restore { f.restored += it }.failed)
        assertTrue(f.restored.isEmpty())
        f.locations[123] = candidate.copy(rootUri = "root://other")
        assertEquals(1, f.engine { true }.restore { f.restored += it }.restored)
        assertEquals("root://selected", f.restored.single().location.rootUri)
    }

    @Test fun cleansUnregisteredRecognizableCbzButProtectsDownloadsAndReaders() {
        val f = Fixture()
        for (gid in listOf(123L, 456L, 789L)) f.archive(f.directory(gid), gid)
        f.downloads += 456
        f.readers += 789
        val result = f.clean()
        assertEquals(1, result.removed)
        assertEquals(2, result.skipped)
        assertEquals(123L, f.removed.single().gid)
        assertTrue(f.root.children.getValue("cosplay").children.keys.containsAll(listOf("456", "789")))
    }

    @Test fun removesWholeOwnedDirectoryIncludingAttachmentsButNotCategory() {
        val f = Fixture()
        val dir = f.directory(123)
        dir.put("notes.txt")
        dir.createDirectory("extra")!!.put("copy.jpg")
        f.identities[123] = RecoveryIdentity(123, "token", 2)
        assertEquals(1, f.clean().removed)
        assertTrue(f.root.children.containsKey("cosplay"))
        assertTrue(f.root.children.getValue("cosplay").children.isEmpty())
    }

    @Test fun failedDeleteOrReadCacheCleanupRetainsLocationForRetry() {
        val f = Fixture()
        val dir = f.directory(123)
        f.identities[123] = RecoveryIdentity(123, "token", 1)
        f.cacheWritable = false
        assertEquals(1, f.clean().failed)
        assertTrue(f.removed.isEmpty())
        f.cacheWritable = true
        dir.failDelete = true
        assertEquals(1, f.clean().failed)
        assertTrue(f.removed.isEmpty())
        dir.failDelete = false
        assertEquals(1, f.clean().removed)
    }

    @Test fun identityConflictDoesNotRestoreOrDelete() = runBlocking {
        val f = Fixture()
        f.archive(f.directory(123), 123)
        f.identities[123] = RecoveryIdentity(456, "wrong", 2)
        assertEquals(1, f.restore().failed)
        assertEquals(1, f.clean().failed)
        assertTrue(f.root.children.getValue("cosplay").children.containsKey("123"))
    }

    @Test fun activeDownloadsStopBothOperations() = runBlocking {
        val f = Fixture()
        f.archive(f.directory(123), 123)
        f.identities[123] = RecoveryIdentity(123, "token", 2)
        f.busy = true
        assertTrue(f.restore().stopped)
        assertTrue(f.clean().stopped)
        assertTrue(f.restored.isEmpty())
        assertTrue(f.removed.isEmpty())
    }

    @Test fun applyFailureLeavesFilesForRetryAndCancellationPropagates() = runBlocking {
        val f = Fixture()
        f.archive(f.directory(123), 123)
        f.identities[123] = RecoveryIdentity(123, "token", 2)
        assertEquals(1, f.engine().restore { error("database unavailable") }.failed)
        assertNotNull(f.directory(123).findFile("123.cbz"))
        try {
            f.engine().restore { throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
