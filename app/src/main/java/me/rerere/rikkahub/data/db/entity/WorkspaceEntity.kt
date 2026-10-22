package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.Workspace

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    // 已废弃：rootfs 时代的状态位，只为兼容旧库保留，代码不再读写
    @ColumnInfo("shell_status")
    val shellStatus: String = LEGACY_SHELL_STATUS_DISABLED,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 已废弃：审批改为全局 Settings.toolApprovals，此处只为兼容旧库保留
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    // 已废弃：命令直接跑在 Android 原生环境，不再需要兼容模式
    @ColumnInfo("shell_compatibility_mode", defaultValue = "0")
    val shellCompatibilityMode: Boolean = false,
    // 是否把此工作区写进请求提示词；同一时刻只有一个工作区被声明
    @ColumnInfo("declared", defaultValue = "0")
    val declared: Boolean = false,
    // 工作区目录的绝对路径，为空时回落到 files/workspaces/<root>/files
    @ColumnInfo("directory")
    val directory: String? = null,
    // 是否启用 Linux 工具链（bash / openssl / curl / ssh，来源为 bin.mt.termex）
    @ColumnInfo("toolchain_enabled", defaultValue = "0")
    val toolchainEnabled: Boolean = false,
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )

    companion object {
        const val LEGACY_SHELL_STATUS_DISABLED = "DISABLED"
    }
}
