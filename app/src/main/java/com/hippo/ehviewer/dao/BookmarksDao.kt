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
interface BookmarksDao {
    @Query("SELECT " + GALLERY_COLUMNS + ", b.PAGE, b.TIME FROM BOOKMARKS b JOIN GALLERY_METADATA m ON m.GID = b.GID ORDER BY b.TIME DESC")
    fun list(): List<BookmarkInfo>

    @Query("SELECT " + GALLERY_COLUMNS + ", b.PAGE, b.TIME FROM BOOKMARKS b JOIN GALLERY_METADATA m ON m.GID = b.GID WHERE b.GID = :gid")
    fun load(gid: Long): BookmarkInfo?

    @Upsert
    fun put(record: BookmarkRecord)

    @Query("SELECT * FROM BOOKMARKS")
    fun records(): List<BookmarkRecord>

    @Query("DELETE FROM BOOKMARKS WHERE GID = :gid")
    fun deleteByKey(gid: Long)
}
