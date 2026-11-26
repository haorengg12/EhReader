// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.dao

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject

@Entity(
    tableName = "DOWNLOAD_TAG",
    primaryKeys = ["GID", "NAMESPACE", "TAG"],
    indices = [Index("NAMESPACE", "TAG", "GID")],
    foreignKeys = [ForeignKey(entity = DownloadRecord::class, parentColumns = ["GID"], childColumns = ["GID"], onDelete = ForeignKey.CASCADE)],
)
data class DownloadTag(
    @ColumnInfo(name = "GID") val gid: Long,
    @ColumnInfo(name = "NAMESPACE") val namespace: String,
    @ColumnInfo(name = "TAG") val tag: String,
)

@Entity(tableName = "DOWNLOAD_TAG_STAT", indices = [Index(value = ["NAMESPACE", "TAG"], unique = true)])
data class DownloadTagStat(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "_id") val id: Long = 0,
    @ColumnInfo(name = "NAMESPACE") val namespace: String,
    @ColumnInfo(name = "TAG") val tag: String,
    @ColumnInfo(name = "TAG_COUNT") val count: Int,
)

@Parcelize
data class DownloadTagKey(val namespace: String, val tag: String) : Parcelable {
    val identity: String get() = "$namespace:$tag"

    companion object {
        /** 索引和查询共用的身份规则：只去掉标签开头可重复的四种已知状态前缀。 */
        fun of(namespace: String, tag: String): DownloadTagKey {
            var start = 0
            while (start + 1 < tag.length && tag[start] == '_' && tag[start + 1] in "WLUD") start += 2
            return DownloadTagKey(namespace, tag.substring(start))
        }

        fun parse(input: String): DownloadTagKey {
            val colon = input.indexOf(':')
            return if (colon < 0) of("", input) else of(input.substring(0, colon), input.substring(colon + 1))
        }

        /** 兼容详情分组与列表扁平 JSON；不修改原文，损坏数据交由外层事务回滚。 */
        fun fromJson(raw: String?): Set<DownloadTagKey> = buildSet {
            if (raw == null) return@buildSet
            val groups = JSONArray(raw)
            for (i in 0 until groups.length()) {
                when (val group = groups.get(i)) {
                    is JSONObject -> {
                        val namespace = group.get("namespace") as? String ?: error("Invalid tag namespace")
                        val tags = group.getJSONArray("tags")
                        for (j in 0 until tags.length()) add(of(namespace, tags.get(j) as? String ?: error("Invalid tag name")))
                    }
                    is String -> add(parse(group))
                    else -> error("Invalid gallery tag JSON")
                }
            }
        }
    }
}
