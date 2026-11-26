// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

import androidx.sqlite.db.SimpleSQLiteQuery
import com.hippo.ehviewer.EhApplication.Companion.ehDatabase
import com.hippo.ehviewer.dao.DownloadTagKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DownloadTagCount(val key: DownloadTagKey, val count: Int)

internal object DownloadFilterStore {
    /** 在后台按筛选顺序读取全部 GID，交给下载列表按需绑定可见行。 */
    suspend fun gids(filter: DownloadFilter, groupOnly: Boolean, label: String?): List<Long> = withContext(Dispatchers.IO) {
        val query = DownloadFilterQuery(filter, groupOnly, label).gids()
        ehDatabase.query(SimpleSQLiteQuery(query.sql, query.args.toTypedArray())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
    }

    /** 此面板始终展示全部下载的统计，包含暂停、失败和未完成的下载。 */
    suspend fun tags(search: String, page: Int): List<DownloadTagCount> = withContext(Dispatchers.IO) {
        ehDatabase.query(
            SimpleSQLiteQuery(
                "SELECT NAMESPACE,TAG,TAG_COUNT FROM DOWNLOAD_TAG_STAT WHERE instr(lower(NAMESPACE || ':' || TAG),lower(?))>0 ORDER BY TAG_COUNT DESC,NAMESPACE,TAG LIMIT 51 OFFSET ?",
                arrayOf<Any>(search, page.coerceAtLeast(0) * 50),
            ),
        ).use { c -> buildList { while (c.moveToNext()) add(DownloadTagCount(DownloadTagKey(c.getString(0), c.getString(1)), c.getInt(2))) } }
    }

    suspend fun languages(): List<String> = withContext(Dispatchers.IO) {
        ehDatabase.query("SELECT DISTINCT ${DownloadFilterQuery.LANGUAGE} FROM DOWNLOADS d JOIN GALLERY_METADATA m ON m.GID=d.GID ORDER BY 1", emptyArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }
}
