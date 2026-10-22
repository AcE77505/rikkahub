package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_31_32"

/**
 * 工作区改为「Android 原生环境 + 可选 termex 工具链」形态。
 *
 * - 新增 `declared`（是否写进请求提示词）、`directory`（工作区目录，可空）、`toolchain_enabled`
 * - 旧的 `shell_status` 里 `INSTALLING` 归一为 `DISABLED`：rootfs 安装流程已删除，这个状态不再可能
 * - 所有工作区默认不声明
 * - `files/workspaces/<root>/linux/` 旧 rootfs 数据保留在磁盘上不删，只是代码不再读取
 */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 31 to 32 (workspace native environment)")
        DatabaseMigrationTracker.onMigrationStart(31, 32)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `declared` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `directory` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `toolchain_enabled` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE `workspaces` SET `shell_status` = 'DISABLED' WHERE `shell_status` = 'INSTALLING'"
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 31 to 32 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
