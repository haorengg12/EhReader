/* Copyright 2026 haorengg12. Licensed under GPL-3.0-or-later. */
package com.hippo.ehviewer.download

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

internal class MemoryGalleryFile(override var name: String, val parent: MemoryGalleryFile? = null, val directory: Boolean = true) : GalleryFile {
    val children = linkedMapOf<String, MemoryGalleryFile>()
    var data = byteArrayOf()
    var writable = true
    var failOutput = false
    var failDelete = false
    var failRename = false
    var failList = false
    override val isDirectory get() = directory
    override fun findFile(name: String) = children[name]
    override fun createDirectory(name: String): MemoryGalleryFile? = if (writable) children.getOrPut(name) { MemoryGalleryFile(name, this) } else null
    override fun createFile(name: String): MemoryGalleryFile? = if (writable) children.getOrPut(name) { MemoryGalleryFile(name, this, false) } else null
    override fun listFiles(): Array<GalleryFile>? = if (directory && !failList) children.values.toTypedArray() else null
    override fun canWrite() = writable
    override fun delete(): Boolean = !failDelete && writable && (parent?.children?.remove(name) != null)
    override fun renameTo(name: String): Boolean {
        if (failRename || !writable || parent == null || parent.failRename || name in parent.children) return false
        parent.children.remove(this.name)
        this.name = name
        parent.children[name] = this
        return true
    }
    override fun openInputStream() = ByteArrayInputStream(data)
    override fun openOutputStream(): OutputStream {
        if (!writable || failOutput || parent?.failOutput == true) throw IOException("No space or permission")
        return object : ByteArrayOutputStream() {
            override fun close() {
                data = toByteArray()
                super.close()
            }
        }
    }
    fun put(name: String, content: ByteArray = byteArrayOf(1, 2, 3)): MemoryGalleryFile = createFile(name)!!.also { it.data = content }
}
