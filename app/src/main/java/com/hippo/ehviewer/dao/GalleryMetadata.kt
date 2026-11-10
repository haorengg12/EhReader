/*
 * Copyright 2022 Moedog
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.ehviewer.dao

import androidx.core.net.toUri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.GalleryTagGroup
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "GALLERY_METADATA")
data class GalleryMetadata(
    @PrimaryKey @ColumnInfo(name = "GID") val gid: Long,
    @ColumnInfo(name = "TOKEN") val token: String? = null,
    @ColumnInfo(name = "TITLE") val title: String? = null,
    @ColumnInfo(name = "TITLE_JPN") val titleJpn: String? = null,
    @ColumnInfo(name = "THUMB") val thumb: String? = null,
    @ColumnInfo(name = "CATEGORY") val category: Int? = null,
    @ColumnInfo(name = "POSTED") val posted: String? = null,
    @ColumnInfo(name = "UPLOADER") val uploader: String? = null,
    @ColumnInfo(name = "RATING") val rating: Float? = null,
    @ColumnInfo(name = "SIMPLE_LANGUAGE") val simpleLanguage: String? = null,
    @ColumnInfo(name = "SOURCE") val source: String? = null,
    @ColumnInfo(name = "DETAIL_URL") val detailUrl: String? = null,
    @ColumnInfo(name = "PARENT") val parent: String? = null,
    @ColumnInfo(name = "VISIBLE") val visible: String? = null,
    @ColumnInfo(name = "LANGUAGE") val language: String? = null,
    @ColumnInfo(name = "PAGES") val pages: Int? = null,
    @ColumnInfo(name = "SIZE") val size: String? = null,
    @ColumnInfo(name = "FAVORITE_COUNT") val favoriteCount: Int? = null,
    @ColumnInfo(name = "RATING_COUNT") val ratingCount: Int? = null,
    @ColumnInfo(name = "TORRENT_COUNT") val torrentCount: Int? = null,
    @ColumnInfo(name = "TORRENT_URL") val torrentUrl: String? = null,
    @ColumnInfo(name = "TAGS") val tags: String? = null,
    @ColumnInfo(name = "ACCOUNT_ID") val accountId: String? = null,
    @ColumnInfo(name = "ACCOUNT_SNAPSHOT") val accountSnapshot: String? = null,
    @ColumnInfo(name = "UPDATED_AT") val updatedAt: Long? = null,
    @ColumnInfo(name = "METADATA_STATE") val metadataState: String = "partial",
) {
    /** 从主库恢复展示信息；账号快照仅在身份一致时用于展示，缺失仍保留为待补全。 */
    fun galleryInfo(account: String?): BaseGalleryInfo = BaseGalleryInfo(
        gid = gid,
        token = token,
        title = title,
        titleJpn = titleJpn,
        thumb = thumb,
        category = category ?: 0,
        posted = posted,
        uploader = uploader,
        rating = rating ?: Float.NaN,
        pages = pages ?: 0,
        simpleLanguage = simpleLanguage,
    ).apply {
        if (account == accountId && accountSnapshot != null) {
            val snapshot = JSONObject(accountSnapshot)
            favoriteSlot = snapshot.optInt("favoriteSlot", -2)
            favoriteName = snapshot.optString("favoriteName").takeIf { it.isNotBlank() }
            favoriteNote = snapshot.optString("favoriteNote").takeIf { it.isNotBlank() }
            rated = snapshot.optBoolean("rated")
        }
        simpleTags = tagGroups()?.flatMap { group -> group.map { "${group.groupName}:$it" } }?.toTypedArray()
    }

    fun galleryDetail(account: String?): GalleryDetail = GalleryDetail(
        galleryInfo = galleryInfo(account),
        torrentCount = torrentCount ?: 0,
        torrentUrl = torrentUrl,
        parent = parent,
        visible = visible,
        language = language,
        size = size,
        favoriteCount = favoriteCount ?: 0,
        isFavorited = account == accountId && accountSnapshot?.let { JSONObject(it).optBoolean("favorited") } == true,
        ratingCount = ratingCount ?: 0,
        tags = tagGroups(),
    )

    private fun tagGroups(): Array<GalleryTagGroup>? = tags?.let { raw ->
        val array = JSONArray(raw)
        val groups = linkedMapOf<String, ArrayList<String>>()
        for (index in 0 until array.length()) {
            val item = array.get(index)
            if (item is JSONObject) {
                val namespace = item.optString("namespace")
                val values = item.getJSONArray("tags")
                for (tag in 0 until values.length()) groups.getOrPut(namespace) { arrayListOf() }.add(values.getString(tag))
            } else {
                val tag = item.toString()
                groups.getOrPut(tag.substringBefore(':', "")) { arrayListOf() }.add(tag.substringAfter(':'))
            }
        }
        groups.map { (namespace, values) -> GalleryTagGroup(values, namespace) }.toTypedArray()
    }

    /** 合并成功取得的在线字段；列表响应不清空详情，业务快照不反写已刷新的主记录。 */
    fun merge(info: GalleryInfo, url: String? = null, account: String? = null): GalleryMetadata {
        require(info.gid == gid && gid > 0)
        val detail = info as? GalleryDetail
        val tagArray = detail?.tags?.let { groups ->
            JSONArray().apply {
                groups.forEach { group ->
                    put(JSONObject().put("namespace", group.groupName).put("tags", JSONArray(group.toList())))
                }
            }.toString()
        } ?: info.simpleTags?.let { JSONArray(it.toList()).toString() }
        return copy(
            token = info.token?.takeIf { it.isNotBlank() } ?: token,
            title = info.title?.takeIf { it.isNotBlank() } ?: title,
            titleJpn = info.titleJpn?.takeIf { it.isNotBlank() } ?: titleJpn,
            thumb = info.thumb ?: thumb,
            category = info.category.takeIf { it != 0 } ?: category,
            posted = info.posted ?: posted,
            uploader = info.uploader ?: uploader,
            rating = info.rating.takeIf { it.isFinite() && it >= 0 && (info.title != null || detail != null) } ?: rating,
            simpleLanguage = info.simpleLanguage ?: simpleLanguage,
            source = url?.toUri()?.host ?: source,
            detailUrl = url?.substringBefore('?') ?: detailUrl,
            parent = detail?.parent ?: parent,
            visible = detail?.visible ?: visible,
            language = detail?.language ?: language,
            pages = info.pages.takeIf { it > 0 } ?: pages,
            size = detail?.size ?: size,
            favoriteCount = detail?.favoriteCount ?: favoriteCount,
            ratingCount = detail?.ratingCount ?: ratingCount,
            torrentCount = detail?.torrentCount ?: torrentCount,
            torrentUrl = detail?.torrentUrl ?: torrentUrl,
            tags = if (detail != null) tagArray ?: tags else tags ?: tagArray,
            accountId = if (detail != null && url != null) account else accountId,
            accountSnapshot = if (detail != null && url != null) {
                JSONObject().put("favorited", detail.isFavorited)
                    .put("favoriteName", detail.favoriteName).put("favoriteSlot", detail.favoriteSlot)
                    .put("rated", detail.rated).put("favoriteNote", detail.favoriteNote).toString()
            } else {
                accountSnapshot
            },
            updatedAt = if (url != null) System.currentTimeMillis() else updatedAt,
            metadataState = if (detail != null) "complete" else metadataState,
        )
    }
}

// 列表使用既有内存展示模型，公共字段只从主表读取。
const val GALLERY_COLUMNS = "m.GID, m.TOKEN, m.TITLE, m.TITLE_JPN, m.THUMB, COALESCE(m.CATEGORY, 0) AS CATEGORY, m.POSTED, m.UPLOADER, COALESCE(m.RATING, 0) AS RATING, m.SIMPLE_LANGUAGE"
