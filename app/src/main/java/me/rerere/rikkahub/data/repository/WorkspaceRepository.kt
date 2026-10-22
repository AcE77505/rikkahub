package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.DEFAULT_SHELL_OVERTIME_SECONDS
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.ToolchainEnvironment
import me.rerere.workspace.ToolchainManager
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

/**
 * 工作区数据入口。
 *
 * 目录与命令全部走 Android 原生环境：文件是普通 `java.io.File`，命令是 `/system/bin/sh`；
 * 启用「Linux 工具链」时由 [ToolchainManager] 提供 bash 与 lib，HOME / TMPDIR 每工作区独立。
 */
class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val toolchainManager: ToolchainManager,
    private val settingsStore: SettingsStore,
    /** 没有声明工作区时，文件工具的根目录 */
    private val sandboxRoot: File,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    /** 当前被声明给 AI 的工作区（最多一个），关闭声明时为空 */
    fun declaredFlow(): Flow<WorkspaceEntity?> = dao.declaredFlow()

    suspend fun getDeclared(): WorkspaceEntity? = dao.getDeclared()

    /**
     * 沙箱目录：没有声明工作区时，文件工具以它为根。
     *
     * 放在 app 私有目录下，不需要存储权限；目录随首次使用创建。
     */
    suspend fun sandboxDir(): File = withContext(Dispatchers.IO) { sandboxRoot.apply { mkdirs() } }

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.targetDir(workspace.root, workspace.directory)
            if (dir.isDirectory) continue
            // 目录缺失时不删除记录（例如恢复备份后工作区文件未随数据库一起恢复），
            // 只记一条日志，保留记录与用户数据，避免误删
            Log.w(
                TAG,
                "Workspace directory missing: id=${workspace.id}, root=${workspace.root}, dir=${dir.absolutePath}"
            )
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    /** 按目录名查工作区：终端会话只拿得到 root，拿不到主键 */
    suspend fun getByRoot(root: String): WorkspaceEntity? = dao.getByRoot(root)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "工作区名称已存在：$finalName"
        }
        // 目录名直接用工作区名，方便在文件管理器/终端里辨认；不再用随机 UUID
        val root = WorkspaceManager.toRootName(finalName)
            ?: error("名称无法用作目录名：$finalName")
        withContext(Dispatchers.IO) {
            require(!manager.workspaceDir(root).exists()) {
                "同名目录已存在：$root"
            }
            manager.ensureWorkspace(root)
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = root,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    /** 把此工作区声明给 AI；DAO 事务保证同一时刻只有一个工作区被声明 */
    suspend fun setDeclared(id: String, declared: Boolean) {
        val now = System.currentTimeMillis()
        if (declared) {
            dao.setDeclared(id, now)
        } else {
            dao.clearDeclared(now)
        }
    }

    /**
     * 设置工作区目录（绝对路径）。
     *
     * 传 null 或空串表示回落到默认目录。非法路径直接抛 [IllegalArgumentException]，
     * 由调用方把消息展示给用户。
     */
    suspend fun setDirectory(id: String, directory: String?) {
        val trimmed = directory?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null) {
            require(trimmed.startsWith("/")) { "目录必须是绝对路径" }
            val dir = File(trimmed)
            // 允许填还不存在的目录：保存时一并创建，不然「指定一个新目录」必须先手动建好才能填
            if (!dir.isDirectory) {
                val created = withContext(Dispatchers.IO) { dir.mkdirs() || dir.isDirectory }
                require(created) { "无法创建目录：$trimmed" }
            }
            require(dir.canRead()) { "目录不可读：$trimmed" }
        }
        dao.setDirectory(id, trimmed, System.currentTimeMillis())
    }

    suspend fun setToolchainEnabled(id: String, enabled: Boolean) {
        if (enabled) {
            require(toolchainManager.resolve() != null) {
                "未检测到可用的终端扩展包（${ToolchainManager.TERMEX_PACKAGE}）"
            }
        }
        dao.setToolchainEnabled(id, enabled, System.currentTimeMillis())
    }

    /** termex 扩展包的检测结果，供设置页决定开关是否可用 */
    fun termexStatus() = toolchainManager.detect()

    /** 工作区实际目录（[WorkspaceEntity.directory] 覆盖或默认 files 目录） */
    suspend fun workingDirectory(id: String): File {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withContext(Dispatchers.IO) { manager.targetDir(workspace.root, workspace.directory) }
    }

    suspend fun homeDir(id: String): File {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withContext(Dispatchers.IO) { manager.homeDir(workspace.root).apply { mkdirs() } }
    }

    suspend fun listFiles(id: String, path: String): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, workspace.directory)
    }

    suspend fun readText(id: String, path: String): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path, workspace.directory)
    }

    suspend fun readBytes(id: String, path: String): ByteArray = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val file = manager.resolveFile(workspace.root, path, workspace.directory)
        require(file.length() <= MAX_READ_BYTES) {
            "File is too large to read: $path (${file.length() / 1024 / 1024}MB, max ${MAX_READ_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
        }
        file.readBytes()
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, workspace.directory, overwrite)
    }

    suspend fun importFile(
        id: String,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, fileName, inputStream, workspace.directory)
    }

    suspend fun fileSize(id: String, path: String): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, workspace.directory)
    }

    suspend fun resolveFile(id: String, path: String): File = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.resolveFile(workspace.root, path, workspace.directory)
    }

    suspend fun exportFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, outputStream, workspace.directory)
    }

    suspend fun deleteFile(id: String, path: String, recursive: Boolean): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, workspace.directory)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite, workspace.directory)
    }

    /** 把工作区的工具链配置解析成可注入的环境；未启用或不可用时返回 null（回退 toybox） */
    suspend fun toolchainEnvironment(id: String): ToolchainEnvironment? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        if (!workspace.toolchainEnabled) return@withContext null
        runCatching { toolchainManager.resolve() }
            .onFailure { Log.w(TAG, "toolchain unavailable, falling back to toybox", it) }
            .getOrNull()
    }

    /**
     * 执行命令。
     *
     * [overtimeMillis] 是软阈值：超过它默认只通过 [onOvertime] 通知，命令继续执行；
     * 只有 [autoStopOnOvertime] 为真时才停止整个进程组。[overtimeMillis] <= 0 表示无限制。
     */
    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        overtimeMillis: Long = DEFAULT_SHELL_OVERTIME_SECONDS * 1_000L,
        autoStopOnOvertime: Boolean = false,
        stdin: ByteArray? = null,
        overflowFileName: String? = null,
        onOvertime: (() -> Unit)? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val toolchain = toolchainEnvironment(id)
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的等待并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(
                root = workspace.root,
                command = command,
                cwd = cwd,
                directory = workspace.directory,
                overtimeMillis = overtimeMillis,
                autoStopOnOvertime = autoStopOnOvertime,
                stdin = stdin,
                toolchain = toolchain,
                overflowFileName = overflowFileName,
                onOvertime = onOvertime,
            )
        }
    }

    /** 按当前设置执行命令：阈值与「自动停止」都取自 [SettingsStore] */
    suspend fun executeCommandWithSettings(
        id: String,
        command: String,
        cwd: String = "",
        timeoutSecondsOverride: Long? = null,
        stdin: ByteArray? = null,
        overflowFileName: String? = null,
        onOvertime: (() -> Unit)? = null,
    ): WorkspaceCommandResult {
        val settings = settingsStore.awaitLoaded()
        val overtimeSeconds = timeoutSecondsOverride
            ?: settings.shellOvertimeSeconds?.toLong()
            ?: UNLIMITED_OVERTIME
        return executeCommand(
            id = id,
            command = command,
            cwd = cwd,
            overtimeMillis = if (overtimeSeconds <= 0) UNLIMITED_OVERTIME else overtimeSeconds * 1_000L,
            autoStopOnOvertime = settings.shellAutoStopOnOvertime,
            stdin = stdin,
            overflowFileName = overflowFileName,
            onOvertime = onOvertime,
        )
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        return true
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_READ_BYTES = 8L * 1024 * 1024

        /** 阈值「无限制」传给 runner 的取值：不提示、不自动停止 */
        const val UNLIMITED_OVERTIME = 0L
    }
}
