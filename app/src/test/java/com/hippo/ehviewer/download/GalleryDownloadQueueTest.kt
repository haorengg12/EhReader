// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadDirname
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDownloadQueueTest {
    @Test
    fun raisingAndLoweringLimitPreservesOrderAndExistingTasks() {
        val queue = GalleryDownloadQueue<Any>()
        val started = mutableListOf<Any>()
        fun fill(limit: Int) = queue.fill(limit, { Any() }, started::add)
        (1L..5L).forEach(queue::enqueue)
        fill(1)
        assertEquals(listOf(1L), queue.running.keys.toList())
        queue.enqueue(1)
        queue.enqueue(2)
        fill(2)
        assertEquals(listOf(1L, 2L), queue.running.keys.toList())
        queue.release(2)
        fill(2)
        assertEquals(listOf(1L, 3L), queue.running.keys.toList())
        fill(1)
        queue.release(1)
        fill(1)
        assertEquals(listOf(3L), queue.running.keys.toList())
        queue.release(3)
        fill(1)
        assertEquals(listOf(4L), queue.running.keys.toList())
        assertEquals(4, started.size)
        assertEquals(setOf(5L), queue.waitingIds)
    }

    @Test
    fun stoppingGalleryWaitsBeforeRestartAndOldCallbacksAreIgnored() {
        val queue = GalleryDownloadQueue<Any>()
        queue.enqueue(7)
        queue.fill(1, { Any() }) {}
        val old = queue.release(7)!!
        queue.enqueue(7)
        queue.enqueue(8)
        assertFalse(queue.fill(2, { null }) { error("Cannot start a stopping gallery") })
        assertFalse(queue.isIdle)
        assertEquals(listOf(7L, 8L), queue.waitingIds.toList())
        queue.fill(2, { Any() }) {}
        assertFalse(queue.isCurrent(7, old))
        assertFalse(queue.isCurrent(8, queue.running.getValue(7)))
        assertTrue(queue.isCurrent(7, queue.running.getValue(7)))
    }

    @Test
    fun anotherGalleryCanPrepareAndPublishWhileAnArchiveIsWriting() {
        val records = ConcurrentHashMap<Long, DownloadDirname>()
        val root = MemoryGalleryFile("root")
        val files = GalleryFiles({ records[it]?.copy() }, { records[it.gid] = it.copy() }, { records[it.gid] = it.copy() }, { root }, { true })
        fun prepare(gid: Long) {
            files.prepare(gid, 4, "root", true).createFile("00000001.jpg")!!.openOutputStream().use { it.write(1) }
        }
        prepare(1)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                files.finish(1, 1, onStage = { stage ->
                    if (stage == "write CBZ") {
                        entered.countDown()
                        resume.await()
                    }
                })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            executor.submit {
                prepare(2)
                files.finish(2, 1)
            }.get(5, TimeUnit.SECONDS)
            assertTrue(files.verify(records.getValue(2)))
            assertEquals("publishing", records.getValue(1).phase)
            resume.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertTrue(files.verify(records.getValue(1)))
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun tenSlotsSupportDeletionFailedPublicationAndBatchPause() {
        val queue = GalleryDownloadQueue<GalleryFile>()
        val root = MemoryGalleryFile("root")
        val records = mutableMapOf<Long, DownloadDirname>()
        val files = GalleryFiles(
            { records[it]?.copy() },
            { records[it.gid] = it.copy() },
            { records[it.gid] = it.copy() },
            { root },
            { it.openInputStream().use { stream -> stream.read() >= 0 } },
        )
        fun fill() = queue.fill(10, { gid -> files.prepare(gid, 4, "root", true) }) {}
        (1L..13L).forEach(queue::enqueue)
        fill()
        assertEquals((1L..10L).toList(), queue.running.keys.toList())
        queue.release(4)
        assertTrue(files.deleteFiles(4))
        fill()
        assertEquals(10, queue.running.size)
        assertTrue(11L in queue.running)

        // 写入并验证 CBZ 后释放该 GID；另一部缺页失败后保留临时文件。
        val good = queue.running.getValue(1)
        good.createFile("00000001.jpg")!!.openOutputStream().use { it.write(1) }
        files.finish(1, 1)
        assertTrue(files.verify(records.getValue(1)))
        queue.release(1)
        fill()
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { files.finish(2, 1) }
        assertEquals("downloading", records.getValue(2).phase)
        queue.release(2)
        fill()
        assertEquals(10, queue.running.size)
        assertTrue(13L in queue.running)
        queue.enqueue(14)
        queue.waitingIds.toList().forEach(queue::removeWaiting)
        queue.running.keys.toList().forEach(queue::release)
        assertTrue(queue.isIdle)
        assertTrue(files.verify(records.getValue(1)))
    }
}
