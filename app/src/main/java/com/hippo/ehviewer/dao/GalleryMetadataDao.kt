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
interface GalleryMetadataDao {
    @Query("SELECT * FROM GALLERY_METADATA WHERE GID = :gid")
    fun load(gid: Long): GalleryMetadata?

    @Query("SELECT * FROM GALLERY_METADATA")
    fun list(): List<GalleryMetadata>

    @Upsert
    fun put(metadata: GalleryMetadata)

    @Query("SELECT * FROM READING_STATE WHERE GID = :gid")
    fun readingState(gid: Long): ReadingState?

    @Query("SELECT * FROM READING_STATE")
    fun readingStates(): List<ReadingState>

    @Upsert
    fun putReadingState(state: ReadingState)
}
