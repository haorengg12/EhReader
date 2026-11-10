/* Copyright 2026 haorengg12. Licensed under GPL-3.0-or-later. */
package com.hippo.ehviewer.download

import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.net.toUri
import com.hippo.ehviewer.EhApplication.Companion.application
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadDirname
import com.hippo.unifile.UniFile
import com.hippo.unifile.openInputStream
import com.hippo.unifile.openOutputStream

/** 连接私有主库与授权文件系统；路径及发布规则由 GalleryFiles 统一执行。 */
object DownloadStorage {
    private val readCache = GalleryReadCache { UniFile.fromUri(application, it.toUri())?.let(::StorageFile) }
    private val files = GalleryFiles(
        EhDB::getDownloadLocation,
        EhDB::putDownloadLocation,
        EhDB::completeDownload,
        { UniFile.fromUri(application, it.toUri())?.let(::StorageFile) },
        { readable((it as StorageFile).file) },
        remove = EhDB::removeDownloadDirname,
    )

    fun container(gid: Long): UniFile? = (files.container(gid) as? StorageFile)?.file
    fun imageDirectory(gid: Long): UniFile? {
        val location = EhDB.getDownloadLocation(gid)
        return if (location?.phase == "complete" && location.format == "cbz") {
            val root = location.rootUri ?: throw java.io.IOException("Download root unavailable")
            (readCache.directory(root, gid) as StorageFile).file
        } else {
            (files.imageDirectory(gid) as? StorageFile)?.file
        }
    }
    fun archive(gid: Long): UniFile? = (files.archive(gid) as? StorageFile)?.file

    fun isCompletedArchive(gid: Long): Boolean = EhDB.getDownloadLocation(gid)?.let { it.phase == "complete" && it.format == "cbz" } == true

    /** 启动时清空当前及主库已登记各下载根的阅读缓存；无权限的根留待首次可访问时重试。 */
    fun clearReadCachesAtStartup() {
        val roots = EhDB.allDownloadDirname.mapNotNull { it.rootUri }.toMutableSet()
        Settings.downloadLocation?.uri?.toString()?.let(roots::add)
        roots.forEach { root ->
            runCatching { readCache.clearAtStartup(root) }.onFailure {
                Log.e("DownloadStorage", "Cannot clear startup read cache", it)
            }
        }
    }

    /** 按需解压一页到对应根的 temp_read/GID，读取和重试均不向成品目录写图片。 */
    fun readArchivePage(gid: Long, index: Int): UniFile? {
        val location = EhDB.getDownloadLocation(gid)?.takeIf { it.phase == "complete" && it.format == "cbz" } ?: return null
        val root = location.rootUri ?: throw java.io.IOException("Download root unavailable")
        val item = location.manifest?.lines()?.getOrNull(index) ?: throw java.io.IOException("Missing page manifest")
        val name = item.substringBefore(':')
        check(DownloadLayout.pagePattern.matches(name) && name.substringBefore('.').toInt() == index + 1) { "Invalid page manifest" }
        val archive = archive(gid) ?: throw java.io.IOException("Missing completed archive")
        return (
            readCache.page(root, gid, name, item.substringAfter(':')) { output ->
                archive.openFileDescriptor("r").use { descriptor ->
                    java.util.zip.ZipFile("/proc/self/fd/${descriptor.fd}").use { zip ->
                        check(GalleryArchive.copyPage(zip, name, output, item.substringAfter(':'))) { "Missing archive page" }
                    }
                }
            } as StorageFile
            ).file
    }

    fun prepare(info: GalleryInfo): UniFile {
        EhDB.putGalleryMetadata(info)
        if (isCompletedArchive(info.gid)) return imageDirectory(info.gid) ?: throw java.io.IOException("Read cache unavailable")
        return (
            files.prepare(
                info.gid,
                EhDB.getGalleryMetadata(info.gid)?.category ?: info.category,
                Settings.downloadLocation?.uri?.toString(),
                Settings.saveAsCbz,
            ) as StorageFile
            ).file
    }

    fun finish(gid: Long, pages: Int, checkActive: () -> Unit = {}) = files.finish(gid, pages, checkActive)
    fun verify(location: DownloadDirname): Boolean = files.verify(location)

    fun readable(file: UniFile): Boolean = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.openInputStream().use { BitmapFactory.decodeStream(it, null, options) }
        options.outWidth > 0 && options.outHeight > 0
    }.getOrDefault(false)

    fun deleteFiles(gid: Long): Boolean {
        check(!com.hippo.ehviewer.spider.SpiderQueen.isInUse(gid)) { "Gallery is being read or downloaded" }
        val location = EhDB.getDownloadLocation(gid) ?: return false
        val root = location.rootUri ?: return false
        if (!readCache.removeGallery(root, gid)) return false
        return files.deleteFiles(gid)
    }
}

private class StorageFile(val file: UniFile) : GalleryFile {
    override val name get() = file.name
    override val isDirectory get() = file.isDirectory
    override fun findFile(name: String): GalleryFile? = file.findFile(name)?.let(::StorageFile)
    override fun createFile(name: String): GalleryFile? = file.createFile(name)?.let(::StorageFile)
    override fun createDirectory(name: String): GalleryFile? = file.createDirectory(name)?.let(::StorageFile)
    override fun listFiles(): Array<GalleryFile>? = file.listFiles()?.map(::StorageFile)?.toTypedArray()
    override fun canWrite(): Boolean = file.ensureDir() && file.canWrite()
    override fun delete(): Boolean = file.delete()
    override fun renameTo(name: String): Boolean = file.renameTo(name)
    override fun openInputStream() = file.openInputStream()
    override fun openOutputStream() = file.openOutputStream()
}
