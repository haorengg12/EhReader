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
interface LocalFavoritesDao {
    @Query("SELECT " + GALLERY_COLUMNS + ", b.TIME FROM LOCAL_FAVORITES b JOIN GALLERY_METADATA m ON m.GID = b.GID ORDER BY b.TIME DESC")
    fun list(): List<LocalFavoriteInfo>

    @Query("SELECT " + GALLERY_COLUMNS + ", b.TIME FROM LOCAL_FAVORITES b JOIN GALLERY_METADATA m ON m.GID = b.GID WHERE b.GID = :gid")
    fun load(gid: Long): LocalFavoriteInfo?

    @Upsert
    fun put(record: LocalFavoriteRecord)

    @Query("SELECT * FROM LOCAL_FAVORITES")
    fun records(): List<LocalFavoriteRecord>

    @Query("DELETE FROM LOCAL_FAVORITES WHERE GID = :gid")
    fun deleteByKey(gid: Long)

    @Query("SELECT " + GALLERY_COLUMNS + ", b.TIME FROM LOCAL_FAVORITES b JOIN GALLERY_METADATA m ON m.GID = b.GID WHERE m.TITLE LIKE :title OR m.TITLE_JPN LIKE :title ORDER BY b.TIME DESC")
    fun list(title: String): List<LocalFavoriteInfo>

    @Query("SELECT EXISTS(SELECT 1 FROM LOCAL_FAVORITES WHERE GID = :gid)")
    fun contains(gid: Long): Boolean
}
