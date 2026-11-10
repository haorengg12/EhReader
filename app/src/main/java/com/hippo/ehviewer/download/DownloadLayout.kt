/* Copyright 2026 haorengg12. Licensed under GPL-3.0-or-later. */
package com.hippo.ehviewer.download

import java.util.Locale

/** 分类及文件名只由网站分类和 GID 决定，拒绝可越出授权根目录的路径。 */
object DownloadLayout {
    val categories = listOf("misc", "doujinshi", "manga", "artistcg", "gamecg", "imageset", "cosplay", "asianporn", "non-h", "western", "private")

    fun category(value: Int): String = categories.getOrNull(Integer.numberOfTrailingZeros(value).takeIf { value > 0 && value and (value - 1) == 0 } ?: -1) ?: "unknown"

    fun directory(gid: Long, category: Int): String {
        require(gid > 0)
        return "${category(category)}/$gid"
    }

    fun segments(path: String): List<String> = path.split('/').also { parts ->
        require(parts.isNotEmpty() && parts.all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it && ':' !in it && '\u0000' !in it })
    }

    fun page(index: Int, extension: String): String {
        require(index >= 0 && extension.lowercase(Locale.ROOT) in extensions)
        return String.format(Locale.ROOT, "%08d.%s", index + 1, extension.lowercase(Locale.ROOT))
    }

    val extensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "heic", "heif", "bmp")
    val pagePattern = Regex("[0-9]{8}\\.(jpg|jpeg|png|gif|webp|avif|heic|heif|bmp)")
}
