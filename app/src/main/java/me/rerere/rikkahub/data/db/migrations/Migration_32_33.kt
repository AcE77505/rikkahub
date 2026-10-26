package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_32_33"

/**
 * 工作区支持 SSH 远程类型。
 *
 * - 新增 `type`（LOCAL / SSH），历史工作区一律是 LOCAL
 * - 新增 `ssh_host` / `ssh_port` / `ssh_username` / `ssh_root`
 *
 * 这里只加列，不动已有数据：旧工作区的 `directory`、`toolchain_enabled` 等保持原样。
 * SSH 凭据不是每行一份：所有 SSH 工作区共用 app 自己生成的一对 ed25519 密钥，
 * 存在 `files/ssh/` 下，因此不需要存进数据库。
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 32 to 33 (ssh workspace)")
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'LOCAL'")
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `ssh_host` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `ssh_port` INTEGER NOT NULL DEFAULT 8022")
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `ssh_username` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `ssh_root` TEXT DEFAULT NULL")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 32 to 33 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
