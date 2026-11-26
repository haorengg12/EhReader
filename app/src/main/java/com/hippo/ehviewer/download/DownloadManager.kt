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
package com.hippo.ehviewer.download

import android.util.Log
import android.util.SparseLongArray
import androidx.collection.LongSparseArray
import androidx.collection.keyIterator
import androidx.core.util.size
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.SpiderQueen.OnSpiderListener
import com.hippo.ehviewer.spider.readFromUniFile
import com.hippo.ehviewer.spider.saveToUniFile
import com.hippo.image.Image
import com.hippo.yorozuya.MathUtils
import com.hippo.yorozuya.ObjectUtils
import com.hippo.yorozuya.SimpleHandler
import com.hippo.yorozuya.collect.LongList
import java.util.LinkedList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object DownloadManager {
    // All download info list
    private val mAllInfoList: LinkedList<DownloadInfo>

    // All download info map
    private val mAllInfoMap: LongSparseArray<DownloadInfo>

    // label and info list map, without default label info list
    private val mMap: MutableMap<String?, LinkedList<DownloadInfo>>

    // All labels without default label
    private val mLabelList: MutableList<DownloadLabel>

    // Store download info with default label
    private val mDefaultInfoList: LinkedList<DownloadInfo>

    private val queue = GalleryDownloadQueue<GalleryTask>()
    private val retryFill = Runnable { ensureDownload() }
    private val mDownloadInfoListeners: MutableList<DownloadInfoListener?>
    private var mDownloadListener: DownloadListener? = null

    init {
        // Get all labels
        val labels = EhDB.allDownloadLabelList.toMutableList()
        mLabelList = labels

        // Create list for each label
        val map = HashMap<String?, LinkedList<DownloadInfo>>()
        mMap = map
        for ((_, label1) in labels) {
            map[label1] = LinkedList()
        }

        // Create default for non tag
        mDefaultInfoList = LinkedList()

        // Get all info
        val allInfoList = EhDB.allDownloadInfo
        mAllInfoList = LinkedList(allInfoList)

        // Create all info map
        val allInfoMap = LongSparseArray<DownloadInfo>(allInfoList.size + 10)
        mAllInfoMap = allInfoMap
        for (info in allInfoList) {

            // Add to all info map
            allInfoMap.put(info.gid, info)

            // Add to each label list
            val label = info.label

            var list = getInfoListForLabel(label)
            if (list == null) {
                // Can't find the label in label list
                list = LinkedList()
                map[label] = list
                if (!containLabel(label)) {
                    // Add label to DB and list
                    labels.add(EhDB.addDownloadLabel(label!!))
                }
            }
            list.add(info)
        }
        mDownloadInfoListeners = ArrayList()
        EhDB.onGalleryMetadataChanged = { metadata ->
            SimpleHandler.getInstance().post {
                mAllInfoMap[metadata.gid]?.let { info ->
                    info.token = metadata.token
                    info.title = metadata.title
                    info.titleJpn = metadata.titleJpn
                    info.thumb = metadata.thumb
                    info.category = metadata.category ?: 0
                    info.posted = metadata.posted
                    info.uploader = metadata.uploader
                    info.rating = metadata.rating ?: Float.NaN
                    info.simpleLanguage = metadata.simpleLanguage
                    getInfoListForLabel(info.label)?.let { list -> mDownloadInfoListeners.forEach { it?.onUpdate(info, list) } }
                }
            }
        }
    }

    fun hasActiveDownloads(): Boolean = !queue.isIdle

    /** 备份事务完成后重建展示索引，不自动加入运行队列。 */
    fun reloadFromDatabase() {
        check(!hasActiveDownloads())
        mLabelList.clear()
        mLabelList.addAll(EhDB.allDownloadLabelList)
        mMap.clear()
        mLabelList.forEach { mMap[it.label] = LinkedList() }
        mDefaultInfoList.clear()
        mAllInfoList.clear()
        mAllInfoMap.clear()
        EhDB.allDownloadInfo.forEach { info ->
            mAllInfoList.add(info)
            mAllInfoMap.put(info.gid, info)
            val list = if (info.label == null) mDefaultInfoList else mMap.getOrPut(info.label) { LinkedList() }
            list.add(info)
        }
        mDownloadInfoListeners.forEach { it?.onReload() }
    }

    private fun getInfoListForLabel(label: String?): LinkedList<DownloadInfo>? = if (label == null) {
        mDefaultInfoList
    } else {
        mMap[label]
    }

    fun containLabel(label: String?): Boolean {
        if (label == null) {
            return false
        }
        for ((_, label1) in mLabelList) {
            if (label == label1) {
                return true
            }
        }
        return false
    }

    fun containDownloadInfo(gid: Long): Boolean = mAllInfoMap.indexOfKey(gid) >= 0

    val labelList: List<DownloadLabel>
        get() = mLabelList
    val allDownloadInfoList: MutableList<DownloadInfo>
        get() = mAllInfoList
    val defaultDownloadInfoList: MutableList<DownloadInfo>
        get() = mDefaultInfoList

    fun getLabelDownloadInfoList(label: String?): MutableList<DownloadInfo>? = mMap[label]

    fun getDownloadInfo(gid: Long): DownloadInfo? = mAllInfoMap[gid]

    fun getDownloadState(gid: Long): Int {
        val info = mAllInfoMap[gid]
        return info?.state ?: DownloadInfo.STATE_INVALID
    }

    fun addDownloadInfoListener(downloadInfoListener: DownloadInfoListener?) {
        mDownloadInfoListeners.add(downloadInfoListener)
    }

    fun removeDownloadInfoListener(downloadInfoListener: DownloadInfoListener?) {
        mDownloadInfoListeners.remove(downloadInfoListener)
    }

    fun setDownloadListener(listener: DownloadListener?) {
        mDownloadListener = listener
    }

    val runningCount: Int get() = queue.running.size
    val firstRunning: DownloadInfo? get() = queue.running.values.firstOrNull()?.info

    /** 等待队首释放写入权限时只保留一次延迟补位；并发上限取当前设置。 */
    fun ensureDownload() {
        val handler = SimpleHandler.getInstance()
        handler.removeCallbacks(retryFill)
        val ready = queue.fill(Settings.concurrentGalleries, { gid ->
            val info = checkNotNull(mAllInfoMap[gid])
            SpiderQueen.obtainDownloadQueenIfAvailable(info)?.let { GalleryTask(info, it) }
        }, GalleryTask::start)
        if (!ready) handler.postDelayed(retryFill, 250)
    }

    private fun notifyInfoUpdated(info: DownloadInfo) {
        val list = getInfoListForLabel(info.label) ?: return
        mDownloadInfoListeners.forEach { it?.onUpdate(info, list) }
    }

    fun startDownload(galleryInfo: GalleryInfo, label: String?) {
        GalleryOperations.available(galleryInfo.gid) { startAvailableDownload(galleryInfo, label) }
    }

    private fun startAvailableDownload(galleryInfo: GalleryInfo, label: String?) {
        EhDB.putGalleryMetadata(galleryInfo)
        if (galleryInfo.gid in queue.running) {
            // It is current task
            return
        }

        // Check in download list
        var info = mAllInfoMap[galleryInfo.gid]
        if (info != null) { // Get it in download list
            if (info.state != DownloadInfo.STATE_WAIT) {
                // Set state DownloadInfo.STATE_WAIT
                info.state = DownloadInfo.STATE_WAIT
                // Add to wait list
                queue.enqueue(info.gid)
                // Update in DB
                EhDB.putDownloadInfo(info)
                // Notify state update
                val list: List<DownloadInfo>? = getInfoListForLabel(info.label)
                if (list != null) {
                    for (l in mDownloadInfoListeners) {
                        l!!.onUpdate(info, list)
                    }
                }
                // Make sure download is running
                ensureDownload()
            }
        } else {
            // It is new download info
            info = DownloadInfo(galleryInfo)
            info.label = label
            info.state = DownloadInfo.STATE_WAIT
            info.time = System.currentTimeMillis()

            // Add to label download list
            val list = getInfoListForLabel(info.label)
            if (list == null) {
                Log.e(TAG, "Can't find download info list with label: $label")
                return
            }
            list.addFirst(info)

            // Add to all download list and map
            mAllInfoList.addFirst(info)
            mAllInfoMap.put(galleryInfo.gid, info)

            // Add to wait list
            queue.enqueue(info.gid)

            // Save to
            EhDB.putDownloadInfo(info)

            // Notify
            for (l in mDownloadInfoListeners) {
                l!!.onAdd(info, list, list.size - 1)
            }
            // Make sure download is running
            ensureDownload()

            // Add it to history
            EhDB.putHistoryInfo(info)
        }
    }

    fun startRangeDownload(gidList: LongList) {
        var update = false
        for (i in 0 until gidList.size) {
            val gid = gidList[i]
            if (GalleryOperations.isBusy(gid)) continue
            val info = mAllInfoMap[gid]
            if (null == info) {
                Log.d(TAG, "Can't get download info with gid: $gid")
                continue
            }
            if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED || info.state == DownloadInfo.STATE_FINISH) {
                update = true
                // Set state DownloadInfo.STATE_WAIT
                info.state = DownloadInfo.STATE_WAIT
                // Add to wait list
                queue.enqueue(info.gid)
                // Update in DB
                EhDB.putDownloadInfo(info)
            }
        }
        if (update) {
            // Notify Listener
            for (l in mDownloadInfoListeners) {
                l!!.onUpdateAll()
            }
            // Ensure download
            ensureDownload()
        }
    }

    fun startAllDownload() {
        var update = false
        // Start all STATE_NONE and STATE_FAILED item
        for (info in mAllInfoList) {
            if (GalleryOperations.isBusy(info.gid)) continue
            if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                update = true
                // Set state DownloadInfo.STATE_WAIT
                info.state = DownloadInfo.STATE_WAIT
                // Add to wait list
                queue.enqueue(info.gid)
                // Update in DB
                EhDB.putDownloadInfo(info)
            }
        }
        if (update) {
            // Notify Listener
            for (l in mDownloadInfoListeners) {
                l!!.onUpdateAll()
            }
            // Ensure download
            ensureDownload()
        }
    }

    fun addDownload(downloadInfoList: List<DownloadInfo>, notify: Boolean = true) {
        for (info in downloadInfoList) {
            if (GalleryOperations.isBusy(info.gid)) continue
            if (containDownloadInfo(info.gid)) {
                // Contain
                return
            }

            // Ensure download state
            if (DownloadInfo.STATE_WAIT == info.state ||
                DownloadInfo.STATE_DOWNLOAD == info.state
            ) {
                info.state = DownloadInfo.STATE_NONE
            }

            // Add to label download list
            var list = getInfoListForLabel(info.label)
            if (null == list) {
                // Can't find the label in label list
                list = LinkedList()
                mMap[info.label] = list
                if (!containLabel(info.label)) {
                    // Add label to DB and list
                    mLabelList.add(EhDB.addDownloadLabel(info.label!!))
                }
            }
            list.add(info)
            // Sort
            list.sortByDateDescending()

            // Add to all download list and map
            mAllInfoList.add(info)
            mAllInfoMap.put(info.gid, info)

            // Save to
            EhDB.putDownloadInfo(info)
        }

        // Sort all download list
        mAllInfoList.sortByDateDescending()

        // Notify
        if (notify) {
            for (l in mDownloadInfoListeners) {
                l!!.onReload()
            }
        }
    }

    fun addDownloadLabel(downloadLabelList: List<DownloadLabel>) {
        for (label in downloadLabelList) {
            val labelString = label.label
            if (!containLabel(labelString)) {
                mMap[labelString] = LinkedList()
                mLabelList.add(EhDB.addDownloadLabel(label))
            }
        }
    }

    fun addDownload(galleryInfo: GalleryInfo, label: String?) {
        GalleryOperations.available(galleryInfo.gid) { addAvailableDownload(galleryInfo, label) }
    }

    private fun addAvailableDownload(galleryInfo: GalleryInfo, label: String?) {
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return
        }

        // It is new download info
        val info = DownloadInfo(galleryInfo)
        info.label = label
        info.state = DownloadInfo.STATE_NONE
        info.time = System.currentTimeMillis()

        // Add to label download list
        val list = getInfoListForLabel(info.label)
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: $label")
            return
        }
        list.addFirst(info)

        // Add to all download list and map
        mAllInfoList.addFirst(info)
        mAllInfoMap.put(galleryInfo.gid, info)

        // Save to
        EhDB.putDownloadInfo(info)

        // Notify
        for (l in mDownloadInfoListeners) {
            l!!.onAdd(info, list, list.size - 1)
        }
    }

    fun stopDownload(gid: Long) {
        val info = stopDownloadInternal(gid)
        if (info != null) {
            // Update listener
            val list: List<DownloadInfo>? = getInfoListForLabel(info.label)
            if (list != null) {
                for (l in mDownloadInfoListeners) {
                    l!!.onUpdate(info, list)
                }
            }
            // Ensure download
            ensureDownload()
        }
    }

    fun stopCurrentDownload() {
        firstRunning?.let { stopDownload(it.gid) }
    }

    fun stopRangeDownload(gidList: LongList) {
        stopRangeDownloadInternal(gidList)

        // Update listener
        for (l in mDownloadInfoListeners) {
            l!!.onUpdateAll()
        }

        // Ensure download
        ensureDownload()
    }

    fun stopAllDownload() {
        queue.waitingIds.toList().forEach(::stopDownloadInternal)
        queue.running.keys.toList().forEach(::stopDownloadInternal)
        SimpleHandler.getInstance().removeCallbacks(retryFill)

        // Notify mDownloadInfoListener
        for (l in mDownloadInfoListeners) {
            l!!.onUpdateAll()
        }
    }

    private val deletionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    fun deleteDownload(gid: Long, deleteFiles: Boolean = false, report: ((String) -> Unit)? = null) = deleteDownloads(listOf(gid), deleteFiles, report)

    fun deleteRangeDownload(gidList: LongList, deleteFiles: Boolean = false, report: ((String) -> Unit)? = null) = deleteDownloads((0 until gidList.size).map { gidList[it] }, deleteFiles, report)

    /** 所有入口共用逐 GID 删除；等待下载退出，在 IO 线程清理，事务成功后才更新展示索引。 */
    private fun deleteDownloads(gids: List<Long>, deleteFiles: Boolean, report: ((String) -> Unit)?) = deletionScope.launch {
        val results = mutableListOf<GalleryDeletionResult>()
        for (gid in gids.distinct()) {
            val reservation = GalleryOperations.reserve(gid)
            if (reservation == null) {
                results.add(GalleryDeletionResult(gid, DeletionStatus.SKIPPED))
                continue
            }
            try {
                val info = mAllInfoMap[gid]
                if (info == null) {
                    results.add(GalleryDeletionResult(gid, DeletionStatus.SKIPPED))
                    continue
                }
                stopDownloadInternal(gid)
                SpiderQueen.awaitStopped(gid)
                if (SpiderQueen.isInUse(gid)) {
                    results.add(GalleryDeletionResult(gid, DeletionStatus.SKIPPED))
                    continue
                }
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val location = EhDB.beginDownloadDeletion(gid)
                    deleteGallery(gid, location, deleteFiles, DownloadStorage::captureDeletion) {
                        EhDB.removeDownloadInfo(gid, location)
                    }
                }
                results.add(result)
                result.error?.let { Log.e(TAG, "Deletion failed for GID $gid; filesDeleted=${result.filesDeleted}; partial=${result.filesMayBePartial}", it) }
                if (result.status == DeletionStatus.DELETED) {
                    mAllInfoList.remove(info)
                    mAllInfoMap.remove(gid)
                    getInfoListForLabel(info.label)?.remove(info)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "Cannot stop/delete GID $gid", error)
                results.add(GalleryDeletionResult(gid, DeletionStatus.FAILED, error = error))
            } finally {
                EhDB.endDownloadDeletion(gid)
                reservation.close()
                mDownloadInfoListeners.forEach { it?.onReload() }
                ensureDownload()
            }
        }
        val context = com.hippo.ehviewer.EhApplication.application
        val message = context.getString(
            com.hippo.ehviewer.R.string.download_delete_report,
            results.count { it.status == DeletionStatus.DELETED },
            results.count { it.status == DeletionStatus.SKIPPED },
            results.count { it.status == DeletionStatus.FAILED },
        ) + results.filter { it.status != DeletionStatus.DELETED }.joinToString("") { result ->
            val reason = when {
                result.status == DeletionStatus.SKIPPED -> com.hippo.ehviewer.R.string.download_delete_skipped
                result.filesDeleted -> com.hippo.ehviewer.R.string.download_delete_database_failed
                result.filesMayBePartial -> com.hippo.ehviewer.R.string.download_delete_partial
                else -> com.hippo.ehviewer.R.string.download_delete_failed
            }
            "\n${result.gid}: ${context.getString(reason)}"
        }
        if (report != null) report(message) else android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }

    fun moveDownload(fromPosition: Int, toPosition: Int) {
        if (fromPosition > toPosition) {
            val time = mAllInfoList[toPosition].time
            for (i in toPosition until fromPosition) {
                val aTime = mAllInfoList[i].time
                val bTime = mAllInfoList[i + 1].time
                mAllInfoList[i].time = if (aTime == bTime) bTime + 1 else bTime
            }
            mAllInfoList[fromPosition].time = time
            EhDB.updateDownloadInfo(mAllInfoList.slice(toPosition..fromPosition))
        } else {
            val time = mAllInfoList[fromPosition].time
            for (i in fromPosition until toPosition) {
                val aTime = mAllInfoList[i].time
                val bTime = mAllInfoList[i + 1].time
                mAllInfoList[i].time = if (aTime == bTime) bTime - 1 else bTime
            }
            mAllInfoList[toPosition].time = time
            EhDB.updateDownloadInfo(mAllInfoList.slice(fromPosition..toPosition))
        }
        val label = mAllInfoList[fromPosition].label
        mAllInfoList.sortByDateDescending()
        val list = getInfoListForLabel(label)!!
        list.sortByDateDescending()
    }

    fun moveDownload(label: String?, fromPosition: Int, toPosition: Int) {
        val list = getInfoListForLabel(label)
        list?.let {
            val absFromPosition = mAllInfoList.indexOf(it[fromPosition])
            val absToPosition = mAllInfoList.indexOf(it[toPosition])
            moveDownload(absFromPosition, absToPosition)
        }
    }

    suspend fun resetAllReadingProgress() = coroutineScope {
        mAllInfoMap.keyIterator().forEach { gid ->
            launch {
                runCatching {
                    resetReadingProgress(gid)
                }.onFailure {
                    Log.e(TAG, "Can't write SpiderInfo", it)
                }
            }
        }
    }

    private fun resetReadingProgress(gid: Long) {
        EhDB.putReadingState(gid, page = 0)
    }

    // Update in DB
    // Update listener
    // No ensureDownload
    private fun stopDownloadInternal(gid: Long): DownloadInfo? {
        val task = queue.release(gid)
        if (task == null && !queue.removeWaiting(gid)) return null
        val info = checkNotNull(mAllInfoMap[gid])
        task?.close()
        info.state = DownloadInfo.STATE_NONE
        EhDB.putDownloadInfo(info)
        if (task != null || queue.isIdle) mDownloadListener?.onCancel(info)
        return info
    }

    private fun stopRangeDownloadInternal(gidList: LongList) {
        for (i in 0 until gidList.size) stopDownloadInternal(gidList[i])
    }

    /**
     * @param label Not allow new label
     */
    fun changeLabel(list: List<DownloadInfo>, label: String?) {
        if (list.any { GalleryOperations.isBusy(it.gid) }) return
        if (null != label && !containLabel(label)) {
            Log.e(TAG, "Not exits label: $label")
            return
        }
        val dstList: MutableList<DownloadInfo>? = getInfoListForLabel(label)
        if (dstList == null) {
            Log.e(TAG, "Can't find label with label: $label")
            return
        }
        for (info in list) {
            if (ObjectUtils.equal(info.label, label)) {
                continue
            }
            val srcList: MutableList<DownloadInfo>? = getInfoListForLabel(info.label)
            if (srcList == null) {
                Log.e(TAG, "Can't find label with label: " + info.label)
                continue
            }
            srcList.remove(info)
            dstList.add(info)
            info.label = label
            dstList.sortByDateDescending()

            // Save to DB
            EhDB.putDownloadInfo(info)
        }
        for (l in mDownloadInfoListeners) {
            l!!.onReload()
        }
    }

    fun addLabel(label: String?) {
        if (label == null || containLabel(label)) {
            return
        }
        mLabelList.add(EhDB.addDownloadLabel(label))
        mMap[label] = LinkedList()
        for (l in mDownloadInfoListeners) {
            l!!.onUpdateLabels()
        }
    }

    fun moveLabel(fromPosition: Int, toPosition: Int) {
        val item = mLabelList.removeAt(fromPosition)
        mLabelList.add(toPosition, item)
        EhDB.moveDownloadLabel(fromPosition, toPosition)
        for (l in mDownloadInfoListeners) {
            l!!.onUpdateLabels()
        }
    }

    fun renameLabel(from: String, to: String) {
        if (mMap[from]?.any { GalleryOperations.isBusy(it.gid) } == true) return
        // Find in label list
        var found = false
        for (raw in mLabelList) {
            if (from == raw.label) {
                found = true
                raw.label = to
                // Update in DB
                EhDB.updateDownloadLabel(raw)
                break
            }
        }
        if (!found) {
            return
        }
        val list = mMap.remove(from) ?: return

        // Update info label
        for (info in list) {
            info.label = to
            // Update in DB
            EhDB.putDownloadInfo(info)
        }
        // Put list back with new label
        mMap[to] = list

        // Notify listener
        for (l in mDownloadInfoListeners) {
            l!!.onRenameLabel(from, to)
        }
    }

    fun deleteLabel(label: String) {
        if (mMap[label]?.any { GalleryOperations.isBusy(it.gid) } == true) return
        // Find in label list and remove
        var found = false
        val iterator = mLabelList.iterator()
        while (iterator.hasNext()) {
            val raw = iterator.next()
            if (label == raw.label) {
                found = true
                iterator.remove()
                EhDB.removeDownloadLabel(raw)
                break
            }
        }
        if (!found) {
            return
        }
        val list = mMap.remove(label) ?: return

        // Update info label
        for (info in list) {
            info.label = null
            // Update in DB
            EhDB.putDownloadInfo(info)
            mDefaultInfoList.add(info)
        }

        // Sort
        mDefaultInfoList.sortByDateDescending()

        // Notify listener
        for (l in mDownloadInfoListeners) {
            l!!.onChange()
        }
    }

    val isIdle: Boolean
        get() = queue.isIdle

    interface DownloadInfoListener {
        /**
         * Add the special info to the special position
         */
        fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int)

        /**
         * The special info is changed
         */
        fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>)

        /**
         * Maybe all data is changed, but size is the same
         */
        fun onUpdateAll()

        /**
         * Maybe all data is changed, maybe list is changed
         */
        fun onReload()

        /**
         * The list is gone, use default list please
         */
        fun onChange()

        /**
         * Rename label
         */
        fun onRenameLabel(from: String, to: String)

        /**
         * Remove the special info from the special position
         */
        fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int)
        fun onUpdateLabels()
    }

    interface DownloadListener {
        /**
         * Get 509 error
         */
        fun onGet509()

        /**
         * Start download
         */
        fun onStart(info: DownloadInfo)

        /**
         * Update download speed
         */
        fun onDownload(info: DownloadInfo)

        /**
         * Update page downloaded
         */
        fun onGetPage(info: DownloadInfo)

        /**
         * Download done
         */
        fun onFinish(info: DownloadInfo)

        /**
         * Download done
         */
        fun onCancel(info: DownloadInfo)
    }

    /** 一次启动一个监听器。迟到的事件按任务实例过滤，重启同一 GID 也不会串写。 */
    private class GalleryTask(val info: DownloadInfo, private val spider: SpiderQueen) : OnSpiderListener {
        private val speed = SpeedReminder(info)

        fun start() {
            info.state = DownloadInfo.STATE_DOWNLOAD
            info.speed = -1
            info.remaining = -1
            info.total = -1
            info.finished = 0
            info.downloaded = 0
            info.legacy = -1
            EhDB.putDownloadInfo(info)
            spider.addOnSpiderListener(this, forDownload = true)
            speed.start()
            mDownloadListener?.onStart(info)
            notifyInfoUpdated(info)
        }

        fun close() {
            spider.removeOnSpiderListener(this)
            speed.stop()
            SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD)
        }

        private fun dispatch(action: () -> Unit) {
            SimpleHandler.getInstance().post {
                if (queue.isCurrent(info.gid, this)) action()
            }
        }

        override fun onGetPages(pages: Int) = dispatch {
            info.total = pages
            notifyInfoUpdated(info)
        }

        override fun onGet509(index: Int) = dispatch { mDownloadListener?.onGet509() }

        override fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
            speed.onDownload(index, contentLength, receivedSize, bytesRead)
        }

        override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) = dispatch {
            updatePage(index, finished, downloaded, total)
            mDownloadListener?.onGetPage(info)
        }

        override fun onPageFailure(index: Int, error: String?, finished: Int, downloaded: Int, total: Int) = dispatch {
            updatePage(index, finished, downloaded, total)
        }

        private fun updatePage(index: Int, finished: Int, downloaded: Int, total: Int) {
            speed.onDone(index)
            info.finished = finished
            info.downloaded = downloaded
            info.total = total
            EhDB.putDownloadInfo(info)
            notifyInfoUpdated(info)
        }

        override fun onFinish(finished: Int, downloaded: Int, total: Int) = dispatch {
            queue.release(info.gid)
            close()
            info.finished = finished
            info.downloaded = downloaded
            info.total = total
            info.legacy = total - finished
            info.state = if (info.legacy == 0) DownloadInfo.STATE_FINISH else DownloadInfo.STATE_FAILED
            EhDB.putDownloadInfo(info)
            mDownloadListener?.onFinish(info)
            notifyInfoUpdated(info)
            ensureDownload()
        }

        override fun onGetImageSuccess(index: Int, image: Image?) = Unit
        override fun onGetImageFailure(index: Int, error: String?) = Unit
    }

    private class SpeedReminder(private val info: DownloadInfo) : Runnable {
        private val mContentLengthMap = SparseLongArray()
        private val mReceivedSizeMap = SparseLongArray()
        private var mStop = true
        private var mBytesRead: Long = 0
        private var oldSpeed: Long = -1
        fun start() {
            if (mStop) {
                mStop = false
                SimpleHandler.getInstance().post(this)
            }
        }

        @Synchronized
        fun stop() {
            if (!mStop) {
                mStop = true
                mBytesRead = 0
                oldSpeed = -1
                mContentLengthMap.clear()
                mReceivedSizeMap.clear()
                SimpleHandler.getInstance().removeCallbacks(this)
            }
        }

        @Synchronized
        fun onDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
            mContentLengthMap.put(index, contentLength)
            mReceivedSizeMap.put(index, receivedSize)
            mBytesRead += bytesRead.toLong()
        }

        @Synchronized
        fun onDone(index: Int) {
            mContentLengthMap.delete(index)
            mReceivedSizeMap.delete(index)
        }

        @Synchronized
        private fun updateSpeed() {
            var newSpeed = mBytesRead / 2
            if (oldSpeed != -1L) {
                newSpeed =
                    MathUtils.lerp(oldSpeed.toFloat(), newSpeed.toFloat(), 0.75f).toLong()
            }
            oldSpeed = newSpeed
            info.speed = newSpeed

            // Calculate remaining
            if (info.total <= 0) {
                info.remaining = -1
            } else if (newSpeed == 0L) {
                info.remaining = 300L * 24L * 60L * 60L * 1000L // 300 days
            } else {
                var downloadingCount = 0
                var downloadingContentLengthSum: Long = 0
                var totalSize: Long = 0
                for (i in 0 until maxOf(mContentLengthMap.size, mReceivedSizeMap.size)) {
                    val contentLength = mContentLengthMap.valueAt(i)
                    val receivedSize = mReceivedSizeMap.valueAt(i)
                    downloadingCount++
                    downloadingContentLengthSum += contentLength
                    totalSize += contentLength - receivedSize
                }
                if (downloadingCount != 0) {
                    totalSize += downloadingContentLengthSum * (info.total - info.downloaded - downloadingCount) / downloadingCount
                    info.remaining = totalSize / newSpeed * 1000
                }
            }
            mBytesRead = 0
        }

        override fun run() {
            if (mStop) return
            updateSpeed()
            mDownloadListener?.onDownload(info)
            notifyInfoUpdated(info)
            if (!mStop) {
                SimpleHandler.getInstance().postDelayed(this, 2000)
            }
        }
    }

    private val TAG = DownloadManager::class.java.simpleName

    private fun MutableList<DownloadInfo>.sortByDateDescending() {
        sortByDescending { it.time }
    }
}
