// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.decodeSpiderInfo
import com.hippo.ehviewer.spider.encodeSpiderInfo
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryRecoveryFileTest {
    @Test fun publishesAndReplacesMarkerWithoutLeavingOldCopies() {
        val directory = MemoryGalleryFile("123")
        GalleryRecoveryFile.write(directory, byteArrayOf(1, 2))
        GalleryRecoveryFile.write(directory, byteArrayOf(3, 4))
        assertArrayEquals(byteArrayOf(3, 4), directory.children.getValue(".ehreader").data)
        assertEquals(setOf(".ehreader"), directory.children.keys)
    }

    @Test fun failedWriteRetainsPreviousMarker() {
        val directory = MemoryGalleryFile("123")
        directory.put(".ehreader", byteArrayOf(1, 2))
        directory.failOutput = true
        assertThrows(Exception::class.java) { GalleryRecoveryFile.write(directory, byteArrayOf(3, 4)) }
        assertArrayEquals(byteArrayOf(1, 2), directory.children.getValue(".ehreader").data)
    }

    @Test fun failedRenameRetainsPreviousMarkerAndCompleteRetrySource() {
        val directory = MemoryGalleryFile("123")
        directory.put(".ehreader", byteArrayOf(1, 2))
        directory.put(".ehreader.part").failRename = true
        assertThrows(Exception::class.java) { GalleryRecoveryFile.write(directory, byteArrayOf(3, 4)) }
        assertArrayEquals(byteArrayOf(1, 2), directory.children.getValue(".ehreader").data)
        assertArrayEquals(byteArrayOf(3, 4), directory.children.getValue(".ehreader.part").data)
        directory.children.getValue(".ehreader.part").failRename = false
        GalleryRecoveryFile.write(directory, byteArrayOf(3, 4))
        assertEquals(setOf(".ehreader"), directory.children.keys)
    }

    @Test fun cborRoundTripPreservesIdentityResumeTokensAndFrozenFormat() {
        val original = SpiderInfo(123, "0123456789", 3, mutableMapOf(0 to "page0", 2 to "page2"), 1, downloadFormat = "images")
        val restored = decodeSpiderInfo(original.encodeSpiderInfo())!!
        assertEquals(123L, restored.gid)
        assertEquals(original.token, restored.token)
        assertEquals(original.pTokenMap, restored.pTokenMap)
        assertTrue(restored.pTokenMap is ConcurrentHashMap)
        assertEquals(1, restored.startPage)
        assertEquals("images", restored.downloadFormat)
    }

    @Test fun readsOldEncodingWithNoFormatAndRejectsInvalidIdentities() {
        val old = SpiderInfo(123, "0123456789", 2)
        assertNull(decodeSpiderInfo(old.encodeSpiderInfo())!!.downloadFormat)
        val legacy = "VERSION2\n1\n123\n0123456789\n0\n1\n40\n2\n0 page0\n"
        assertEquals(1, decodeSpiderInfo(legacy.toByteArray())!!.startPage)
        for (invalid in listOf(SpiderInfo(-1, "token", 2), SpiderInfo(123, "token", 0), SpiderInfo(123, "token", 2, startPage = 2), SpiderInfo(123, "token", 2, mutableMapOf(2 to "bad")))) {
            assertNull(decodeSpiderInfo(invalid.encodeSpiderInfo()))
        }
        assertNull(decodeSpiderInfo(byteArrayOf(1, 2, 3)))
    }
}
