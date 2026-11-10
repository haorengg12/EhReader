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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface HistoryDao {
    @Query("SELECT " + GALLERY_COLUMNS + ", b.TIME FROM HISTORY b JOIN GALLERY_METADATA m ON m.GID = b.GID ORDER BY b.TIME DESC")
    fun list(): List<HistoryInfo>

    @Query("SELECT " + GALLERY_COLUMNS + ", b.TIME FROM HISTORY b JOIN GALLERY_METADATA m ON m.GID = b.GID WHERE b.GID = :gid")
    fun load(gid: Long): HistoryInfo?

    @Upsert
    fun put(record: HistoryRecord)

    @Query("SELECT * FROM HISTORY")
    fun records(): List<HistoryRecord>

    @Query("DELETE FROM HISTORY WHERE GID = :gid")
    fun deleteByKey(gid: Long)

    @Query("SELECT " + GALLERY_COLUMNS + ", b.TIME FROM HISTORY b JOIN GALLERY_METADATA m ON m.GID = b.GID ORDER BY b.TIME DESC")
    fun listLazy(): PagingSource<Int, HistoryInfo>

    @Query("DELETE FROM HISTORY")
    fun deleteAll()
}
