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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "DOWNLOADS", indices = [Index("TIME"), Index("LABEL")], foreignKeys = [ForeignKey(entity = GalleryMetadata::class, parentColumns = ["GID"], childColumns = ["GID"], onDelete = ForeignKey.RESTRICT)])
data class DownloadRecord(
    @PrimaryKey @ColumnInfo(name = "GID") val gid: Long,
    @ColumnInfo(name = "STATE") val state: Int = 0,
    @ColumnInfo(name = "REMAINING_PAGES") val remainingPages: Int = 0,
    @ColumnInfo(name = "TIME") val time: Long = 0,
    @ColumnInfo(name = "LABEL") val label: String? = null,
    @ColumnInfo(name = "LEGACY_DOWNLOAD_PATH") val legacyDownloadPath: String? = null,
    @ColumnInfo(name = "FINISHED_PAGES") val finishedPages: Int = 0,
    @ColumnInfo(name = "ATTEMPTED_PAGES") val attemptedPages: Int = 0,
    @ColumnInfo(name = "TOTAL_PAGES") val totalPages: Int = 0,
    /** 是否来自旧版数据库导入，供后续旧文件路径迁移业务使用；新下载沿用 false。 */
    @ColumnInfo(name = "IMPORTED_FROM_LEGACY", defaultValue = "0") val importedFromLegacy: Boolean = false,
)

@Entity(tableName = "HISTORY", indices = [Index("TIME")], foreignKeys = [ForeignKey(entity = GalleryMetadata::class, parentColumns = ["GID"], childColumns = ["GID"], onDelete = ForeignKey.RESTRICT)])
data class HistoryRecord(
    @PrimaryKey @ColumnInfo(name = "GID") val gid: Long,
    @ColumnInfo(name = "TIME") val time: Long = 0,
)

@Entity(tableName = "LOCAL_FAVORITES", indices = [Index("TIME")], foreignKeys = [ForeignKey(entity = GalleryMetadata::class, parentColumns = ["GID"], childColumns = ["GID"], onDelete = ForeignKey.RESTRICT)])
data class LocalFavoriteRecord(
    @PrimaryKey @ColumnInfo(name = "GID") val gid: Long,
    @ColumnInfo(name = "TIME") val time: Long = 0,
)

@Entity(tableName = "BOOKMARKS", foreignKeys = [ForeignKey(entity = GalleryMetadata::class, parentColumns = ["GID"], childColumns = ["GID"], onDelete = ForeignKey.RESTRICT)])
data class BookmarkRecord(
    @PrimaryKey @ColumnInfo(name = "GID") val gid: Long,
    @ColumnInfo(name = "PAGE") val page: Int = 0,
    @ColumnInfo(name = "TIME") val time: Long = 0,
)

@Entity(tableName = "READING_STATE", foreignKeys = [ForeignKey(entity = GalleryMetadata::class, parentColumns = ["GID"], childColumns = ["GID"], onDelete = ForeignKey.RESTRICT)])
data class ReadingState(
    @PrimaryKey @ColumnInfo(name = "GID") val gid: Long,
    @ColumnInfo(name = "PAGE") val page: Int = 0,
    @ColumnInfo(name = "SPIDER_INFO") val spiderInfo: ByteArray? = null,
)
