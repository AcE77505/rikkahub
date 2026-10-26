package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.SshTarget
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
    // 本地工作区还是 SSH 远程工作区
    @ColumnInfo("type", defaultValue = "LOCAL")
    val type: String = TYPE_LOCAL,
    @ColumnInfo("ssh_host")
    val sshHost: String? = null,
    @ColumnInfo("ssh_port", defaultValue = "8022")
    val sshPort: Int = SshTarget.DEFAULT_PORT,
    @ColumnInfo("ssh_username")
    val sshUsername: String? = null,
    // 远程工作目录；为空时用登录用户的 home
    @ColumnInfo("ssh_root")
    val sshRoot: String? = null,
) {
    val isSsh: Boolean get() = type == TYPE_SSH

    /** 组装 SSH 连接目标；非 SSH 工作区或未填全时返回 null */
    fun sshTarget(): SshTarget? {
        if (!isSsh) return null
        val host = sshHost?.trim().orEmpty()
        val username = sshUsername?.trim().orEmpty()
        if (host.isEmpty() || username.isEmpty()) return null
        return SshTarget(
            host = host,
            port = sshPort,
            username = username,
            remoteRoot = sshRoot?.trim()?.takeIf { it.isNotEmpty() } ?: SshTarget.DEFAULT_REMOTE_ROOT,
        )
    }

    /**
     * 生成「复制到远端终端」的命令时只需要主机和端口。
     *
     * setup 命令本身会回显 `whoami`，用户正是靠它得到用户名的，所以这里不能要求用户名已填。
     */
    fun sshCommandTarget(): SshTarget? {
        if (!isSsh) return null
        val host = sshHost?.trim().orEmpty()
        if (host.isEmpty()) return null
        return SshTarget(
            host = host,
            port = sshPort,
            username = sshUsername?.trim().orEmpty(),
            remoteRoot = sshRoot?.trim()?.takeIf { it.isNotEmpty() } ?: SshTarget.DEFAULT_REMOTE_ROOT,
        )
    }

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

        /** 本地工作区：文件与命令都跑在本 app 的沙箱里 */
        const val TYPE_LOCAL = "LOCAL"

        /** SSH 工作区：文件与命令都通过 ssh 在远端执行 */
        const val TYPE_SSH = "SSH"
    }
}
