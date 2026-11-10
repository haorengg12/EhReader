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

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface DownloadsDao {
    @Query("SELECT " + GALLERY_COLUMNS + ", b.STATE, b.REMAINING_PAGES AS LEGACY, b.TIME, b.LABEL, b.FINISHED_PAGES, b.ATTEMPTED_PAGES, b.TOTAL_PAGES FROM DOWNLOADS b JOIN GALLERY_METADATA m ON m.GID = b.GID ORDER BY b.TIME DESC")
    fun list(): List<DownloadInfo>

    @Query("SELECT " + GALLERY_COLUMNS + ", b.STATE, b.REMAINING_PAGES AS LEGACY, b.TIME, b.LABEL, b.FINISHED_PAGES, b.ATTEMPTED_PAGES, b.TOTAL_PAGES FROM DOWNLOADS b JOIN GALLERY_METADATA m ON m.GID = b.GID WHERE b.GID = :gid")
    fun load(gid: Long): DownloadInfo?

    @Upsert
    fun put(record: DownloadRecord)

    @Query("SELECT * FROM DOWNLOADS")
    fun records(): List<DownloadRecord>

    @Query("DELETE FROM DOWNLOADS WHERE GID = :gid")
    fun deleteByKey(gid: Long)

    @Query("SELECT * FROM DOWNLOADS WHERE GID = :gid")
    fun record(gid: Long): DownloadRecord?
}
