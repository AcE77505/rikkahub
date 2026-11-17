package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_34_35"

/**
 * message_node 的索引从 (conversation_id) 换成 (conversation_id, node_index)。
 *
 * 上游在 3.3.0 把这条索引放在 32 -> 33 里，但 fork 的 32 -> 33 已被工作区 SSH 字段占用，
 * 这里单独作为 34 -> 35 引入：只建索引、删索引，不动表数据。
 * 复合索引以 conversation_id 开头，外键级联删除和按会话的查询照样能用，旧索引不再需要。
 * 索引不是唯一索引，旧数据里同一会话有重复的 node_index 也不影响迁移和读取。
 */
val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 34 to 35 (message_node index)")
        DatabaseMigrationTracker.onMigrationStart(34, 35)
        try {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                    "ON `message_node` (`conversation_id`, `node_index`)"
            )
            db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")
            Log.i(TAG, "migrate: migrate from 34 to 35 success")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
