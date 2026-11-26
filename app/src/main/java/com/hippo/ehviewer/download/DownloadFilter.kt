// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

import android.os.Parcelable
import com.hippo.ehviewer.dao.DownloadTagKey
import kotlinx.parcelize.Parcelize

@Parcelize
data class DownloadFilter(
    val all: List<DownloadTagKey> = emptyList(),
    val any: List<DownloadTagKey> = emptyList(),
    val excluded: List<DownloadTagKey> = emptyList(),
    val title: String = "",
    val category: Int? = null,
    val language: String? = null,
    val minimumRating: Float? = null,
    val maximumRating: Float? = null,
    val state: Int? = null,
    val sort: DownloadFilterSort = DownloadFilterSort.NEWEST,
) : Parcelable

enum class DownloadFilterSort(val order: String) {
    NEWEST("d.TIME DESC,d.GID DESC"),
    OLDEST("d.TIME ASC,d.GID ASC"),
    TITLE("COALESCE(m.TITLE,'') COLLATE NOCASE ASC,d.GID ASC"),
    TITLE_DESC("COALESCE(m.TITLE,'') COLLATE NOCASE DESC,d.GID DESC"),
    RATING("m.RATING DESC,d.GID DESC"),
    RATING_ASC("m.RATING ASC,d.GID ASC"),
    CATEGORY("m.CATEGORY ASC,d.GID ASC"),
    GID("d.GID DESC"),
}

internal data class DownloadSql(val sql: String, val args: List<Any>)

/** 参数化组合查询；标签身份与索引共用规则，所有排序以 GID 打破并列。 */
internal class DownloadFilterQuery(filter: DownloadFilter, groupOnly: Boolean = false, label: String? = null) {
    private val args = mutableListOf<Any>()
    private val clauses = mutableListOf<String>()
    private val order = filter.sort.order

    init {
        fun tagPredicate(key: DownloadTagKey): String {
            val normalized = DownloadTagKey.of(key.namespace, key.tag)
            args.add(normalized.namespace)
            args.add(normalized.tag)
            return "EXISTS (SELECT 1 FROM DOWNLOAD_TAG t WHERE t.GID=d.GID AND t.NAMESPACE=? AND t.TAG=?)"
        }
        filter.all.map { DownloadTagKey.of(it.namespace, it.tag) }.distinct().forEach { clauses.add(tagPredicate(it)) }
        if (filter.any.isNotEmpty()) clauses.add(filter.any.distinct().joinToString(" OR ", "(", ")") { tagPredicate(it) })
        filter.excluded.distinct().forEach { clauses.add("NOT " + tagPredicate(it)) }
        if (filter.title.isNotEmpty()) {
            clauses.add("(instr(lower(COALESCE(m.TITLE,'')),lower(?))>0 OR instr(lower(COALESCE(m.TITLE_JPN,'')),lower(?))>0)")
            args.add(filter.title)
            args.add(filter.title)
        }
        filter.category?.let {
            clauses.add("m.CATEGORY=?")
            args.add(it)
        }
        filter.language?.let {
            clauses.add("$LANGUAGE=?")
            args.add(it)
        }
        filter.minimumRating?.let {
            require(it.isFinite() && it in 0f..5f)
            clauses.add("m.RATING>=?")
            args.add(it)
        }
        filter.maximumRating?.let {
            require(it.isFinite() && it in 0f..5f)
            clauses.add("m.RATING<=?")
            args.add(it)
        }
        require(filter.minimumRating == null || filter.maximumRating == null || filter.minimumRating <= filter.maximumRating)
        filter.state?.let {
            require(it in 0..4)
            clauses.add("d.STATE=?")
            args.add(it)
        }
        if (groupOnly) {
            if (label == null) {
                clauses.add("d.LABEL IS NULL")
            } else {
                clauses.add("d.LABEL=?")
                args.add(label)
            }
        }
    }

    private val source: String get() = " FROM DOWNLOADS d JOIN GALLERY_METADATA m ON m.GID=d.GID" +
        if (clauses.isEmpty()) "" else clauses.joinToString(" AND ", " WHERE ")

    fun gids() = DownloadSql("SELECT d.GID$source ORDER BY $order", args.toList())

    companion object {
        // 日语等画廊可能没有语言缩写；查询和选项面板都回退到已保存的完整语言。
        const val LANGUAGE = "COALESCE(NULLIF(m.SIMPLE_LANGUAGE,''),m.LANGUAGE,'')"
    }
}
