// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

/** 保留旧副本直至新身份文件写完并更名成功；中断时可读取 bak/part，禁止直接截断唯一副本。 */
internal object GalleryRecoveryFile {
    const val NAME = ".ehreader"
    val names = listOf(NAME, "$NAME.bak", "$NAME.part")

    fun write(directory: GalleryFile, bytes: ByteArray) {
        check(directory.isDirectory && bytes.isNotEmpty())
        val part = directory.findFile("$NAME.part") ?: directory.createFile("$NAME.part") ?: error("Cannot create recovery file")
        check(part.name == "$NAME.part" && !part.isDirectory)
        part.openOutputStream().use { it.write(bytes) }
        check(part.openInputStream().use { it.readBytes().contentEquals(bytes) }) { "Recovery file verification failed" }
        val old = directory.findFile(NAME)
        if (old != null) {
            check(!old.isDirectory)
            directory.findFile("$NAME.bak")?.let { check(it.delete()) { "Cannot rotate recovery backup" } }
            check(old.renameTo("$NAME.bak")) { "Cannot preserve recovery file" }
        }
        if (!part.renameTo(NAME)) {
            old?.renameTo(NAME)
            error("Cannot publish recovery file")
        }
        directory.findFile("$NAME.bak")?.delete()
    }
}
