// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.dao

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.hippo.ehviewer.download.DownloadFilter
import com.hippo.ehviewer.download.DownloadFilterQuery
import com.hippo.ehviewer.download.DownloadFilterSort
import com.hippo.ehviewer.download.DownloadSql
import java.io.File
import java.lang.reflect.Proxy
import java.sql.DriverManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** JDBC 只提供 SQLite 引擎与 Android 接口适配；迁移、解析、同步及查询执行生产代码。 */
class DownloadTagDatabaseTest {
    private class Fixture(version: Int = 3) : AutoCloseable {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val sql: SupportSQLiteDatabase = proxy { name, args ->
            when (name) {
                "inTransaction" -> !connection.autoCommit
                "execSQL" -> {
                    execute(args[0] as String, (args.getOrNull(1) as? Array<*>)?.toList().orEmpty())
                    Unit
                }
                "query" -> cursor(rows(args[0] as String, (args.getOrNull(1) as? Array<*>)?.toList().orEmpty()))
                "compileStatement" -> statement(args[0] as String)
                else -> error("Unsupported database method $name")
            }
        }

        init {
            execute("PRAGMA foreign_keys=ON")
            val entities = JSONObject(File("schemas/com.hippo.ehviewer.dao.EhDatabase/$version.json").readText()).getJSONObject("database").getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indexes = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indexes.length()) execute(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }

        fun execute(text: String, args: List<Any?> = emptyList()) {
            connection.prepareStatement(text).use { s ->
                args.forEachIndexed { i, value -> s.setObject(i + 1, value) }
                s.execute()
            }
        }

