package me.rerere.workspace

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
)

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    /** 输出超过上限时，溢出部分写入的文件（TMPDIR 下），可为空 */
    val fullOutputPath: String? = null,
)
