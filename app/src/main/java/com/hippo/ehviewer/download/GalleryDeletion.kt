// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadDirname
import java.io.IOException

internal enum class DeletionStatus { DELETED, SKIPPED, FAILED }

internal data class GalleryDeletionResult(
    val gid: Long,
    val status: DeletionStatus,
    val filesDeleted: Boolean = false,
    val filesMayBePartial: Boolean = false,
    val error: Exception? = null,
)

/** 先固定两个精确目录及递归清单，再逐文件删除；缺失视为已清理，列举失败不能视为缺失。 */
internal class GalleryDeletionPlan private constructor(private val entries: List<Pair<GalleryFile, String?>>) {
    fun delete() {
        for ((file, identity) in entries) {
            check(!file.isSymbolicLink && file.identity == identity) { "Gallery path changed during deletion" }
            if (file.isDirectory) check(file.listFiles()?.isEmpty() == true) { "Gallery directory changed during deletion" }
            check(file.delete()) { "Cannot delete gallery file" }
        }
    }

    companion object {
        fun capture(location: DownloadDirname, openRoot: (String) -> GalleryFile?): GalleryDeletionPlan {
            require(location.gid > 0)
            val path = DownloadLayout.segments(location.dirname ?: throw IOException("Missing gallery path"))
            check(path.size == 2 && path[0] in DownloadLayout.categories + "unknown" && path[1] == location.gid.toString()) { "Invalid gallery path" }
            val root = openRoot(location.rootUri ?: throw IOException("Missing authorized root")) ?: throw IOException("Download root unavailable")
            check(!root.isSymbolicLink && root.isDirectory && root.canWrite()) { "Download root permission lost" }
            fun child(parent: GalleryFile, name: String): GalleryFile? {
                check(!parent.isSymbolicLink && parent.isDirectory) { "Invalid parent directory" }
                val children = parent.listFiles() ?: throw IOException("Cannot enumerate gallery path")
                val matches = children.filter { it.name == name }
                check(matches.size <= 1) { "Ambiguous gallery path" }
                return matches.singleOrNull()?.also { check(!it.isSymbolicLink && it.isDirectory) { "Invalid gallery directory" } }
            }
            val gallery = child(root, path[0])?.let { child(it, path[1]) }
            val cache = child(root, GalleryReadCache.DIRECTORY)?.let { child(it, path[1]) }
            val entries = mutableListOf<GalleryFile>()
            fun collect(file: GalleryFile) {
                check(!file.isSymbolicLink) { "Refusing symbolic link in gallery" }
                if (file.isDirectory) {
                    val children = file.listFiles() ?: throw IOException("Cannot enumerate gallery contents")
                    check(children.map { it.name }.distinct().size == children.size) { "Ambiguous gallery contents" }
                    children.forEach { child ->
                        check(DownloadLayout.segments(child.name ?: throw IOException("Missing file name")).size == 1)
                        collect(child)
                    }
                }
                entries.add(file)
            }
            // 两份清单均成功后才允许修改任何文件，避免缓存失败时先删掉成品。
            cache?.let(::collect)
            gallery?.let(::collect)
            return GalleryDeletionPlan(entries.map { it to it.identity })
        }
    }
}

/** 文件与数据库不能共同回滚；把部分清理和文件已删但事务失败分别返回，供逐项报告和幂等重试。 */
internal fun deleteGallery(
    gid: Long,
    location: DownloadDirname?,
    deleteFiles: Boolean,
    capture: (DownloadDirname) -> GalleryDeletionPlan,
    commit: () -> Boolean,
): GalleryDeletionResult {
    var filesDeleted = false
    var attempted = false
    return try {
        if (deleteFiles && location != null) {
            val plan = capture(location.copy())
            attempted = true
            plan.delete()
            filesDeleted = true
        }
        GalleryDeletionResult(gid, if (commit()) DeletionStatus.DELETED else DeletionStatus.SKIPPED, filesDeleted)
    } catch (error: Exception) {
        if (error is java.util.concurrent.CancellationException) throw error
        GalleryDeletionResult(gid, DeletionStatus.FAILED, filesDeleted, attempted && !filesDeleted, error)
    }
}
