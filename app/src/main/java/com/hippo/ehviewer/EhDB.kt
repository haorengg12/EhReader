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
package com.hippo.ehviewer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.paging.PagingSource
import androidx.room.Room.databaseBuilder
import com.hippo.ehviewer.EhApplication.Companion.ehDatabase
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.BasicDao
import com.hippo.ehviewer.dao.DownloadDirname
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.dao.DownloadRecord
import com.hippo.ehviewer.dao.EhDatabase
import com.hippo.ehviewer.dao.Filter
import com.hippo.ehviewer.dao.GalleryMetadata
import com.hippo.ehviewer.dao.HistoryInfo
import com.hippo.ehviewer.dao.HistoryRecord
import com.hippo.ehviewer.dao.LocalFavoriteInfo
import com.hippo.ehviewer.dao.LocalFavoriteRecord
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.dao.ReadingState
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.unifile.UniFile
import com.hippo.util.sendTo
import okhttp3.HttpUrl.Companion.toHttpUrl

object EhDB {
    private val TAG = EhDB::class.java.simpleName
    private val db = ehDatabase
    var onGalleryMetadataChanged: ((GalleryMetadata) -> Unit)? = null

    // Fix state
    @get:Synchronized
    val allDownloadInfo: List<DownloadInfo>
        get() = db.downloadsDao().list().onEach {
            if (it.state == DownloadInfo.STATE_WAIT || it.state == DownloadInfo.STATE_DOWNLOAD) {
                it.state = DownloadInfo.STATE_NONE
            }
        }

    /** 所有业务写入先建立 GID 主记录，再在同一事务保存业务行。 */
    @Synchronized
    fun putGalleryMetadata(info: GalleryInfo, url: String? = null, account: String? = currentAccount()) {
        require(info.gid > 0)
        val nested = db.inTransaction()
        var saved: GalleryMetadata? = null
        db.runInTransaction {
            val dao = db.galleryMetadataDao()
            val old = dao.load(info.gid)
            val persistedView = info is DownloadInfo || info is HistoryInfo || info is LocalFavoriteInfo
            if (old == null || !persistedView || url != null) {
                saved = (old ?: GalleryMetadata(info.gid)).merge(info, url, account)
                dao.put(saved)
            }
        }
        if (!nested) saved?.let { onGalleryMetadataChanged?.invoke(it) }
    }

    fun getGalleryMetadata(gid: Long): GalleryMetadata? = db.galleryMetadataDao().load(gid)

    fun currentAccount(): String? = com.hippo.ehviewer.client.EhCookieStore.getCookieValue(
        com.hippo.ehviewer.client.EhUrl.HOST_E.toHttpUrl(),
        com.hippo.ehviewer.client.EhCookieStore.KEY_IPB_MEMBER_ID,
    )

    fun getGalleryDetail(gid: Long): com.hippo.ehviewer.client.data.GalleryDetail? = getGalleryMetadata(gid)
        ?.takeIf { it.metadataState == "complete" }?.galleryDetail(currentAccount())

    fun getReadingState(gid: Long): ReadingState? = db.galleryMetadataDao().readingState(gid)

    @Synchronized
    fun putReadingState(gid: Long, page: Int? = null, spiderInfo: ByteArray? = null) {
        db.runInTransaction {
            val dao = db.galleryMetadataDao()
            if (dao.load(gid) == null) dao.put(GalleryMetadata(gid))
            val old = dao.readingState(gid) ?: ReadingState(gid)
            dao.putReadingState(old.copy(page = page ?: old.page, spiderInfo = spiderInfo ?: old.spiderInfo))
        }
    }

    @Synchronized
    fun updateDownloadInfo(downloadInfos: List<DownloadInfo>) {
        db.runInTransaction { downloadInfos.forEach { putDownloadInfo(it) } }
    }

    @Synchronized
    fun putDownloadInfo(info: DownloadInfo) {
        db.runInTransaction {
            putGalleryMetadata(info)
            val dao = db.downloadsDao()
            val record = dao.record(info.gid) ?: DownloadRecord(info.gid)
            // 普通下载状态更新保留导入来源及旧路径；新记录使用实体默认标记 false。
            dao.put(
                record.copy(
                    state = info.state,
                    remainingPages = info.legacy,
                    time = info.time,
                    label = info.label,
                    finishedPages = info.finished,
                    attemptedPages = info.downloaded,
                    totalPages = info.total,
                ),
            )
        }
    }

    @Synchronized
    fun removeDownloadInfo(downloadInfo: DownloadInfo) {
        db.downloadsDao().deleteByKey(downloadInfo.gid)
    }

