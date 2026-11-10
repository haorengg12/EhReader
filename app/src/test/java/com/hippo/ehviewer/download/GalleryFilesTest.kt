/* Copyright 2026 haorengg12. Licensed under GPL-3.0-or-later. */
package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadDirname
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryFilesTest {
    private class Fixture {
        val roots = mutableMapOf("one" to MemoryGalleryFile("root"), "two" to MemoryGalleryFile("root2"))
        val records = mutableMapOf<Long, DownloadDirname>()
        var commitFails = false
        var sequence = 0
        val files = GalleryFiles(
            { records[it]?.copy() },
            { records[it.gid] = it.copy() },
            {
                if (commitFails) throw IOException("Database commit failed")
                records[it.gid] = it.copy()
            },
            { roots[it] },
            { it.openInputStream().use { stream -> stream.read() == 1 } },
            { "%06x".format(sequence++) },
            { records.remove(it) },
        )
        fun start(cbz: Boolean = true): MemoryGalleryFile = (files.prepare(12, 2, "one", cbz) as MemoryGalleryFile).also {
            it.put("00000001.jpg")
            it.put("00000002.png", byteArrayOf(1, 4, 5))
        }
        fun target(): MemoryGalleryFile = roots.getValue("one").children.getValue("doujinshi").children.getValue("12")
    }

    @Test
    fun publishCbzThenReadVerifyAndRepeat() {
        val fixture = Fixture()
        fixture.start()
        fixture.files.finish(12, 2)
        val record = fixture.records.getValue(12)
        assertEquals("complete", record.phase)
        assertEquals("doujinshi/12/12.cbz", record.actualPath)
        assertTrue(fixture.files.verify(record))
        assertNotNull(fixture.target().findFile(".thumb"))
        assertNull(fixture.target().findFile("12_000000_tmp"))
        fixture.files.finish(12, 2)
        assertEquals(1, fixture.target().children.keys.count { it.endsWith(".cbz") })
    }

    @Test
    fun pauseAndRootSwitchKeepTaskIdentityAndFormat() {
        val fixture = Fixture()
        val stage = fixture.start(false)
        val resumed = fixture.files.prepare(12, 4, "two", true)
        assertTrue(stage === resumed)
        assertEquals("images", fixture.records.getValue(12).format)
        assertEquals("one", fixture.records.getValue(12).rootUri)
        fixture.files.finish(12, 2)
        assertEquals("doujinshi/12", fixture.records.getValue(12).actualPath)
        assertNotNull(fixture.target().findFile("00000001.jpg"))
        assertTrue(fixture.roots.getValue("two").children.isEmpty())
    }

    @Test
    fun writtenTargetWithFailedDatabaseCommitCanResume() {
        val fixture = Fixture()
        val stage = fixture.start()
        fixture.commitFails = true
        assertThrows(IOException::class.java) { fixture.files.finish(12, 2) }
        assertEquals("publishing", fixture.records.getValue(12).phase)
        assertNotNull(stage.findFile("00000001.jpg"))
        val archive = fixture.target().findFile("12.cbz")!!.data.clone()
        fixture.commitFails = false
        fixture.files.finish(12, 2)
        assertTrue(archive.contentEquals(fixture.target().findFile("12.cbz")!!.data))
        assertTrue(fixture.files.verify(fixture.records.getValue(12)))
    }

    @Test
    fun spacePermissionAndCancellationFailuresRetainSource() {
        val fixture = Fixture()
        val stage = fixture.start()
        fixture.target().failOutput = true
        assertThrows(IOException::class.java) { fixture.files.finish(12, 2) }
        assertNotNull(stage.findFile("00000002.png"))
        assertFalse(fixture.records.getValue(12).phase == "complete")
        fixture.target().failOutput = false
        val root = fixture.roots.remove("one")!!
        assertThrows(IOException::class.java) { fixture.files.finish(12, 2) }
        fixture.roots["one"] = root
        assertThrows(InterruptedException::class.java) { fixture.files.finish(12, 2) { throw InterruptedException("Cancelled") } }
        fixture.files.finish(12, 2)
        assertTrue(fixture.files.verify(fixture.records.getValue(12)))
    }

    @Test
    fun missingUnreadableAndConflictingContentCannotComplete() {
        val fixture = Fixture()
        val stage = fixture.start()
        stage.children.remove("00000002.png")
        assertThrows(IllegalStateException::class.java) { fixture.files.finish(12, 2) }
        stage.put("00000002.png", byteArrayOf(0))
        assertThrows(IllegalStateException::class.java) { fixture.files.finish(12, 2) }
        stage.put("00000002.png")
        fixture.target().put("12.cbz", byteArrayOf(8))
        assertThrows(IllegalStateException::class.java) { fixture.files.finish(12, 2) }
        assertTrue(fixture.target().findFile("12.cbz")!!.data.contentEquals(byteArrayOf(8)))
    }

    @Test
    fun deletionRemovesEntireGalleryDirectoryAndProtectsOtherGalleries() {
        val fixture = Fixture()
        val category = fixture.roots.getValue("one").createDirectory("doujinshi")!!
        val directory = category.createDirectory("12")!!
        directory.createDirectory("12_000000_tmp")!!.put("unrelated.txt")
        val other = category.createDirectory("13")!!.also { it.put("00000001.jpg") }
        fixture.start(false)
        assertEquals("doujinshi/12/12_000001_tmp", fixture.records.getValue(12).tempPath)
        fixture.files.finish(12, 2)
        directory.put("notes.txt")
        assertTrue(fixture.files.deleteFiles(12))
        assertNull(category.findFile("12"))
        assertNotNull(other.findFile("00000001.jpg"))
        assertTrue(category.children.containsKey("13"))
        assertFalse(fixture.records.containsKey(12))
    }

    @Test
    fun scatteredPublicationRetriesAfterCommitFailureWithoutRemovingUnknownContent() {
        val fixture = Fixture()
        val stage = fixture.start(false)
        fixture.commitFails = true
        assertThrows(IOException::class.java) { fixture.files.finish(12, 2) }
        assertNotNull(stage.findFile("00000001.jpg"))
        fixture.target().put("notes.txt")
        fixture.commitFails = false
        fixture.files.finish(12, 2)
        assertTrue(fixture.files.verify(fixture.records.getValue(12)))
        fixture.target().put("12.cbz", byteArrayOf(9))
        fixture.files.deleteFiles(12)
        assertNull(fixture.roots.getValue("one").children.getValue("doujinshi").findFile("12"))
    }

    @Test
    fun foreignGalleryPathCannotBeReadOrDeleted() {
        val fixture = Fixture()
        fixture.start()
        fixture.records[12] = fixture.records.getValue(12).copy(dirname = "doujinshi/13")
        assertThrows(IllegalStateException::class.java) { fixture.files.deleteFiles(12) }
        assertThrows(IllegalStateException::class.java) { fixture.files.prepare(12, 2, "one", true) }
    }

    @Test
    fun failedRecursiveDeleteKeepsLocationForRetry() {
        val fixture = Fixture()
        fixture.start()
        fixture.files.finish(12, 2)
        val directory = fixture.target()
        val nested = directory.createDirectory("attachments")!!
        val file = nested.put("notes.txt")
        file.failDelete = true
        assertFalse(fixture.files.deleteFiles(12))
        assertTrue(fixture.records.containsKey(12))
        assertNotNull(fixture.roots.getValue("one").findFile("doujinshi"))
        file.failDelete = false
        assertTrue(fixture.files.deleteFiles(12))
        assertFalse(fixture.records.containsKey(12))
        assertNull(fixture.roots.getValue("one").children.getValue("doujinshi").findFile("12"))
    }
}
