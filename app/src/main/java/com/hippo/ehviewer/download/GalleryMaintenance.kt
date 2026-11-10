// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadDirname
import java.io.IOException
import java.util.concurrent.CancellationException

internal data class RecoveryIdentity(val gid: Long, val token: String?, val pages: Int, val format: String? = null, val fromDisk: Boolean = true)
internal data class RecoveryCandidate(val identity: RecoveryIdentity, val location: DownloadDirname, val finished: Int)
internal data class MaintenanceResult(var restored: Int = 0, var removed: Int = 0, var failed: Int = 0, var skipped: Int = 0, var stopped: Boolean = false)

/** 仅扫描新版分类/GID 布局；恢复不搬文件，清理只删除可确认归属且不再下载的整个 GID 目录。 */
internal class GalleryMaintenance(
    private val rootUri: String,
    private val root: GalleryFile,
    private val load: (Long) -> DownloadDirname?,
    private val identity: (GalleryFile, Long) -> RecoveryIdentity?,
    private val hasDownload: (Long) -> Boolean,
    private val inUse: (Long) -> Boolean,
    private val busy: () -> Boolean,
    private val readable: (GalleryFile) -> Boolean,
    private val defaultFormat: String,
    private val existingAvailable: (DownloadDirname) -> Boolean = { false },
    private val beforeDelete: (Entry) -> Boolean = { true },
    private val onError: (Exception) -> Unit = {},
) {
    internal data class Entry(val gid: Long, val path: String, val directory: GalleryFile)

    private fun scan(result: MaintenanceResult): List<Entry> {
        check(root.isDirectory) { "Download root unavailable" }
        val categories = root.listFiles() ?: throw IOException("Cannot list download root")
        return categories.filter { it.isDirectory && it.name in DownloadLayout.categories + "unknown" }.flatMap { category ->
            val children = category.listFiles()
            if (children == null) {
                result.failed++
                emptyList()
            } else {
                children.mapNotNull { child ->
                    val gid = child.name?.toLongOrNull()?.takeIf { it > 0 && it.toString() == child.name }
                    if (!child.isDirectory || gid == null) null else Entry(gid, "${category.name}/$gid", child)
                }
            }
        }
    }

    /** 重复 GID、损坏内容、身份冲突逐项报告；在线补元数据或写库失败不会删除本地内容。 */
    suspend fun restore(apply: suspend (RecoveryCandidate) -> Unit): MaintenanceResult {
        val result = MaintenanceResult()
        for ((_, entries) in scan(result).groupBy { it.gid }) {
            if (busy()) {
                result.stopped = true
                break
            }
            if (entries.size != 1) {
                result.failed += entries.size
                continue
            }
            val entry = entries.single()
            if (inUse(entry.gid)) {
                result.skipped++
                continue
            }
            try {
                val old = load(entry.gid)
                val info = identity(entry.directory, entry.gid)
                if (info == null) {
                    result.skipped++
                    continue
                }
                check(info.gid == entry.gid) { "Gallery identity mismatch" }
                val candidate = inspect(entry, info, old)
                if (old != null && (old.rootUri != rootUri || old.dirname != entry.path) && existingAvailable(old)) {
                    check(old.phase == "complete" && candidate.location.phase == "complete" && old.manifest == candidate.location.manifest) { "Another valid gallery location already exists" }
                }
                if (busy() || inUse(entry.gid)) {
                    result.stopped = true
                    break
                }
                apply(candidate)
                result.restored++
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError(error)
                result.failed++
            }
        }
        return result
    }

    /** 下载列表中的 GID、正在阅读的目录及未知文件均受保护；只有删除成功才更新位置记录。 */
    fun clean(deleted: (Entry) -> Unit): MaintenanceResult {
        val result = MaintenanceResult()
        for (entry in scan(result)) {
            if (busy()) {
                result.stopped = true
                break
            }
            if (hasDownload(entry.gid) || inUse(entry.gid)) {
                result.skipped++
                continue
            }
            try {
                val old = load(entry.gid)
                val info = identity(entry.directory, entry.gid)
                check(info == null || info.gid == entry.gid) { "Gallery identity mismatch" }
                val registered = old?.rootUri == rootUri && old.dirname == entry.path
                val owned = registered || info?.fromDisk == true || isRecognizableContent(entry)
                if (!owned) {
                    result.skipped++
                    continue
                }
                if (busy() || hasDownload(entry.gid) || inUse(entry.gid)) {
                    result.skipped++
                    continue
                }
                check(beforeDelete(entry)) { "Cannot clear gallery read cache" }
                check(entry.directory.deleteTree()) { "Cannot delete gallery directory" }
                result.removed++
                deleted(entry)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError(error)
                result.failed++
            }
        }
        return result
    }

    private fun isRecognizableContent(entry: Entry): Boolean {
        val children = entry.directory.listFiles() ?: throw IOException("Cannot list gallery directory")
        // 未登记且没有身份文件时，只有纯成品目录可被识别；未知附属文件不作为删除依据。
        if (children.any { it.isDirectory || !(DownloadLayout.pagePattern.matches(it.name.orEmpty()) || it.name == "${entry.gid}.cbz" || it.name == ".thumb") }) return false
        return runCatching { inspect(entry, RecoveryIdentity(entry.gid, null, 0), null).location.phase == "complete" }.getOrDefault(false)
    }

    private fun inspect(entry: Entry, info: RecoveryIdentity, old: DownloadDirname?): RecoveryCandidate {
        val children = entry.directory.listFiles() ?: throw IOException("Cannot list gallery directory")
        val expected = info.pages.takeIf { it > 0 } ?: old?.expectedPages?.takeIf { it > 0 }
        val temporary = children.filter { it.isDirectory && it.name?.matches(Regex("${entry.gid}_[0-9a-f]{6}_tmp")) == true }
        check(temporary.size <= 1) { "Ambiguous temporary directories" }
        val format = info.format ?: old?.format ?: defaultFormat
        check(format in setOf("cbz", "images"))
        val location = DownloadDirname(entry.gid, entry.path, rootUri = rootUri, format = format)
        fun candidate(manifest: Map<String, String>, complete: Boolean, temp: GalleryFile? = null): RecoveryCandidate {
            val pages = expected ?: manifest.size
            check(pages > 0 && (!complete || pages == manifest.size)) { "Page count mismatch" }
            location.expectedPages = pages
            location.phase = if (complete) "complete" else "downloading"
            location.tempPath = temp?.let { "${entry.path}/${it.name}" }
            location.manifest = if (complete) manifest.entries.joinToString("\n") { "${it.key}:${it.value}" } else null
            location.actualPath = if (!complete) {
                null
            } else if (location.format == "cbz") {
                "${entry.path}/${entry.gid}.cbz"
            } else {
                entry.path
            }
            if (!complete && old?.phase == "publishing" && old.tempPath == location.tempPath && old.manifest != null) {
                check(old.manifest == manifest.entries.joinToString("\n") { "${it.key}:${it.value}" }) { "Interrupted publication content changed" }
                location.phase = "publishing"
                location.manifest = old.manifest
            }
            return RecoveryCandidate(info.copy(pages = pages, format = location.format), location, manifest.size)
        }
        fun images(directory: GalleryFile, complete: Boolean): Map<String, String> {
            val files = directory.listFiles()?.filter { DownloadLayout.pagePattern.matches(it.name.orEmpty()) }?.sortedBy { it.name } ?: throw IOException("Cannot list images")
            check(files.map { it.name!!.substringBefore('.') }.distinct().size == files.size) { "Duplicate page indices" }
            if (complete) GalleryArchive.validateNames(files.map { it.name!! })
            return files.associate { file ->
                check(!file.isDirectory && readable(file)) { "Unreadable image" }
                check(file.name!!.substringBefore('.').toInt() > 0 && (expected == null || file.name!!.substringBefore('.').toInt() <= expected)) { "Image index out of range" }
                file.name!! to file.openInputStream().use(GalleryArchive::digest)
            }
        }
        if (old?.phase == "publishing" && temporary.isNotEmpty()) return candidate(images(temporary.single(), false), false, temporary.single())
        val archive = children.firstOrNull { it.name == "${entry.gid}.cbz" && !it.isDirectory }
        val numbered = children.any { DownloadLayout.pagePattern.matches(it.name.orEmpty()) }
        check(archive == null || !numbered) { "Ambiguous completed formats" }
        if (archive != null) {
            location.format = "cbz"
            return candidate(archive.openInputStream().use(GalleryArchive::manifest), true)
        }
        if (numbered) {
            val manifest = images(entry.directory, true)
            if (expected == null || expected == manifest.size) {
                location.format = "images"
                return candidate(manifest, true)
            }
            check(temporary.isNotEmpty()) { "Incomplete published images" }
        }
        if (temporary.isNotEmpty()) return candidate(images(temporary.single(), false), false, temporary.single())
        check(expected != null && !numbered) { "No recoverable gallery content" }
        check(info.fromDisk || (old?.rootUri == rootUri && old.dirname == entry.path)) { "No local download identity or content" }
        location.expectedPages = expected
        return RecoveryCandidate(info.copy(pages = expected, format = format), location, 0)
    }
}
