/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.spider

import android.util.Log
import com.hippo.ehviewer.EhApplication.Companion.imageCache as sCache
import com.hippo.ehviewer.EhApplication.Companion.nonCacheOkHttpClient
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhRequestBuilder
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.getImageKey
import com.hippo.ehviewer.coil.edit
import com.hippo.ehviewer.coil.read
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadStorage
import com.hippo.ehviewer.download.GalleryArchive
import com.hippo.ehviewer.gallery.GalleryProvider2.Companion.SUPPORT_IMAGE_EXTENSIONS
import com.hippo.image.UniFileSource
import com.hippo.unifile.UniFile
import com.hippo.unifile.openInputStream
import com.hippo.unifile.openOutputStream
import com.hippo.unifile.sha1
import com.hippo.util.runInterruptibleOkio
import com.hippo.util.runSuspendCatching
import com.hippo.util.sendTo
import com.hippo.yorozuya.FileUtils
import java.io.IOException
import java.util.Locale
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.buffer
import okio.sink

class SpiderDen(private val mGalleryInfo: GalleryInfo) {
    private val fileHashRegex = Regex("/h/([0-9a-f]{40})")
    private val mGid = mGalleryInfo.gid

    @Volatile
    var downloadDir: UniFile? = null

    @Volatile
    @SpiderQueen.Mode
    var mode = SpiderQueen.MODE_READ
        set(value) {
            field = value
            if (field == SpiderQueen.MODE_DOWNLOAD) downloadDir = DownloadStorage.prepare(mGalleryInfo)
        }

    private fun containInCache(index: Int): Boolean {
        val key = getImageKey(mGid, index)
        if (DownloadStorage.isCompletedArchive(mGid)) return DownloadStorage.readArchivePage(mGid, index) != null
        return sCache.openSnapshot(key)?.use { true } == true
    }

    private fun containInDownloadDir(index: Int): Boolean {
        if (DownloadStorage.isCompletedArchive(mGid)) return DownloadStorage.readArchivePage(mGid, index) != null
        downloadDir = DownloadStorage.imageDirectory(mGid) ?: downloadDir
        val dir = downloadDir ?: return false
        return findImageFile(dir, index)?.let(DownloadStorage::readable) == true
    }

    private fun copyFromCacheToDownloadDir(index: Int, skip: Boolean): Boolean {
        if (DownloadStorage.isCompletedArchive(mGid)) return DownloadStorage.readArchivePage(mGid, index) != null
        val dir = downloadDir ?: return false
        // Find image file in cache
        val key = getImageKey(mGid, index)
        return runCatching {
            sCache.read(key) {
                // Get extension
                val extension = fixExtension("." + metadata.toFile().readText())
                // Don't copy from cache if `download original image` enabled, ignore gif
                if (skip && extension != GIF_IMAGE_EXTENSION) {
                    return false
                }
                // Copy from cache to download dir
                return copyImage(UniFile.fromFile(data.toFile())!!, dir, index, extension)
            }
        }.getOrElse {
            Log.e(TAG, "Error", it)
            false
        }
    }

    fun copyFromUniFileToDownloadDir(oldDir: UniFile, oldIndex: Int, index: Int): Boolean {
        val dir = downloadDir ?: return false
        return runCatching {
            val oldFile = findImageFile(oldDir, oldIndex) ?: return false
            val extension = oldFile.name.let { name -> FileUtils.getExtensionFromFilename(name) }
            copyImage(oldFile, dir, index, ".$extension")
        }.getOrElse {
            Log.e(TAG, "Error", it)
            false
        }
    }

    private fun copyImage(source: UniFile, dir: UniFile, index: Int, extension: String): Boolean {
        val name = perFilename(index, extension)
        val part = dir.createFile("$name.part") ?: return false
        try {
            source sendTo part
            check(source.openInputStream().use(GalleryArchive::digest) == part.openInputStream().use(GalleryArchive::digest))
            check(DownloadStorage.readable(part))
            dir.findFile(name)?.let { check(it.delete()) }
            return part.renameTo(name)
        } finally {
            if (part.name?.endsWith(".part") == true) part.delete()
        }
    }

