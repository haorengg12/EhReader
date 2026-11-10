/* Copyright 2026 haorengg12. Licensed under GPL-3.0-or-later. */
package com.hippo.ehviewer.download

import java.io.IOException
import java.io.OutputStream

/** CBZ 阅读图片仅放在其下载根的 temp_read/GID；每个进程首次访问根时先完成清空。 */
internal class GalleryReadCache(private val openRoot: (String) -> GalleryFile?) {
    private val cleanedRoots = mutableSetOf<String>()

    /** 启动清理与首次读取共用锁及标记，防止后台清理误删本次启动刚解压的图片。 */
    @Synchronized
    fun clearAtStartup(rootId: String) {
        cleanRootOnce(rootId)
    }

    private fun cleanRootOnce(rootId: String): GalleryFile {
        val root = openRoot(rootId) ?: throw IOException("Read cache root unavailable")
        check(root.canWrite() && root.isDirectory) { "Read cache root permission lost" }
        if (rootId !in cleanedRoots) {
            root.findFile(DIRECTORY)?.let { cache ->
                check(cache.isDirectory) { "Read cache path is not a directory" }
                val children = cache.listFiles() ?: throw IOException("Cannot enumerate read cache")
                check(children.all { it.deleteTree() }) { "Cannot clear read cache" }
            }
            cleanedRoots.add(rootId)
        }
        return root
    }

    @Synchronized
    fun directory(rootId: String, gid: Long): GalleryFile {
        require(gid > 0)
        val root = cleanRootOnce(rootId)
        val cache = root.findFile(DIRECTORY) ?: root.createDirectory(DIRECTORY) ?: throw IOException("Cannot create read cache")
        check(cache.isDirectory && cache.name == DIRECTORY)
        val name = gid.toString()
        return (cache.findFile(name) ?: cache.createDirectory(name) ?: throw IOException("Cannot create gallery read cache")).also {
            check(it.isDirectory && it.name == name)
        }
    }

    /** 按主库清单校验缓存及解压结果；失败清理 .part，不修改 CBZ 或画廊成品目录。 */
    @Synchronized
    fun page(rootId: String, gid: Long, name: String, digest: String, extract: (OutputStream) -> Unit): GalleryFile {
        require(DownloadLayout.pagePattern.matches(name) && digest.matches(Regex("[0-9a-f]{64}")))
        val directory = directory(rootId, gid)
        directory.findFile(name)?.let { cached ->
            if (runCatching { cached.openInputStream().use(GalleryArchive::digest) == digest }.getOrDefault(false)) return cached
            check(cached.delete()) { "Cannot replace invalid read cache" }
        }
        val partName = "$name.part"
        directory.findFile(partName)?.let { check(it.delete()) { "Cannot remove interrupted read cache" } }
        val part = directory.createFile(partName) ?: throw IOException("Cannot create read cache image")
        check(part.name == partName)
        try {
            part.openOutputStream().use(extract)
            check(part.openInputStream().use(GalleryArchive::digest) == digest) { "Read cache image digest mismatch" }
            check(part.renameTo(name) && part.name == name) { "Cannot publish read cache image" }
            return part
        } finally {
            if (part.name == partName) part.delete()
        }
    }

    @Synchronized
    fun removeGallery(rootId: String, gid: Long): Boolean {
        require(gid > 0)
        val root = cleanRootOnce(rootId)
        val gallery = root.findFile(DIRECTORY)?.findFile(gid.toString()) ?: return true
        check(gallery.isDirectory && gallery.name == gid.toString())
        return gallery.deleteTree()
    }

    companion object {
        const val DIRECTORY = "temp_read"
    }
}
