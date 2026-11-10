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

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [GalleryMetadata::class, ReadingState::class, BookmarkRecord::class, DownloadRecord::class, DownloadLabel::class, DownloadDirname::class, Filter::class, HistoryRecord::class, LocalFavoriteRecord::class, QuickSearch::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class EhDatabase : RoomDatabase() {
    abstract fun galleryMetadataDao(): GalleryMetadataDao
    abstract fun bookmarksBao(): BookmarksDao
    abstract fun downloadDirnameDao(): DownloadDirnameDao
    abstract fun downloadLabelDao(): DownloadLabelDao
    abstract fun downloadsDao(): DownloadsDao
    abstract fun filterDao(): FilterDao
    abstract fun historyDao(): HistoryDao
    abstract fun localFavoritesDao(): LocalFavoritesDao
    abstract fun quickSearchDao(): QuickSearchDao
}

fun buildMainDB(context: Context): EhDatabase {
    // 独立新库不打开、不迁移、不改写 eh.db 及其 WAL；旧版本来源留待手动导入。
    // TODO: Remove allowMainThreadQueries
    return Room.databaseBuilder(context, EhDatabase::class.java, "ehreader.db").allowMainThreadQueries()
        .build()
}