    fun copyFromArchive(gid: Long, oldIndex: Int, index: Int): Boolean = runCatching {
        val archive = DownloadStorage.archive(gid) ?: return false
        val dir = downloadDir ?: return false
        GalleryArchive.copyPage(archive.openInputStream(), oldIndex) { extension ->
            (dir.createFile(perFilename(index, ".$extension")) ?: throw IOException("Cannot copy archive page")).openOutputStream()
        }
    }.getOrDefault(false)

    operator fun contains(index: Int): Boolean = when (mode) {
        SpiderQueen.MODE_READ -> {
            containInCache(index) || containInDownloadDir(index)
        }
        SpiderQueen.MODE_DOWNLOAD -> {
            containInDownloadDir(index) || copyFromCacheToDownloadDir(index, Settings.skipCopyImage)
        }
        else -> {
            false
        }
    }

    private fun removeFromCache(index: Int): Boolean {
        val key = getImageKey(mGid, index)
        return sCache.remove(key)
    }

    private fun removeFromDownloadDir(index: Int): Boolean {
        downloadDir = DownloadStorage.imageDirectory(mGid) ?: downloadDir
        return downloadDir?.let { findImageFile(it, index)?.delete() } == true
    }

    fun remove(index: Int): Boolean = removeFromCache(index) or removeFromDownloadDir(index)

    private fun findDownloadFileForIndex(index: Int, extension: String): UniFile? {
        if (DownloadStorage.isCompletedArchive(mGid)) downloadDir = DownloadStorage.imageDirectory(mGid)
        val dir = downloadDir ?: return null
        val ext = fixExtension(".$extension")
        return dir.createFile(perFilename(index, ext) + ".part")
    }

    @Throws(IOException::class)
    suspend fun saveImageFromUrl(
        url: String,
        referer: String?,
        dir: UniFile,
        filename: String,
    ): UniFile? {
        nonCacheOkHttpClient.newCall(EhRequestBuilder(url, referer).build()).executeAsync().use {
            if (it.code >= 400) return null
            val ext = it.body.contentType()?.subtype ?: "jpg"
            val dst = dir.subFile("$filename.$ext") ?: return null
            return runSuspendCatching {
                var ret = 0L
                runInterruptibleOkio {
                    dst.openOutputStream().sink().buffer().use { sink ->
                        it.body.source().use { source ->
                            while (true) {
                                val bytesRead = source.read(sink.buffer, 8192)
                                if (bytesRead == -1L) break
                                ret += bytesRead
                                sink.emitCompleteSegments()
                            }
                        }
                    }
                }
                if (ret == it.body.contentLength()) dst else null
            }.onFailure { e ->
                Log.e(TAG, "Error", e)
            }.getOrNull()
        }
    }

    @Throws(IOException::class)
    suspend fun makeHttpCallAndSaveImage(
        index: Int,
        url: String,
        referer: String?,
        notifyProgress: (Long, Long, Int) -> Unit,
    ): Boolean {
        // TODO: Use HttpEngine[https://developer.android.com/reference/android/net/http/HttpEngine] directly here if available
        // Since we don't want unnecessary copy between jvm heap & native heap
        nonCacheOkHttpClient.newCall(EhRequestBuilder(url, referer).build()).executeAsync().use {
            if (it.code >= 400) return false
            return saveFromHttpResponse(index, it, notifyProgress)
        }
    }

    private suspend fun saveFromHttpResponse(index: Int, response: Response, notifyProgress: (Long, Long, Int) -> Unit): Boolean {
        val url = response.request.url.toString()
        val extension = response.body.contentType()?.subtype ?: "jpg"
        val length = response.body.contentLength()

        suspend fun doSave(outFile: UniFile): Long {
            var ret = 0L
            runInterruptibleOkio {
                outFile.openOutputStream().sink().buffer().use { sink ->
                    response.body.source().use { source ->
                        while (true) {
                            val bytesRead = source.read(sink.buffer, 8192)
                            if (bytesRead == -1L) break
                            ret += bytesRead
                            sink.emitCompleteSegments()
                            notifyProgress(length, ret, bytesRead.toInt())
                        }
                    }
                }
                fileHashRegex.find(url)?.let {
                    val expected = it.groupValues[1]
                    val actual = outFile.sha1()
                    check(expected == actual) { "File hash mismatch: expected $expected, but got $actual\nURL: $url" }
                }
            }
            return ret
        }

        findDownloadFileForIndex(index, extension)?.let { part ->
            try {
                val received = doSave(part)
                check(length < 0 || received == length) { "Incomplete image response" }
                check(DownloadStorage.readable(part)) { "Unreadable downloaded image" }
                val finalName = perFilename(index, fixExtension(".$extension"))
                COMPAT_IMAGE_EXTENSIONS.forEach { ext -> downloadDir?.findFile(perFilename(index, ext))?.let { check(it.delete()) } }
                check(part.renameTo(finalName)) { "Cannot publish downloaded page" }
                return true
            } finally {
                if (part.name?.endsWith(".part") == true) part.delete()
            }
        }

        // Read Mode, allow save to cache
        if (mode == SpiderQueen.MODE_READ) {
            val key = getImageKey(mGid, index)
            var received: Long = 0
            runSuspendCatching {
                sCache.edit(key) {
                    metadata.toFile().writeText(extension)
                    received = doSave(UniFile.fromFile(data.toFile())!!)
                }
            }.onFailure {
                Log.e(TAG, "Error", it)
            }.onSuccess {
                return length < 0 || received == length
            }
        }

        return false
    }

