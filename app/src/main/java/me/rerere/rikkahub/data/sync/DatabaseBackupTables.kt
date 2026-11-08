package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.datastore.BackupResource
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

/**
 * 按备份项对数据库分表导出/恢复。
 *
 * 导出：`VACUUM INTO` 得到当前库快照，再 `ATTACH` 这份快照，把所选表复制进一个新的独立库。
 * 恢复：打开备份库，把对应的表按覆盖或合并语义写回当前库，整个过程在一个事务里完成。
 */
internal object DatabaseBackupTables {
    private val CONVERSATION_TABLES = listOf(
        // 没有 @Entity(tableName = ...) 的实体用类名作表名
        "ConversationEntity",
        "message_node",
        "conversation_folder",
        "favorites",
        "memory_file",
        "memory_consolidation",
        "usage_record",
        "translation_history",
        "media_creation_session",
        "media_creation_node",
        "media_creation_record",
        "GenMediaEntity",
        // 附件索引；文件本体在 files/upload 里，归 FILES 项
        "managed_files",
    )

    private val WORKSPACE_TABLES = listOf("workspaces")

    fun tablesOf(resource: BackupResource): List<String> = when (resource) {
        BackupResource.CONVERSATIONS -> CONVERSATION_TABLES
        BackupResource.WORKSPACES -> WORKSPACE_TABLES
        else -> emptyList()
    }

    fun hasTables(resource: BackupResource): Boolean = tablesOf(resource).isNotEmpty()

    /** 备份覆盖的全部表名，用于与 Room schema 校对。 */
    fun allTables(): Set<String> =
        BackupResource.entries.flatMapTo(mutableSetOf()) { tablesOf(it) }

    /**
     * 把 [resources] 涉及的表导出到 [destination]；没有涉及任何表时返回 false。
     *
     * 直接导出整库快照，恢复时再按资源挑表。不在这里用 ATTACH 拼一份只含所选表的库：
     * requery 对 ATTACH / VACUUM 的路径处理不可靠，新建的目标库还会报 CANTOPEN。
     */
    fun export(
        context: Context,
        live: SupportSQLiteDatabase,
        resources: Collection<BackupResource>,
        destination: File,
    ): Boolean {
        val tables = resources.flatMap { tablesOf(it) }.distinct()
        if (tables.isEmpty()) return false
        check(!destination.exists()) { "Backup table snapshot already exists" }

        DatabaseBackup.createSnapshot(live, destination)
        try {
            // 校验能打开、完整性正常，并去掉 sidecar，产物是单个自包含文件
            DatabaseBackup.normalize(context, destination)
            val available = withOpen(context, destination) { tableNames(it) }
            val missing = tables.filterNot { it in available }
            check(missing.isEmpty()) { "Backup snapshot is missing tables: ${missing.joinToString()}" }
            return true
        } catch (e: Throwable) {
            destination.delete()
            File(destination.path + "-wal").delete()
            File(destination.path + "-shm").delete()
            throw e
        }
    }

    /** 备份库里属于 [resource] 且确实存在的表。 */
    fun existingTables(context: Context, database: File, resource: BackupResource): List<String> =
        existingTables(context, database, tablesOf(resource))

    /** [candidates] 里在 [database] 中确实存在的表。 */
    fun existingTables(context: Context, database: File, candidates: Collection<String>): List<String> =
        withOpen(context, database) { db -> candidates.filter { it in tableNames(db) } }

    private fun tableNames(db: SupportSQLiteDatabase, schema: String = "main"): Set<String> =
        db.query("SELECT name FROM $schema.sqlite_master WHERE type = 'table'")
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** 用恢复计划把备份表写回当前库，整体在一个事务里完成。 */
    fun import(context: Context, plan: RestoreDatabasePlan, source: File): Int {
        check(source.isFile) { "Staged backup database is missing" }
        val targets = (plan.overwrite + plan.merge).distinct()
        if (targets.isEmpty()) return 0
        return withOpenLive(context) { live ->
            require(targets.none { it !in liveTableNames(live) }) { "Backup contains unsupported tables" }
            live.beginTransaction()
            try {
                live.execSQL("ATTACH DATABASE ${DatabaseBackup.sqlLiteral(source.absolutePath)} AS backup")
                try {
                    var imported = 0
                    for (table in plan.overwrite) {
                        live.execSQL("DELETE FROM main.\"$table\"")
                        imported += copyRows(live, table, merge = false)
                    }
                    for (table in plan.merge) {
                        imported += copyRows(live, table, merge = true)
                    }
                    imported
                } finally {
                    live.execSQL("DETACH DATABASE backup")
                }
            } finally {
                live.endTransaction()
            }
        }
    }

    /**
     * 把备份表的数据写回当前库。
     *
     * 合并用 `ON CONFLICT ... DO UPDATE` 而不是 `INSERT OR REPLACE`：后者会先删行，触发外键的
     * ON DELETE CASCADE，把备份里没有的子表数据一起删掉。
     */
    private fun copyRows(live: SupportSQLiteDatabase, table: String, merge: Boolean): Int {
        val columns = live.query("PRAGMA main.table_info(\"$table\")").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(TableColumn(name = cursor.getString(1), primaryKey = cursor.getInt(5) > 0))
                }
            }
        }
        val names = columns.map { it.name }
        val quoted = names.joinToString(", ") { "\"$it\"" }
        val source = "backup.\"$table\""
        val conflict = if (merge && columns.any { it.primaryKey }) {
            val keys = columns.filter { it.primaryKey }.joinToString(", ") { "\"${it.name}\"" }
            val updates = columns.filterNot { it.primaryKey }
                .joinToString(", ") { "\"${it.name}\" = excluded.\"${it.name}\"" }
            if (updates.isEmpty()) " ON CONFLICT($keys) DO NOTHING" else " ON CONFLICT($keys) DO UPDATE SET $updates"
        } else {
            ""
        }
        // WHERE true 消除 `FROM source ON CONFLICT` 的歧义（否则 ON 会被当成 join 的 ON）
        live.execSQL("INSERT INTO main.\"$table\" ($quoted) SELECT $quoted FROM $source WHERE true$conflict")
        return live.query("SELECT count(*) FROM $source").use {
            it.moveToFirst()
            it.getInt(0)
        }
    }

    private class TableColumn(val name: String, val primaryKey: Boolean)

    private fun liveTableNames(live: SupportSQLiteDatabase): Set<String> =
        live.query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun <T> withOpenLive(context: Context, block: (SupportSQLiteDatabase) -> T): T {
        val file = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
        check(file.isFile) { "Current database is missing" }
        val database = SQLiteDatabase.openDatabase(
            SQLiteConfiguration.configure(
                context,
                SQLiteDatabaseConfiguration(file.absolutePath, SQLiteDatabase.OPEN_READWRITE),
            ),
            null,
        ) { error("Current database is corrupt") }
        try {
            DatabaseBackup.checkpoint(database)
            return block(database)
        } finally {
            database.close()
        }
    }

    private fun <T> withOpen(context: Context, file: File, block: (SupportSQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(
            SQLiteConfiguration.configure(
                context,
                SQLiteDatabaseConfiguration(file.absolutePath, SQLiteDatabase.OPEN_READWRITE),
            ),
            null,
        ) { error("Backup database is corrupt") }.use(block)
}