    fun getDownloadLocation(gid: Long): DownloadDirname? = db.downloadDirnameDao().load(gid)

    @Synchronized
    fun putDownloadLocation(location: DownloadDirname) {
        db.runInTransaction {
            val metadata = db.galleryMetadataDao()
            if (metadata.load(location.gid) == null) metadata.put(GalleryMetadata(location.gid))
            val dao = db.downloadDirnameDao()
            if (dao.load(location.gid) == null) dao.insert(location) else dao.update(location)
        }
    }

    /** 文件已核验后，一次事务提交可读位置和完成状态；事务失败保留临时源文件。 */
    @Synchronized
    fun completeDownload(location: DownloadDirname) {
        db.runInTransaction {
            putDownloadLocation(location)
            db.downloadsDao().load(location.gid)?.let { info ->
                info.state = DownloadInfo.STATE_FINISH
                info.legacy = 0
                info.finished = location.expectedPages
                info.downloaded = location.expectedPages
                info.total = location.expectedPages
                putDownloadInfo(info)
            }
        }
    }

    @get:Synchronized
    val allDownloadDirname: List<DownloadDirname>
        get() = db.downloadDirnameDao().list()

    @Synchronized
    fun getDownloadDirname(gid: Long): String? {
        val dao = db.downloadDirnameDao()
        val raw = dao.load(gid)
        return raw?.dirname
    }

    @Synchronized
    fun putDownloadDirname(gid: Long, dirname: String?) {
        val location = getDownloadLocation(gid) ?: DownloadDirname(gid)
        location.dirname = dirname
        putDownloadLocation(location)
    }

    @Synchronized
    fun removeDownloadDirname(gid: Long) {
        val dao = db.downloadDirnameDao()
        dao.deleteByKey(gid)
    }

    @get:Synchronized
    val allDownloadLabelList: List<DownloadLabel>
        get() = db.downloadLabelDao().list()

    @Synchronized
    fun addDownloadLabel(label: String): DownloadLabel {
        val dao = db.downloadLabelDao()
        val raw = DownloadLabel()
        raw.label = label
        raw.time = System.currentTimeMillis()
        raw.id = dao.insert(raw)
        return raw
    }

    @Synchronized
    fun addDownloadLabel(raw: DownloadLabel): DownloadLabel {
        // Reset id
        raw.id = null
        val dao = db.downloadLabelDao()
        raw.id = dao.insert(raw)
        return raw
    }

    @Synchronized
    fun updateDownloadLabel(raw: DownloadLabel?) {
        val dao = db.downloadLabelDao()
        dao.update(raw!!)
    }

