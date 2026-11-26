/* Copyright 2026 haorengg12. Licensed under GPL-3.0-or-later. */
package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadDirname
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/** 普通文件与 SAF 共用同一发布流程，文件系统及主库提交可以独立失败。 */
internal interface GalleryFile {
    val name: String?
    val isDirectory: Boolean
    val isSymbolicLink: Boolean get() = false
    val identity: String? get() = null
    fun findFile(name: String): GalleryFile?
    fun createFile(name: String): GalleryFile?
    fun createDirectory(name: String): GalleryFile?
    fun listFiles(): Array<GalleryFile>?
    fun canWrite(): Boolean
    fun delete(): Boolean
    fun renameTo(name: String): Boolean
    fun openInputStream(): InputStream
    fun openOutputStream(): OutputStream
}

/** 仅对调用方已核实边界的目录递归删除；列举或删除失败时保留失败状态。 */
internal fun GalleryFile.deleteTree(): Boolean {
    if (isDirectory) {
        val children = listFiles() ?: return false
        if (!children.all { it.deleteTree() }) return false
    }
    return delete()
}

/** 每个 GID 一个任务和一个实际位置。根 URI 固定在记录中，换目录不重解释旧相对路径。 */
internal class GalleryFiles(
    private val load: (Long) -> DownloadDirname?,
    private val save: (DownloadDirname) -> Unit,
    private val commit: (DownloadDirname) -> Unit,
    private val openRoot: (String) -> GalleryFile?,
    private val readable: (GalleryFile) -> Boolean,
    private val randomSuffix: () -> String = { ByteArray(3).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } },
) {
    private class WriteLock(var users: Int = 0)
    private val writeLocks = mutableMapOf<Long, WriteLock>()

    /** 同一 GID 的准备、发布和清理互斥；其他画廊无需等待整部 CBZ 写完。 */
    private inline fun <T> withGalleryLock(gid: Long, action: () -> T): T {
        val lock = synchronized(writeLocks) { writeLocks.getOrPut(gid) { WriteLock() }.also { it.users++ } }
        try {
            return synchronized(lock) { action() }
        } finally {
            synchronized(writeLocks) {
                if (--lock.users == 0) writeLocks.remove(gid)
            }
        }
    }

    fun root(location: DownloadDirname): GalleryFile? {
        val directory = location.dirname ?: return null
        check(directory.substringBefore('/') in DownloadLayout.categories + "unknown" && directory.substringAfter('/') == location.gid.toString()) { "Invalid gallery directory" }
        check(location.format in setOf("cbz", "images"))
        location.tempPath?.let { check(it.matches(Regex("${Regex.escape(directory)}/${location.gid}_[0-9a-f]{6}_tmp"))) { "Invalid task directory" } }
        location.actualPath?.let { check(it == if (location.format == "cbz") "$directory/${location.gid}.cbz" else directory) { "Invalid content path" } }
        return location.rootUri?.let(openRoot)
    }

    /** SAF 逐级定位/创建，禁止将带斜杠的相对路径当单个 document name。 */
    fun resolve(root: GalleryFile, path: String, createDirectories: Boolean = false): GalleryFile? {
        var current = root
        for (part in DownloadLayout.segments(path)) {
            current = current.findFile(part) ?: if (createDirectories) current.createDirectory(part) ?: return null else return null
            if (current.name != part) return null
        }
        return current
    }

    fun container(gid: Long): GalleryFile? = load(gid)?.let { location ->
        val path = location.dirname ?: return null
        root(location)?.let { resolve(it, path) }
    }

    fun imageDirectory(gid: Long): GalleryFile? = load(gid)?.let { location ->
        val path = if (location.phase == "complete") location.dirname else location.tempPath
        path?.let { root(location)?.let { root -> resolve(root, it) } }
    }

    fun archive(gid: Long): GalleryFile? = load(gid)?.takeIf { it.phase == "complete" && it.format == "cbz" }?.let { location ->
        location.actualPath?.let { root(location)?.let { root -> resolve(root, it) } }
    }

    fun prepare(gid: Long, category: Int, rootUri: String?, cbz: Boolean): GalleryFile = withGalleryLock(gid) {
        val location = load(gid) ?: DownloadDirname(gid).also {
            it.dirname = DownloadLayout.directory(gid, category)
            it.rootUri = rootUri ?: throw IOException("Missing download root")
            it.format = if (cbz) "cbz" else "images"
            save(it)
        }
        if (location.phase == "complete") return@withGalleryLock imageDirectory(gid) ?: throw IOException("Download location unavailable")
        val root = root(location) ?: throw IOException("Download root unavailable")
        check(root.canWrite()) { "Download permission lost" }
        val directory = location.dirname ?: throw IOException("Missing target directory")
        // 不同 GID 可能首次同时创建相同的分类目录。
        val parent = synchronized(this) { resolve(root, directory, true) } ?: throw IOException("Cannot create gallery directory")
        location.tempPath?.let {
            return@withGalleryLock resolve(root, it, true) ?: throw IOException("Cannot resume temporary directory")
        }
        // 未登记的成品一律不覆盖；发布中断的写入只能由原任务继续。
        check(parent.findFile("$gid.cbz") == null && parent.findFile(".thumb") == null && parent.listFiles().orEmpty().none { DownloadLayout.pagePattern.matches(it.name.orEmpty()) }) { "Target already contains unregistered content" }
        repeat(100) {
            val suffix = randomSuffix()
            val name = "${gid}_${suffix}_tmp"
            if (parent.findFile(name) == null) {
                // 先持久化唯一名称；创建失败/退出仍沿用此任务，不争写另一套目录。
                location.tempPath = "$directory/$name"
                location.phase = "downloading"
                save(location)
                return@withGalleryLock parent.createDirectory(name)?.takeIf { it.name == name } ?: throw IOException("Cannot create temporary directory")
            }
        }
        throw IOException("Temporary directory collision")
    }

    /** 成品写入前保存清单；文件完成但主库未提交时，按清单核实接续，不覆盖其他内容。 */
    fun finish(gid: Long, pages: Int, onStage: (String) -> Unit = {}, checkActive: () -> Unit = {}): Unit = withGalleryLock(gid) {
        checkActive()
        val location = load(gid) ?: throw IOException("Missing download task")
        check(pages > 0)
        if (location.phase == "complete") {
            check(verify(location)) { "Completed content is missing or damaged" }
            cleanupTemporary(location)
            return@withGalleryLock
        }
        val root = root(location) ?: throw IOException("Download permission lost")
        val temporary = location.tempPath?.let { resolve(root, it) } ?: throw IOException("Missing temporary content")
        onStage("validate pages")
        // SAF 文件名查询可能跨进程。只列举一次，后续写入复用已识别的文件。
        val entries = temporary.listFiles() ?: throw IOException("Cannot enumerate temporary pages")
        val ordered = entries.mapNotNull { file ->
            checkActive()
            val name = file.name ?: throw IOException("Cannot read temporary file name")
            if (DownloadLayout.pagePattern.matches(name) && name.substringBefore('.').toInt() in 1..pages) name to file else null
        }.sortedBy { it.first }
        check(ordered.size == pages) { "Expected $pages pages, found ${ordered.size}" }
        val names = ordered.map { it.first }
        GalleryArchive.validateNames(names)
        val files = ordered.toMap()
        val manifest = files.mapValues { (name, file) ->
            checkActive()
            check(readable(file)) { "Unreadable page $name" }
            file.openInputStream().use(GalleryArchive::digest)
        }
        if (location.phase != "publishing") {
            val parent = resolve(root, location.dirname!!) ?: throw IOException("Missing target directory")
            check(parent.findFile("$gid.cbz") == null && parent.listFiles().orEmpty().none { DownloadLayout.pagePattern.matches(it.name.orEmpty()) }) { "Target content appeared during download" }
        }
        location.manifest?.let { check(parseManifest(it) == manifest) { "Task content changed during publication" } }
        location.manifest = manifest.entries.joinToString("\n") { "${it.key}:${it.value}" }
        location.expectedPages = pages
        location.phase = "publishing"
        save(location)
        val parent = resolve(root, location.dirname!!) ?: throw IOException("Missing target directory")
        if (location.format == "cbz") {
            val name = "$gid.cbz"
            val existing = parent.findFile(name)
            val target = existing ?: parent.createFile(name) ?: throw IOException("Cannot create CBZ")
            check(target.name == name)
            val valid = existing != null && run {
                onStage("verify existing CBZ")
                runCatching { existing.openInputStream().use(GalleryArchive::manifest) == manifest }.getOrDefault(false)
            }
            if (!valid) {
                onStage("write CBZ")
                GalleryArchive.write(names, target.openOutputStream()) { page ->
                    checkActive()
                    files.getValue(page).openInputStream()
                }
                onStage("verify CBZ")
                check(target.openInputStream().use(GalleryArchive::manifest) == manifest) { "CBZ verification failed" }
            }
            location.actualPath = "${location.dirname}/$name"
        } else {
            files.forEach { (name, source) ->
                checkActive()
                val target = parent.findFile(name) ?: parent.createFile(name) ?: throw IOException("Cannot create image")
                check(target.name == name)
                if (runCatching { target.openInputStream().use(GalleryArchive::digest) }.getOrNull() != manifest[name]) {
                    source.openInputStream().use { input -> target.openOutputStream().use { input.copyTo(it) } }
                }
                check(target.openInputStream().use(GalleryArchive::digest) == manifest[name]) { "Image verification failed" }
            }
            location.actualPath = location.dirname
        }
        // 本地封面不依赖在线缩略图或图片缓存；保留在 GID 目录。
        val thumb = parent.findFile(".thumb") ?: parent.createFile(".thumb") ?: throw IOException("Cannot save cover")
        files.getValue(names.first()).openInputStream().use { input -> thumb.openOutputStream().use { input.copyTo(it) } }
        location.phase = "complete"
        location.lastError = null
        checkActive()
        onStage("commit")
        commit(location)
        // 仅在位置提交后清理本次任务的已知文件，未知内容保持原样。
        onStage("clean temporary pages")
        files.values.forEach { it.delete() }
        if (temporary.listFiles()?.isEmpty() == true) temporary.delete()
    }

    fun verify(location: DownloadDirname): Boolean = runCatching {
        val manifest = parseManifest(location.manifest ?: return false)
        check(manifest.size == location.expectedPages)
        val root = root(location) ?: return false
        if (location.format == "cbz") {
            val file = location.actualPath?.let { resolve(root, it) } ?: return false
            file.openInputStream().use(GalleryArchive::manifest) == manifest
        } else {
            val dir = location.actualPath?.let { resolve(root, it) } ?: return false
            manifest.all { (name, digest) -> dir.findFile(name)?.openInputStream()?.use(GalleryArchive::digest) == digest }
        }
    }.getOrDefault(false)

    private fun parseManifest(raw: String): Map<String, String> = raw.lines().associate { line ->
        val name = line.substringBefore(':')
        val digest = line.substringAfter(':')
        check(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid manifest digest" }
        name to digest
    }.also { GalleryArchive.validateNames(it.keys.sorted()) }

    /** 只处理固定位置的文件，位置记录交由下载删除事务统一移除。 */
    fun deleteFiles(gid: Long): Boolean = withGalleryLock(gid) {
        val location = load(gid) ?: return@withGalleryLock false
        val plan = GalleryDeletionPlan.capture(location, openRoot)
        runCatching { plan.delete() }.isSuccess
    }

    private fun cleanupTemporary(location: DownloadDirname) {
        val temporary = location.tempPath?.let { path -> root(location)?.let { resolve(it, path) } } ?: return
        location.manifest?.let(::parseManifest)?.keys.orEmpty().forEach { temporary.findFile(it)?.delete() }
        if (temporary.listFiles()?.isEmpty() == true) temporary.delete()
    }
}
