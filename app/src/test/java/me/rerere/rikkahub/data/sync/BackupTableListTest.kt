package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 备份的表名清单必须覆盖 Room schema 里的每一张表。
 *
 * 曾经把 `ConversationEntity` 写成 `conversation`、`conversation_folder` 写成 `folders`，
 * 导出时报 "Backup snapshot is missing tables"，所以这里用导出的 schema 做校对。
 */
class BackupTableListTest {
    @Test
    fun `table list covers the Room schema`() {
        val dir = File("schemas/me.rerere.rikkahub.data.db.AppDatabase")
        assumeTrue("Room schema is unavailable", dir.isDirectory)
        val latest = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.removeSuffix(".json").toIntOrNull() != null }
            .maxBy { it.name.removeSuffix(".json").toInt() }

        val entities = JsonInstant.parseToJsonElement(latest.readText())
            .jsonObject.getValue("database")
            .jsonObject.getValue("entities")
            .jsonArray
        val schemaTables = entities
            .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
            .toSet()

        assertEquals(schemaTables, DatabaseBackupTables.allTables())
    }
}