    @Synchronized
    fun moveDownloadLabel(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) {
            return
        }
        val reverse = fromPosition > toPosition
        val offset = if (reverse) toPosition else fromPosition
        val limit = if (reverse) fromPosition - toPosition + 1 else toPosition - fromPosition + 1
        val dao = db.downloadLabelDao()
        val list = dao.list(offset, limit)
        val step = if (reverse) 1 else -1
        val start = if (reverse) limit - 1 else 0
        val end = if (reverse) 0 else limit - 1
        val toTime = list[end].time
        var i = end
        while (if (reverse) i < start else i > 0) {
            val aTime = list[i].time
            val bTime = list[i + step].time
            list[i].time = if (aTime == bTime) bTime + step else bTime
            i += step
        }
        list[start].time = toTime
        dao.update(list)
    }

    @Synchronized
    fun removeDownloadLabel(raw: DownloadLabel?) {
        val dao = db.downloadLabelDao()
        dao.delete(raw!!)
    }

    @get:Synchronized
    val allLocalFavorites: List<GalleryInfo>
        get() {
            val dao = db.localFavoritesDao()
            val list = dao.list()
            return ArrayList<GalleryInfo>(list)
        }

    @Synchronized
    fun searchLocalFavorites(query: String): List<GalleryInfo> {
        val dao = db.localFavoritesDao()
        val list = dao.list("%$query%")
        return ArrayList<GalleryInfo>(list)
    }

    @Synchronized
    fun removeLocalFavorites(gid: Long) {
        db.localFavoritesDao().deleteByKey(gid)
    }

    @Synchronized
    fun removeLocalFavorites(gidArray: LongArray) {
        val dao = db.localFavoritesDao()
        for (gid in gidArray) {
            dao.deleteByKey(gid)
        }
    }

    @Synchronized
    fun containLocalFavorites(gid: Long): Boolean {
        val dao = db.localFavoritesDao()
        return dao.contains(gid)
    }

    @Synchronized
    fun putLocalFavorites(galleryInfo: GalleryInfo) {
        db.runInTransaction {
            putGalleryMetadata(galleryInfo)
            val dao = db.localFavoritesDao()
            if (!dao.contains(galleryInfo.gid)) {
                dao.put(LocalFavoriteRecord(galleryInfo.gid, (galleryInfo as? LocalFavoriteInfo)?.time ?: System.currentTimeMillis()))
            }
        }
    }

    @Synchronized
    fun putLocalFavorites(galleryInfoList: List<GalleryInfo>) {
        for (gi in galleryInfoList) {
            putLocalFavorites(gi)
        }
    }

    @get:Synchronized
    val allQuickSearch: List<QuickSearch>
        get() {
            val dao = db.quickSearchDao()
            return dao.list()
        }

    @Synchronized
    fun insertQuickSearch(quickSearch: QuickSearch) {
        val dao = db.quickSearchDao()
        quickSearch.id = null
        quickSearch.time = System.currentTimeMillis()
        quickSearch.id = dao.insert(quickSearch)
    }

    @Synchronized
    fun importQuickSearch(quickSearchList: List<QuickSearch?>) {
        val dao = db.quickSearchDao()
        for (quickSearch in quickSearchList) {
            dao.insert(quickSearch!!)
        }
    }

    @Synchronized
    fun deleteQuickSearch(quickSearch: QuickSearch?) {
        quickSearch ?: return
        val dao = db.quickSearchDao()
        dao.delete(quickSearch)
    }

    @Synchronized
    fun moveQuickSearch(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) {
            return
        }
        val reverse = fromPosition > toPosition
        val offset = if (reverse) toPosition else fromPosition
        val limit = if (reverse) fromPosition - toPosition + 1 else toPosition - fromPosition + 1
        val dao = db.quickSearchDao()
        val list = dao.list(offset, limit)
        val step = if (reverse) 1 else -1
        val start = if (reverse) limit - 1 else 0
        val end = if (reverse) 0 else limit - 1
        val toTime = list[end].time
        var i = end
        while (if (reverse) i < start else i > 0) {
            val aTime = list[i].time
            val bTime = list[i + step].time
            list[i].time = if (aTime == bTime) bTime + step else bTime
            i += step
        }
        list[start].time = toTime
        dao.update(list)
    }

    @get:Synchronized
    val historyLazyList: PagingSource<Int, HistoryInfo>
        get() = db.historyDao().listLazy()

    @Synchronized
    fun putHistoryInfo(galleryInfo: GalleryInfo) {
        db.runInTransaction {
            putGalleryMetadata(galleryInfo)
            db.historyDao().put(HistoryRecord(galleryInfo.gid, System.currentTimeMillis()))
        }
    }

    @Synchronized
    fun updateHistoryFavSlot(gid: Long, slot: Int) {
        val metadata = getGalleryMetadata(gid) ?: return
        val account = com.hippo.ehviewer.client.EhCookieStore.getCookieValue(
            com.hippo.ehviewer.client.EhUrl.HOST_E.toHttpUrl(),
            com.hippo.ehviewer.client.EhCookieStore.KEY_IPB_MEMBER_ID,
        )
        val snapshot = if (account == metadata.accountId) org.json.JSONObject(metadata.accountSnapshot ?: "{}") else org.json.JSONObject()
        snapshot.put("favoriteSlot", slot).put("favorited", slot >= 0)
        db.galleryMetadataDao().put(metadata.copy(accountId = account, accountSnapshot = snapshot.toString()))
    }

    @Synchronized
    fun putHistoryInfo(historyInfoList: List<HistoryInfo>) {
        db.runInTransaction {
            historyInfoList.forEach {
                putGalleryMetadata(it)
                if (db.historyDao().load(it.gid) == null) db.historyDao().put(HistoryRecord(it.gid, it.time))
            }
        }
    }

    @Synchronized
    fun deleteHistoryInfo(info: HistoryInfo?) {
        val dao = db.historyDao()
        dao.deleteByKey(info!!.gid)
    }

    @Synchronized
    fun clearHistoryInfo() {
        val dao = db.historyDao()
        dao.deleteAll()
    }

    @get:Synchronized
    val allFilter: List<Filter>
        get() = db.filterDao().list()

    @Synchronized
    fun addFilter(filter: Filter): Boolean {
        val existFilter: Filter? = try {
            db.filterDao().load(filter.text!!, filter.mode)
        } catch (_: Exception) {
            null
        }
        return if (existFilter == null) {
            filter.id = null
            filter.id = db.filterDao().insert(filter)
            true
        } else {
            false
        }
    }

    @Synchronized
    fun deleteFilter(filter: Filter) {
        db.filterDao().delete(filter)
    }

    @Synchronized
    fun triggerFilter(filter: Filter) {
        filter.enable = filter.enable?.not() == true
        db.filterDao().update(filter)
    }

    private fun <T> copyDao(from: BasicDao<T>, to: BasicDao<T>) {
        val list = from.list()
        for (item in list) to.insert(item)
    }

    /** 新版备份包括全部 GID 关系、位置及阅读状态；不含图片与目录授权。 */
    @Synchronized
    fun exportDB(context: Context, uri: Uri): Boolean {
        val name = "ehreader.export.db"
        return runCatching {
            context.deleteDatabase(name)
            databaseBuilder(context, EhDatabase::class.java, name).setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build().use { target ->
                db.runInTransaction {
                    target.runInTransaction {
                        db.galleryMetadataDao().list().forEach { target.galleryMetadataDao().put(it) }
                        db.downloadsDao().records().forEach { target.downloadsDao().put(it) }
                        db.historyDao().records().forEach { target.historyDao().put(it) }
                        db.localFavoritesDao().records().forEach { target.localFavoritesDao().put(it) }
                        db.bookmarksBao().records().forEach { target.bookmarksBao().put(it) }
                        db.galleryMetadataDao().readingStates().forEach { target.galleryMetadataDao().putReadingState(it) }
                        copyDao(db.downloadLabelDao(), target.downloadLabelDao())
                        copyDao(db.downloadDirnameDao(), target.downloadDirnameDao())
                        copyDao(db.quickSearchDao(), target.quickSearchDao())
                        copyDao(db.filterDao(), target.filterDao())
                    }
                }
            }
            UniFile.fromFile(context.getDatabasePath(name))!! sendTo UniFile.fromUri(context, uri)!!
            true
        }.onFailure { Log.e(TAG, "Backup failed", it) }.getOrDefault(false).also { context.deleteDatabase(name) }
    }

    /** 先验证新版备份所有表，再整体事务合并；旧 schema 不自动转换，失败全部回滚。 */
    @Synchronized
    fun importDB(context: Context, uri: Uri): String? {
        val name = "ehreader.restore.db"
        context.deleteDatabase(name)
        return runCatching {
            check(!DownloadManager.hasActiveDownloads()) { "Pause downloads before restoring" }
            databaseBuilder(context, EhDatabase::class.java, name)
                .createFromInputStream { context.contentResolver.openInputStream(uri) }.build().use { source ->
                    val metadata = source.galleryMetadataDao().list()
                    val downloads = source.downloadsDao().records()
                    val history = source.historyDao().records()
                    val favorites = source.localFavoritesDao().records()
                    val bookmarks = source.bookmarksBao().records()
                    val reading = source.galleryMetadataDao().readingStates()
                    val locations = source.downloadDirnameDao().list()
                    val labels = source.downloadLabelDao().list()
                    val searches = source.quickSearchDao().list()
                    val filters = source.filterDao().list()
                    db.runInTransaction {
                        metadata.forEach { if (getGalleryMetadata(it.gid) == null) db.galleryMetadataDao().put(it) }
                        downloads.forEach { if (db.downloadsDao().load(it.gid) == null) db.downloadsDao().put(it.copy(state = DownloadInfo.STATE_NONE)) }
                        history.forEach { if (db.historyDao().load(it.gid) == null) db.historyDao().put(it) }
                        favorites.forEach { if (!db.localFavoritesDao().contains(it.gid)) db.localFavoritesDao().put(it) }
                        bookmarks.forEach { if (db.bookmarksBao().load(it.gid) == null) db.bookmarksBao().put(it) }
                        reading.forEach { if (getReadingState(it.gid) == null) db.galleryMetadataDao().putReadingState(it) }
                        locations.forEach { if (getDownloadLocation(it.gid) == null) putDownloadLocation(it.copy(rootUri = null)) }
                        labels.forEach { label -> if (db.downloadLabelDao().list().none { it.label == label.label }) addDownloadLabel(label) }
                        searches.forEach { item ->
                            if (db.quickSearchDao().list().none { it.name == item.name }) {
                                item.id = null
                                db.quickSearchDao().insert(item)
                            }
                        }
                        filters.forEach { item -> if (item !in db.filterDao().list()) addFilter(item) }
                    }
                }
            null
        }.onFailure { Log.e(TAG, "Restore failed", it) }
            .getOrElse { context.getString(R.string.settings_advanced_import_data_cant_read) }
            .also { context.deleteDatabase(name) }
    }
}

private inline fun <T> EhDatabase.use(block: (EhDatabase) -> T): T = try {
    block(this)
} finally {
    close()
}