    fun saveToUniFile(index: Int, file: UniFile): Boolean {
        if (DownloadStorage.isCompletedArchive(mGid)) {
            val page = DownloadStorage.readArchivePage(mGid, index) ?: return false
            return runCatching {
                page sendTo file
                true
            }.onFailure { Log.e(TAG, "Cannot save archive page", it) }.getOrDefault(false)
        }
        val key = getImageKey(mGid, index)

        // Read from diskCache first
        sCache.read(key) {
            runCatching {
                UniFile.fromFile(data.toFile())!! sendTo file
                return true
            }.onFailure {
                Log.e(TAG, "Error", it)
                return false
            }
        }

        // Read from download dir
        runCatching {
            findImageFile(downloadDir!!, index)!! sendTo file
        }.onFailure {
            Log.e(TAG, "Error", it)
            return false
        }.onSuccess {
            return true
        }
        return false
    }

    fun getExtension(index: Int): String? {
        if (DownloadStorage.isCompletedArchive(mGid)) {
            return DownloadStorage.readArchivePage(mGid, index)?.name?.substringAfterLast('.')
        }
        val key = getImageKey(mGid, index)
        return sCache.openSnapshot(key)?.use { it.metadata.toFile().readText() }
            ?: downloadDir?.let { findImageFile(it, index) }
                ?.name.let { FileUtils.getExtensionFromFilename(it) }
    }

    fun getImageSource(index: Int): UniFileSource? {
        downloadDir = DownloadStorage.imageDirectory(mGid) ?: downloadDir
        if (DownloadStorage.isCompletedArchive(mGid)) {
            val page = DownloadStorage.readArchivePage(mGid, index) ?: return null
            return object : UniFileSource {
                override val source = page
                override fun close() {}
            }
        }
        if (mode == SpiderQueen.MODE_READ) {
            val key = getImageKey(mGid, index)
            sCache.openSnapshot(key)?.let {
                val source = UniFile.fromFile(it.data.toFile())!!
                return object : UniFileSource, AutoCloseable by it {
                    override val source = source
                }
            }
        }
        val dir = downloadDir ?: return null
        val source = findImageFile(dir, index) ?: return null
        return object : UniFileSource {
            override val source = source
            override fun close() {}
        }
    }

    companion object {
        private val TAG = SpiderDen::class.java.simpleName
        private val COMPAT_IMAGE_EXTENSIONS = com.hippo.ehviewer.download.DownloadLayout.extensions.map { ".$it" }
        private val GIF_IMAGE_EXTENSION = SUPPORT_IMAGE_EXTENSIONS[2]

        /**
         * @param extension with dot
         */
        private fun fixExtension(extension: String): String = extension.lowercase(Locale.ROOT).takeIf { COMPAT_IMAGE_EXTENSIONS.contains(it) }
            ?: SUPPORT_IMAGE_EXTENSIONS[0]

        fun findImageFile(dir: UniFile, index: Int): UniFile? = COMPAT_IMAGE_EXTENSIONS.firstNotNullOfOrNull { dir.findFile(perFilename(index, it)) }

        /**
         * @param extension with dot
         */
        fun perFilename(index: Int, extension: String?): String = String.format(Locale.US, "%08d%s", index + 1, extension)

        fun getGalleryDownloadDir(gid: Long): UniFile? = DownloadStorage.imageDirectory(gid)
    }
}