        fun rows(text: String, args: List<Any?> = emptyList()): List<List<Any?>> = connection.prepareStatement(text).use { s ->
            args.forEachIndexed { i, value -> s.setObject(i + 1, value) }
            s.executeQuery().use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getObject(it) }) } }
        }

        fun rows(query: DownloadSql) = rows(query.sql, query.args)

        fun transaction(block: () -> Unit) {
            connection.autoCommit = false
            try {
                block()
                connection.commit()
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }

        fun gallery(gid: Long, tags: String?, download: Boolean = true, state: Int = 0) {
            execute("INSERT INTO GALLERY_METADATA (GID,TAGS,METADATA_STATE,TITLE,CATEGORY,SIMPLE_LANGUAGE,RATING) VALUES (?,?,'complete','Same title',4,'EN',4.5)", listOf(gid, tags))
            if (download) execute("INSERT INTO DOWNLOADS (GID,STATE,REMAINING_PAGES,TIME,FINISHED_PAGES,ATTEMPTED_PAGES,TOTAL_PAGES) VALUES (?,?,0,100,0,0,0)", listOf(gid, state))
        }

        fun sync(gid: Long, tags: String?) = transaction {
            execute("UPDATE GALLERY_METADATA SET TAGS=? WHERE GID=?", listOf(tags, gid))
            DownloadTagIndex.sync(sql, gid, tags)
        }

        fun stats() = rows("SELECT NAMESPACE,TAG,TAG_COUNT FROM DOWNLOAD_TAG_STAT ORDER BY NAMESPACE,TAG")

        private fun statement(text: String): SupportSQLiteStatement {
            val s = connection.prepareStatement(text)
            return proxy { name, args ->
                when (name) {
                    "bindLong", "bindString" -> s.setObject(args[0] as Int, args[1])
                    "executeInsert" -> {
                        s.executeUpdate()
                        1L
                    }
                    "close" -> s.close()
                    else -> error("Unsupported statement method $name")
                }
            }
        }

        private fun cursor(rows: List<List<Any?>>): Cursor {
            var position = -1
            return proxy { name, args ->
                when (name) {
                    "moveToNext" -> {
                        position++
                        position < rows.size
                    }
                    "moveToFirst" -> {
                        position = 0
                        rows.isNotEmpty()
                    }
                    "getString" -> rows[position][args[0] as Int] as String
                    "getLong" -> (rows[position][args[0] as Int] as Number).toLong()
                    "isNull" -> rows[position][args[0] as Int] == null
                    "getCount" -> rows.size
                    "close" -> Unit
                    else -> error("Unsupported cursor method $name")
                }
            }
        }

        override fun close() = connection.close()
    }

    @Test
    fun tagIdentityPreservesNamespacesWhitespaceAndUnknownPrefixes() {
        assertEquals(DownloadTagKey("new namespace", "name_with  spaces"), DownloadTagKey.of("new namespace", "_L_W_U_Dname_with  spaces"))
        assertEquals(DownloadTagKey("", "_X_name"), DownloadTagKey.parse("_X_name"))
        assertEquals(DownloadTagKey("temp", "_w_name"), DownloadTagKey.parse("temp:_w_name"))
        assertEquals(DownloadTagKey("artist", "a:b"), DownloadTagKey.parse("artist:_Ua:b"))
        assertEquals(" name ", DownloadTagKey.parse("artist: name ").tag)
    }

    @Test
    fun bothJsonFormatsDeduplicateStatusVariantsWithoutChangingRawJson() {
        val raw = """[{"namespace":"artist","tags":["_L_Wname","name","_Uname"]},"temp:_Dtemporary","_X_unknown","future:two  spaces"]"""
        assertEquals(setOf(DownloadTagKey("artist", "name"), DownloadTagKey("temp", "temporary"), DownloadTagKey("", "_X_unknown"), DownloadTagKey("future", "two  spaces")), DownloadTagKey.fromJson(raw))
        assertTrue(raw.contains("_L_Wname"))
        assertTrue(DownloadTagKey.fromJson(null).isEmpty())
        assertTrue(DownloadTagKey.fromJson("[]").isEmpty())
    }

    @Test
    fun malformedJsonIsRejectedBeforeIndexMutation() {
        for (raw in listOf("bad json", "[null]", "[123]", """[{"namespace":5,"tags":[]}]""", """[{"namespace":"artist","tags":[1]}]""")) {
            assertThrows(Exception::class.java) { DownloadTagKey.fromJson(raw) }
        }
    }

    @Test
    fun migrationBackfillsOnlyDownloadsIncludingEveryState(): Unit = Fixture(2).use { f ->
        for (gid in 1L..5L) f.gallery(gid, """["artist:_Wshared","artist:shared","temp:_Ltag","plain"]""", state = (gid - 1).toInt())
        f.gallery(6, """["artist:history only"]""", download = false)
        f.execute("INSERT INTO HISTORY VALUES (6,123)")
        f.transaction { DownloadTagIndex.MIGRATION_2_3.migrate(f.sql) }
        assertEquals(listOf(listOf("", "plain", 5), listOf("artist", "shared", 5), listOf("temp", "tag", 5)), f.stats())
        assertEquals(listOf(listOf(15)), f.rows("SELECT COUNT(*) FROM DOWNLOAD_TAG"))
        assertEquals(listOf(listOf(123)), f.rows("SELECT TIME FROM HISTORY"))
        assertTrue(f.rows("PRAGMA foreign_key_check").isEmpty())
        f.transaction { DownloadTagIndex.MIGRATION_2_3.migrate(f.sql) }
        assertEquals(3, f.stats().size)
        Fixture().use { current ->
            for (table in listOf("DOWNLOAD_TAG", "DOWNLOAD_TAG_STAT")) {
                assertEquals(current.rows("PRAGMA table_info($table)"), f.rows("PRAGMA table_info($table)"))
                assertEquals(current.rows("PRAGMA foreign_key_list($table)"), f.rows("PRAGMA foreign_key_list($table)"))
                assertEquals(current.rows("PRAGMA index_list($table)"), f.rows("PRAGMA index_list($table)"))
            }
        }
    }

    @Test
    fun migrationFailureRollsBackNewTablesAndPreservesMetadata(): Unit = Fixture(2).use { f ->
        f.gallery(1, "invalid")
        assertThrows(Exception::class.java) { f.transaction { DownloadTagIndex.MIGRATION_2_3.migrate(f.sql) } }
        assertTrue(f.rows("SELECT name FROM sqlite_master WHERE name='DOWNLOAD_TAG'").isEmpty())
        assertEquals(listOf(listOf("invalid")), f.rows("SELECT TAGS FROM GALLERY_METADATA"))
    }

    @Test
    fun metadataDeltaAndRepeatedSyncKeepExactCounts(): Unit = Fixture().use { f ->
        f.gallery(1, null)
        f.gallery(2, null)
        f.sync(1, """["artist:shared","temp:old"]""")
        f.sync(2, """["artist:shared"]""")
        repeat(4) {
            f.execute("UPDATE DOWNLOADS SET STATE=?,LABEL='group',FINISHED_PAGES=? WHERE GID=1", listOf(it, it))
            f.sync(1, """["artist:_Wshared","temp:old","artist:shared"]""")
        }
        assertEquals(listOf(listOf("artist", "shared", 2), listOf("temp", "old", 1)), f.stats())
        f.sync(1, """[{"namespace":"artist","tags":["shared","new"]}]""")
        assertEquals(listOf(listOf("artist", "new", 1), listOf("artist", "shared", 2)), f.stats())
        f.sync(2, null)
        assertEquals(listOf(listOf("artist", "new", 1), listOf("artist", "shared", 1)), f.stats())
    }

    @Test
    fun failuresRollbackMetadataRelationsAndCountsTogether(): Unit = Fixture().use { f ->
        f.gallery(1, null)
        f.sync(1, """["artist:old"]""")
        f.execute("CREATE TRIGGER fail_stat BEFORE UPDATE ON DOWNLOAD_TAG_STAT WHEN NEW.TAG='new' BEGIN SELECT RAISE(ABORT,'injected'); END")
        assertThrows(Exception::class.java) { f.sync(1, """["artist:new"]""") }
        assertEquals(listOf(listOf("artist", "old", 1)), f.stats())
        assertEquals(listOf(listOf("old")), f.rows("SELECT TAG FROM DOWNLOAD_TAG"))
        assertEquals(listOf(listOf("""["artist:old"]""")), f.rows("SELECT TAGS FROM GALLERY_METADATA"))
        assertThrows(Exception::class.java) { f.sync(1, "invalid") }
        assertEquals(listOf(listOf("artist", "old", 1)), f.stats())
    }

    @Test
    fun deleteAndReaddUseRetainedJsonAndDoNotRestoreMissingTags(): Unit = Fixture().use { f ->
        f.gallery(1, null)
        f.sync(1, """["artist:saved"]""")
        repeat(2) {
            f.transaction {
                f.execute(DownloadDeletionSql.DECREMENT_TAGS, listOf(1))
                f.execute(DownloadDeletionSql.REMOVE_EMPTY_TAGS, listOf(1))
                f.execute(DownloadDeletionSql.REMOVE_DOWNLOAD, listOf(1))
            }
        }
        assertTrue(f.stats().isEmpty())
        assertTrue(f.rows("SELECT * FROM DOWNLOAD_TAG").isEmpty())
        f.execute("INSERT INTO DOWNLOADS (GID,STATE,REMAINING_PAGES,TIME,FINISHED_PAGES,ATTEMPTED_PAGES,TOTAL_PAGES) VALUES (1,0,0,0,0,0,0)")
        f.transaction { DownloadTagIndex.sync(f.sql, 1, f.rows("SELECT TAGS FROM GALLERY_METADATA")[0][0] as String) }
        assertEquals(listOf(listOf("artist", "saved", 1)), f.stats())
        f.sync(1, null)
        f.transaction { DownloadTagIndex.rebuild(f.sql) }
        assertTrue(f.stats().isEmpty())
    }

    @Test
    fun rebuildRepairsMissingExtraRelationsAndWrongCounts(): Unit = Fixture().use { f ->
        f.gallery(1, """["artist:a"]""")
        f.gallery(2, """["artist:a","future:b"]""")
        f.transaction { DownloadTagIndex.rebuild(f.sql) }
        f.execute("DELETE FROM DOWNLOAD_TAG WHERE GID=1")
        f.execute("INSERT INTO DOWNLOAD_TAG VALUES (2,'wrong','extra')")
        f.execute("UPDATE DOWNLOAD_TAG_STAT SET TAG_COUNT=999")
        f.transaction { DownloadTagIndex.rebuild(f.sql) }
        assertEquals(listOf(listOf("artist", "a", 2), listOf("future", "b", 1)), f.stats())
        assertEquals(listOf(listOf(3)), f.rows("SELECT COUNT(*) FROM DOWNLOAD_TAG"))
    }

    @Test
    fun backupRestoreRebuildsFromMergedTruthInsteadOfTrustingBackupCounts(): Unit = Fixture(2).use { source ->
        source.gallery(1, """["artist:backup"]""")
        source.gallery(2, """["artist:second"]""")
        source.transaction { DownloadTagIndex.MIGRATION_2_3.migrate(source.sql) }
        source.execute("UPDATE DOWNLOAD_TAG_STAT SET TAG_COUNT=500")
        Fixture().use { target ->
            target.gallery(1, """["artist:existing"]""")
            target.transaction {
                source.rows("SELECT GID,TAGS FROM GALLERY_METADATA WHERE GID=2").forEach { target.gallery((it[0] as Number).toLong(), it[1] as String) }
                DownloadTagIndex.rebuild(target.sql)
            }
            assertEquals(listOf(listOf("artist", "existing", 1), listOf("artist", "second", 1)), target.stats())
        }
    }

    @Test
    fun combinedBooleanFiltersUseCanonicalTagsAndGlobalCountsStayUnchanged(): Unit = Fixture().use { f ->
        f.gallery(1, """["artist:a","temp:b"]""")
        f.gallery(2, """["artist:a","temp:b","unknown:excluded"]""")
        f.gallery(3, """["artist:a","temp:c"]""", state = 4)
        f.transaction { DownloadTagIndex.rebuild(f.sql) }
        val before = f.stats()
        val filter = DownloadFilter(all = listOf(DownloadTagKey.parse("artist:_La")), any = listOf(DownloadTagKey.parse("temp:b"), DownloadTagKey.parse("temp:c")), excluded = listOf(DownloadTagKey.parse("unknown:excluded")), title = "same", category = 4, language = "EN", minimumRating = 4f, maximumRating = 5f, state = 0)
        assertEquals(listOf(listOf(1)), f.rows(DownloadFilterQuery(filter).gids()))
        assertEquals(before, f.stats())
        assertTrue(f.rows(DownloadFilterQuery(filter.copy(title = "%' OR 1=1 --")).gids()).isEmpty())
        assertTrue(f.rows(DownloadFilterQuery(filter.copy(all = listOf(DownloadTagKey("artist", "a' OR 1=1 --")))).gids()).isEmpty())
    }

    @Test
    fun allMatchesAreReturnedInStableOrderAcrossGroups(): Unit = Fixture().use { f ->
        for (gid in 1L..205L) f.gallery(gid, null)
        f.execute("UPDATE DOWNLOADS SET LABEL='group' WHERE GID>100")
        for (sort in DownloadFilterSort.entries) {
            val query = DownloadFilterQuery(DownloadFilter(sort = sort))
            val matches = f.rows(query.gids())
            assertEquals(205, matches.size)
            assertEquals(205, matches.distinct().size)
            assertEquals(matches, f.rows(query.gids()))
        }
        assertEquals(100, f.rows(DownloadFilterQuery(DownloadFilter(), true, null).gids()).size)
        assertEquals(105, f.rows(DownloadFilterQuery(DownloadFilter(), true, "group").gids()).size)
        assertTrue(f.rows(DownloadFilterQuery(DownloadFilter(), true, "missing").gids()).isEmpty())
    }

    @Test
    fun unknownFieldsDoNotMatchConstrainedRatingOrLanguage(): Unit = Fixture().use { f ->
        f.gallery(1, null)
        f.execute("UPDATE GALLERY_METADATA SET RATING=NULL,SIMPLE_LANGUAGE=NULL,CATEGORY=NULL")
        assertTrue(f.rows(DownloadFilterQuery(DownloadFilter(minimumRating = 0f)).gids()).isEmpty())
        assertTrue(f.rows(DownloadFilterQuery(DownloadFilter(language = "EN")).gids()).isEmpty())
        assertEquals(listOf(listOf(1)), f.rows(DownloadFilterQuery(DownloadFilter(language = "")).gids()))
        f.execute("UPDATE GALLERY_METADATA SET LANGUAGE='Japanese'")
        assertEquals(listOf(listOf(1)), f.rows(DownloadFilterQuery(DownloadFilter(language = "Japanese")).gids()))
        assertTrue(f.rows(DownloadFilterQuery(DownloadFilter(language = "")).gids()).isEmpty())
        f.execute("UPDATE GALLERY_METADATA SET SIMPLE_LANGUAGE='JA'")
        assertEquals(listOf(listOf(1)), f.rows(DownloadFilterQuery(DownloadFilter(language = "JA")).gids()))
        assertThrows(IllegalArgumentException::class.java) { DownloadFilterQuery(DownloadFilter(minimumRating = Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { DownloadFilterQuery(DownloadFilter(minimumRating = 4f, maximumRating = 3f)) }
    }

    @Test
    fun indexConstraintsRejectOrphansAndDuplicates(): Unit = Fixture().use { f ->
        assertThrows(Exception::class.java) { f.execute("INSERT INTO DOWNLOAD_TAG VALUES (1,'artist','a')") }
        f.gallery(1, null)
        f.sync(1, """["artist:a"]""")
        assertThrows(Exception::class.java) { f.execute("INSERT INTO DOWNLOAD_TAG VALUES (1,'artist','a')") }
        assertThrows(Exception::class.java) { f.execute("INSERT INTO DOWNLOAD_TAG_STAT (NAMESPACE,TAG,TAG_COUNT) VALUES ('artist','a',1)") }
        val plan = f.rows("EXPLAIN QUERY PLAN SELECT GID FROM DOWNLOAD_TAG WHERE NAMESPACE='artist' AND TAG='a'").toString()
        assertTrue(plan.contains("index_DOWNLOAD_TAG_NAMESPACE_TAG_GID"))
        assertFalse(f.sql.inTransaction())
        assertThrows(IllegalStateException::class.java) { DownloadTagIndex.rebuild(f.sql) }
    }

    @Test
    fun restartPausesInterruptedDatabaseStatesWithoutChangingTagCounts(): Unit = Fixture().use { f ->
        for (gid in 1L..5L) f.gallery(gid, """["artist:shared"]""", state = (gid - 1).toInt())
        f.transaction {
            DownloadTagIndex.rebuild(f.sql)
            f.execute(RESET_INTERRUPTED_DOWNLOADS)
        }
        assertEquals(listOf(listOf(0), listOf(0), listOf(0), listOf(3), listOf(4)), f.rows("SELECT STATE FROM DOWNLOADS ORDER BY GID"))
        assertEquals(listOf(listOf("artist", "shared", 5)), f.stats())
        assertEquals(3, f.rows(DownloadFilterQuery(DownloadFilter(state = 0)).gids()).size)
    }

    @Test
    fun capacityAndQueryPlansAtTwentyThousandGalleries() {
        Fixture().use { f ->
            val report = JSONObject().put("galleries", 20000).put("tagsPerGallery", 40).put("sqlite", f.rows("SELECT sqlite_version()")[0][0])
            fun measure(name: String, action: () -> Unit) {
                val start = System.nanoTime()
                action()
                report.put(name, (System.nanoTime() - start) / 1_000_000.0)
            }
            f.transaction {
                for (gid in 1L..20000L) f.gallery(gid, JSONArray((0 until 40).map { "ns${it % 5}:tag${(gid + it) % 1000}" }).toString(), state = (gid % 5).toInt())
            }
            measure("rebuildMs") { f.transaction { DownloadTagIndex.rebuild(f.sql) } }
            assertEquals(listOf(listOf(800000)), f.rows("SELECT COUNT(*) FROM DOWNLOAD_TAG"))
            val filters = mapOf("all" to DownloadFilter(), "and" to DownloadFilter(all = listOf(DownloadTagKey("ns0", "tag5"), DownloadTagKey("ns1", "tag6"))), "orNot" to DownloadFilter(any = listOf(DownloadTagKey("ns0", "tag5"), DownloadTagKey("ns1", "tag6")), excluded = listOf(DownloadTagKey("ns2", "tag7"))), "fields" to DownloadFilter(category = 4, language = "EN", minimumRating = 4f, state = 4, sort = DownloadFilterSort.RATING))
            filters.forEach { (name, filter) ->
                val query = DownloadFilterQuery(filter)
                measure("${name}MatchesMs") {
                    f.rows(query.gids())
                }
                report.put("${name}Plan", f.rows("EXPLAIN QUERY PLAN " + query.gids().sql, query.gids().args).toString())
            }
            measure("tagPanelMs") { f.rows("SELECT NAMESPACE,TAG,TAG_COUNT FROM DOWNLOAD_TAG_STAT ORDER BY TAG_COUNT DESC,NAMESPACE,TAG LIMIT 51") }
            report.put("databaseBytes", (f.rows("PRAGMA page_count")[0][0] as Number).toLong() * (f.rows("PRAGMA page_size")[0][0] as Number).toLong())
            val output = File("build/reports/f14-capacity.json")
            output.parentFile!!.mkdirs()
            output.writeText(report.toString(2) + "\n")
        }
    }

    companion object {
        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> invoke(method.name, args.orEmpty()) } as T
    }
}
