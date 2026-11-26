/* Copyright 2026 haorengg12. Licensed under GPL-3.0-or-later. */
package com.hippo.ehviewer.download

import android.graphics.BitmapFactory
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import com.hippo.ehviewer.EhApplication.Companion.application
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadDirname
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.encodeSpiderInfo
import com.hippo.ehviewer.spider.readFromCache
import com.hippo.ehviewer.spider.readFromUniFile
import com.hippo.unifile.TreeDocumentFile
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
                archive.openInputStream().use { input ->
                    check(GalleryArchive.copyPage(input, name, output, item.substringAfter(':'))) { "Missing archive page" }
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
            ).file.also {
            val recovery = readFromCache(info.gid) ?: EhDB.getGalleryMetadata(info.gid)?.let { metadata ->
                metadata.pages?.takeIf { it > 0 }?.let { SpiderInfo(metadata.gid, metadata.token, it) }
            } ?: throw java.io.IOException("Missing gallery recovery identity")
            saveRecoveryInfo(recovery)
        }
    }

    fun finish(gid: Long, pages: Int, checkActive: () -> Unit = {}) {
        files.finish(gid, pages, { stage -> Log.i("DownloadStorage", "Finalize gid=$gid pages=$pages: $stage") }, checkActive)
        Log.i("DownloadStorage", "Finalize gid=$gid pages=$pages: done")
    }
    fun verify(location: DownloadDirname): Boolean = files.verify(location)

    fun readable(file: UniFile): Boolean = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.openInputStream().use { BitmapFactory.decodeStream(it, null, options) }
        options.outWidth > 0 && options.outHeight > 0
    }.getOrDefault(false)

    /** 正常下载准备、完成及退出时保存 .ehreader；恢复旧的新布局任务时也补齐该文件。 */
    @Synchronized
    fun saveRecoveryInfo(info: SpiderInfo) {
        val location = EhDB.getDownloadLocation(info.gid) ?: return
        val directory = container(info.gid) ?: throw java.io.IOException("Download directory unavailable")
        info.downloadFormat = location.format
        writeRecoveryInfo(directory, info)
    }

    @Synchronized
    internal fun writeRecoveryInfo(directory: UniFile, info: SpiderInfo) = GalleryRecoveryFile.write(StorageFile(directory), info.encodeSpiderInfo())

    fun readRecoveryInfo(directory: UniFile): SpiderInfo? = GalleryRecoveryFile.names.firstNotNullOfOrNull { name ->
        directory.findFile(name)?.let(::readFromUniFile)
    }

    internal fun storageFile(file: UniFile): GalleryFile = StorageFile(file)
    internal fun uniFile(file: GalleryFile): UniFile = (file as StorageFile).file
    internal fun removeReadCache(rootUri: String, gid: Long): Boolean = readCache.removeGallery(rootUri, gid)

    internal fun captureDeletion(location: DownloadDirname): GalleryDeletionPlan = GalleryDeletionPlan.capture(location) {
        val uri = it.toUri()
        // 保留数据库记录的精确 document URI，不能重新解释成 SAF tree 的顶层目录。
        val root = if (UniFile.isTreeUri(uri) && DocumentsContract.isDocumentUri(application, uri)) {
            TreeDocumentFile(null, application, uri)
        } else {
            UniFile.fromUri(application, uri)
        }
        root?.let(::StorageFile)
    }
}

private class StorageFile(val file: UniFile) : GalleryFile {
    override val name get() = file.name
    override val isDirectory get() = file.isDirectory
    override val identity: String
        get() = if (file.uri.scheme == "file") java.io.File(file.uri.path!!).canonicalPath else file.uri.toString()
    override val isSymbolicLink: Boolean
        get() = file.uri.scheme == "file" && java.io.File(file.uri.path!!).let {
            java.io.File(it.parentFile?.canonicalFile, it.name).absoluteFile != it.canonicalFile
        }
    override fun findFile(name: String): GalleryFile? = file.findFile(name)?.let(::StorageFile)
    override fun createFile(name: String): GalleryFile? = file.createFile(name)?.let(::StorageFile)
    override fun createDirectory(name: String): GalleryFile? = file.createDirectory(name)?.let(::StorageFile)
    override fun listFiles(): Array<GalleryFile>? {
        if (!UniFile.isTreeUri(file.uri)) return file.listFiles()?.map(::StorageFile)?.toTypedArray()
        // UniFile 的宽松列举会把撤权/提供方错误当作空目录；删除必须使用可失败的完整列举。
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(file.uri, DocumentsContract.getDocumentId(file.uri))
        val result = mutableListOf<GalleryFile>()
        val cursor = application.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?: throw java.io.IOException("Cannot enumerate SAF directory")
        cursor.use {
            while (it.moveToNext()) {
                val child = DocumentsContract.buildDocumentUriUsingTree(file.uri, it.getString(0))
                result.add(StorageFile(TreeDocumentFile(file, application, child)))
            }
        }
        return result.toTypedArray()
    }
    override fun canWrite(): Boolean = file.ensureDir() && file.canWrite()
    override fun delete(): Boolean = if (file.uri.scheme == "file") java.io.File(file.uri.path!!).delete() else file.delete()
    override fun renameTo(name: String): Boolean = file.renameTo(name)
    override fun openInputStream() = file.openInputStream()
    override fun openOutputStream() = file.openOutputStream()
}
