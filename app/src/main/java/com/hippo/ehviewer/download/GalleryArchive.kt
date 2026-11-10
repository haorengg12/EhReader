/* Copyright 2026 haorengg12. Licensed under GPL-3.0-or-later. */
package com.hippo.ehviewer.download

import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 归档只接受连续编号图片；校验页序、重复项、CRC 与内容散列，不提取任意归档路径。 */
object GalleryArchive {
    /** 直接读取授权 URI 提供的流，不重开 /proc 文件描述符路径；输出交给调用方管理。 */
    fun copyPage(input: InputStream, name: String, output: OutputStream, expectedDigest: String): Boolean {
        require(DownloadLayout.pagePattern.matches(name) && expectedDigest.matches(Regex("[0-9a-f]{64}")))
        return ZipInputStream(input.buffered()).use { zip ->
            var page = 1
            while (true) {
                val entry = zip.nextEntry ?: return false
                check(!entry.isDirectory && DownloadLayout.pagePattern.matches(entry.name) && entry.name.substringBefore('.').toInt() == page) { "Invalid archive page order" }
                if (page == name.substringBefore('.').toInt()) {
                    check(entry.name == name) { "Archive page name mismatch" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var length = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        length += count
                    }
                    zip.closeEntry()
                    check(length > 0 && digest.digest().joinToString("") { "%02x".format(it) } == expectedDigest) { "Archive page digest mismatch" }
                    return true
                }
                zip.closeEntry()
                page++
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    }

    fun write(names: List<String>, output: OutputStream, input: (String) -> InputStream) {
        validateNames(names)
        ZipOutputStream(output.buffered()).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                input(name).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun manifest(input: InputStream): Map<String, String> {
        val tracked = ArchiveInput(input)
        return ZipInputStream(tracked).use { zip ->
            val result = linkedMapOf<String, String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                check(!entry.isDirectory && DownloadLayout.pagePattern.matches(entry.name) && entry.name !in result) { "Invalid archive entry" }
                result[entry.name] = digest(zip)
                zip.closeEntry()
            }
            validateNames(result.keys.toList())
            // ZipInputStream 只验证 local entries；额外检查中央目录结束记录，拒绝截断归档。
            val buffer = ByteArray(8192)
            while (tracked.read(buffer) >= 0) { /* Drain the central directory. */ }
            tracked.verifyEnd(result.size)
            result
        }
    }

    fun copyPage(input: InputStream, index: Int, output: (String) -> OutputStream): Boolean = ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: return false
            check(DownloadLayout.pagePattern.matches(entry.name)) { "Invalid archive entry" }
            if (entry.name.substringBefore('.').toInt() == index + 1) {
                output(entry.name.substringAfter('.')).use { zip.copyTo(it) }
                zip.closeEntry()
                return true
            }
            zip.closeEntry()
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    fun digest(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var length = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            length += count
        }
        check(length > 0) { "Empty image" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun validateNames(names: List<String>) {
        check(names.isNotEmpty()) { "No images" }
        names.forEachIndexed { index, name ->
            check(DownloadLayout.pagePattern.matches(name) && name.substringBefore('.').toInt() == index + 1) { "Missing or unordered image" }
        }
    }

    private class ArchiveInput(input: InputStream) : FilterInputStream(input) {
        private val tail = ByteArray(65557)
        private var total = 0L

        private fun record(value: Int) {
            tail[(total % tail.size).toInt()] = value.toByte()
            total++
        }

        override fun read(): Int = `in`.read().also { if (it >= 0) record(it) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also { count ->
            for (index in 0 until count) record(buffer[offset + index].toInt())
        }

        fun verifyEnd(pages: Int) {
            val size = minOf(total, tail.size.toLong()).toInt()
            val bytes = ByteArray(size) { tail[((total - size + it) % tail.size).toInt()] }
            fun word(offset: Int): Int = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
            for (offset in size - 22 downTo 0) {
                if (bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4b.toByte() && bytes[offset + 2] == 5.toByte() && bytes[offset + 3] == 6.toByte()) {
                    if (offset + 22 + word(offset + 20) == size && word(offset + 10) == minOf(pages, 65535)) return
                }
            }
            error("Truncated archive central directory")
        }
    }
}
