// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.dao

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 所有调用都必须位于业务写入事务内；派生表可随时从下载和元数据重建。 */
internal object DownloadTagIndex {
    const val CREATE_TAG = "CREATE TABLE IF NOT EXISTS `DOWNLOAD_TAG` (`GID` INTEGER NOT NULL, `NAMESPACE` TEXT NOT NULL, `TAG` TEXT NOT NULL, PRIMARY KEY(`GID`, `NAMESPACE`, `TAG`), FOREIGN KEY(`GID`) REFERENCES `DOWNLOADS`(`GID`) ON UPDATE NO ACTION ON DELETE CASCADE)"
    const val CREATE_REVERSE = "CREATE INDEX IF NOT EXISTS `index_DOWNLOAD_TAG_NAMESPACE_TAG_GID` ON `DOWNLOAD_TAG` (`NAMESPACE`, `TAG`, `GID`)"
    const val CREATE_STAT = "CREATE TABLE IF NOT EXISTS `DOWNLOAD_TAG_STAT` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `NAMESPACE` TEXT NOT NULL, `TAG` TEXT NOT NULL, `TAG_COUNT` INTEGER NOT NULL)"
    const val CREATE_UNIQUE = "CREATE UNIQUE INDEX IF NOT EXISTS `index_DOWNLOAD_TAG_STAT_NAMESPACE_TAG` ON `DOWNLOAD_TAG_STAT` (`NAMESPACE`, `TAG`)"
    const val INSERT_TAG = "INSERT INTO DOWNLOAD_TAG (GID,NAMESPACE,TAG) VALUES (?,?,?)"
    const val DELETE_TAG = "DELETE FROM DOWNLOAD_TAG WHERE GID=? AND NAMESPACE=? AND TAG=?"
    const val INSERT_STAT = "INSERT OR IGNORE INTO DOWNLOAD_TAG_STAT (NAMESPACE,TAG,TAG_COUNT) VALUES (?,?,0)"
    const val INCREMENT = "UPDATE DOWNLOAD_TAG_STAT SET TAG_COUNT=TAG_COUNT+1 WHERE NAMESPACE=? AND TAG=?"
    const val DECREMENT = "UPDATE DOWNLOAD_TAG_STAT SET TAG_COUNT=TAG_COUNT-1 WHERE NAMESPACE=? AND TAG=?"
    const val DELETE_ZERO = "DELETE FROM DOWNLOAD_TAG_STAT WHERE NAMESPACE=? AND TAG=? AND TAG_COUNT<=0"
    const val RECOUNT = "INSERT INTO DOWNLOAD_TAG_STAT (NAMESPACE,TAG,TAG_COUNT) SELECT NAMESPACE,TAG,COUNT(*) FROM DOWNLOAD_TAG GROUP BY NAMESPACE,TAG"
    const val SOURCE = "SELECT d.GID,m.TAGS FROM DOWNLOADS d JOIN GALLERY_METADATA m ON m.GID=d.GID ORDER BY d.GID"

    /** 使用集合差同步，重复新增或进度变化不会重复增减全局画廊数。 */
    fun sync(sql: SupportSQLiteDatabase, gid: Long, raw: String?) {
        check(sql.inTransaction())
        if (!sql.query("SELECT 1 FROM DOWNLOADS WHERE GID=?", arrayOf(gid)).use { it.moveToFirst() }) return
        val next = DownloadTagKey.fromJson(raw)
        val previous = sql.query("SELECT NAMESPACE,TAG FROM DOWNLOAD_TAG WHERE GID=?", arrayOf(gid)).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(DownloadTagKey(cursor.getString(0), cursor.getString(1))) }
        }
        for (key in previous - next) {
            val args = arrayOf<Any>(key.namespace, key.tag)
            sql.execSQL(DELETE_TAG, arrayOf<Any>(gid, key.namespace, key.tag))
            sql.execSQL(DECREMENT, args)
            sql.execSQL(DELETE_ZERO, args)
        }
        for (key in next - previous) {
            val args = arrayOf<Any>(key.namespace, key.tag)
            sql.execSQL(INSERT_TAG, arrayOf<Any>(gid, key.namespace, key.tag))
            sql.execSQL(INSERT_STAT, args)
            sql.execSQL(INCREMENT, args)
        }
    }

    /** 迁移、备份恢复及人工纠偏复用全量重建；仅索引仍属于下载列表的画廊。 */
    fun rebuild(sql: SupportSQLiteDatabase) {
        check(sql.inTransaction())
        sql.execSQL("DELETE FROM DOWNLOAD_TAG")
        sql.execSQL("DELETE FROM DOWNLOAD_TAG_STAT")
        sql.compileStatement(INSERT_TAG).use { insert ->
            sql.query(SOURCE).use { cursor ->
                while (cursor.moveToNext()) {
                    val gid = cursor.getLong(0)
                    val raw = if (cursor.isNull(1)) null else cursor.getString(1)
                    for (key in DownloadTagKey.fromJson(raw)) {
                        insert.bindLong(1, gid)
                        insert.bindString(2, key.namespace)
                        insert.bindString(3, key.tag)
                        insert.executeInsert()
                    }
                }
            }
        }
        sql.execSQL(RECOUNT)
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_TAG)
            db.execSQL(CREATE_REVERSE)
            db.execSQL(CREATE_STAT)
            db.execSQL(CREATE_UNIQUE)
            rebuild(db)
        }
    }
}
