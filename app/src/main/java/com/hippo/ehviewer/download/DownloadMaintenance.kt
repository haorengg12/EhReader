// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.encodeSpiderInfo
import com.hippo.ehviewer.spider.readFromCache
import com.hippo.unifile.UniFile
import com.hippo.unifile.openInputStream
import com.hippo.util.runSuspendCatching
import java.io.IOException
import kotlinx.coroutines.sync.Mutex

/** 设置里的手动恢复与清理共用互斥任务；只作用于用户当前选择的新版下载根。 */
internal object DownloadMaintenance {
    private val mutex = Mutex()

    suspend fun run(restore: Boolean): MaintenanceResult {
        if (!mutex.tryLock()) return MaintenanceResult(stopped = true)
        try {
            if (DownloadManager.hasActiveDownloads()) return MaintenanceResult(stopped = true)
            val selected = Settings.downloadLocation ?: throw IOException("No selected download root")
            val rootUri = selected.uri.toString()
            val maintenance = GalleryMaintenance(
                rootUri, DownloadStorage.storageFile(selected), EhDB::getDownloadLocation,
                { dir, gid -> identity(DownloadStorage.uniFile(dir), gid) },
                DownloadManager::containDownloadInfo, SpiderQueen::isInUse,
                { DownloadManager.hasActiveDownloads() || Settings.downloadLocation?.uri?.toString() != rootUri },
                { DownloadStorage.readable(DownloadStorage.uniFile(it)) },
                if (Settings.saveAsCbz) "cbz" else "images",
                existingAvailable = { location ->
                    if (location.phase == "complete") DownloadStorage.verify(location) else DownloadStorage.container(location.gid)?.isDirectory == true
                },
                beforeDelete = { DownloadStorage.removeReadCache(rootUri, it.gid) },
                onError = { Log.e("DownloadMaintenance", "Gallery maintenance failed", it) },
            )
            return if (restore) {
                maintenance.restore { candidate -> restoreCandidate(candidate, selected) }
            } else {
                maintenance.clean { entry ->
                    EhDB.getDownloadLocation(entry.gid)?.takeIf { it.rootUri == rootUri && it.dirname == entry.path }?.let {
                        EhDB.removeDownloadDirname(it.gid)
                    }
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun identity(directory: UniFile, gid: Long): RecoveryIdentity? {
        val marker = DownloadStorage.readRecoveryInfo(directory)
        check(marker == null || marker.gid == gid) { "Recovery file belongs to another gallery" }
        val metadata = EhDB.getGalleryMetadata(gid)
        check(marker?.token == null || metadata?.token == null || marker.token == metadata.token) { "Conflicting gallery token" }
        if (marker != null) return RecoveryIdentity(gid, marker.token ?: metadata?.token, marker.pages, marker.downloadFormat)
        val comic = directory.findFile("ComicInfo.xml")?.openInputStream()?.bufferedReader()?.use { GalleryDetailUrlParser.parse(it.readText()) }
        check(comic == null || comic.gid == gid) { "ComicInfo belongs to another gallery" }
        if (comic != null) return RecoveryIdentity(gid, comic.token, metadata?.pages ?: 0)
        if (metadata != null) return RecoveryIdentity(gid, metadata.token, metadata.pages ?: EhDB.getDownloadLocation(gid)?.expectedPages ?: 0, fromDisk = false)
        check(GalleryRecoveryFile.names.none { directory.findFile(it) != null }) { "Damaged recovery identity" }
        return null
    }

    private suspend fun restoreCandidate(candidate: RecoveryCandidate, selected: UniFile) {
        val location = candidate.location
        var directory = selected
        DownloadLayout.segments(location.dirname!!).forEach { segment ->
            directory = directory.findFile(segment) ?: throw IOException("Gallery directory disappeared")
        }
        val metadata = EhDB.getGalleryMetadata(location.gid)
        val category = DownloadLayout.categories.indexOf(location.dirname!!.substringBefore('/')).takeIf { it >= 0 }?.let { 1 shl it } ?: 0
        val view = metadata?.galleryInfo(EhDB.currentAccount()) ?: BaseGalleryInfo(gid = location.gid, token = candidate.identity.token, category = category)
        if (view.token == null) view.token = candidate.identity.token
        if (view.title.isNullOrBlank() && !view.token.isNullOrBlank()) {
            // 联网补全失败不阻止使用本地身份和经过校验的内容恢复下载。
            runSuspendCatching { EhEngine.fillGalleryListByApi(listOf(view), EhUrl.referer) }
                .onFailure { Log.e("DownloadMaintenance", "Metadata unavailable; restoring local identity", it) }
        }
        check(view.pages <= 0 || view.pages == location.expectedPages) { "Online/local page count mismatch" }
        view.pages = location.expectedPages
        val stored = readFromCache(location.gid) ?: DownloadStorage.readRecoveryInfo(directory)
        val spider = stored?.takeIf { it.gid == location.gid && it.token == view.token && it.pages == location.expectedPages }
            ?: SpiderInfo(location.gid, view.token, location.expectedPages)
        spider.downloadFormat = location.format
        check(!DownloadManager.hasActiveDownloads() && !SpiderQueen.isInUse(location.gid)) { "Gallery became active" }
        DownloadStorage.writeRecoveryInfo(directory, spider)
        EhDB.restoreDownload(view, location, candidate.finished, spider.encodeSpiderInfo(), spider.startPage)
    }
}
