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
import androidx.room.PrimaryKey

@Entity(tableName = "DOWNLOAD_LOCATIONS", foreignKeys = [ForeignKey(entity = GalleryMetadata::class, parentColumns = ["GID"], childColumns = ["GID"], onDelete = ForeignKey.RESTRICT)])
data class DownloadDirname(
    @PrimaryKey
    @ColumnInfo(name = "GID")
    var gid: Long = 0,

    @ColumnInfo(name = "DIRNAME")
    var dirname: String? = null,
    @ColumnInfo(name = "ROOT_URI") var rootUri: String? = null,
    @ColumnInfo(name = "ACTUAL_PATH") var actualPath: String? = null,
    @ColumnInfo(name = "TEMP_PATH") var tempPath: String? = null,
    @ColumnInfo(name = "FORMAT") var format: String = "cbz",
    @ColumnInfo(name = "PHASE") var phase: String = "pending",
    @ColumnInfo(name = "EXPECTED_PAGES") var expectedPages: Int = 0,
    @ColumnInfo(name = "LAST_ERROR") var lastError: String? = null,
    @ColumnInfo(name = "MANIFEST") var manifest: String? = null,
)
