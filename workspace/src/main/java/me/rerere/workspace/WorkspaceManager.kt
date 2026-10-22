package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 工作区文件与命令的门面。
 *
 * 磁盘布局（[baseDir] 通常是 `files/workspaces/`）：
 * ```
 * <root>/
 *   files/     # 默认工作区目录（未指定 directory 时使用）
 *   home/      # HOME，工作区私有
 *   tmp/       # TMPDIR，工作区私有
 *   linux/     # 旧版 rootfs，已废弃，代码不再读取（保留用户数据不删）
 * ```
 */
class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
) {
    private val fileSystem = WorkspaceFileSystem(config)

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        homeDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    /** 默认工作区目录（未指定自定义目录时） */
    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun homeDir(root: String): File = File(workspaceDir(root), HOME_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    /**
     * 工作区实际目录：[directory] 非空且是绝对路径时用它，否则回落到默认 [filesDir]。
     *
     * 这里不做存在性校验，调用方（Repository / UI）负责校验提示；
     * 命令与文件读写前会 [File.mkdirs] 兜底。
     */
    fun targetDir(root: String, directory: String? = null): File {
        val custom = directory?.trim().orEmpty()
        return if (custom.startsWith("/")) File(custom) else filesDir(root)
    }

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(root: String, path: String = "", directory: String? = null): List<WorkspaceFileEntry> =
        fileSystem.list(targetDir(root, directory), path)

    fun readText(
        root: String,
        path: String,
        directory: String? = null,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(targetDir(root, directory), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        directory: String? = null,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry =
        fileSystem.writeText(targetDir(root, directory), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
        directory: String? = null,
    ): WorkspaceFileEntry {
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(targetDir(root, directory), targetPath, inputStream)
    }

    fun fileSize(root: String, path: String, directory: String? = null): Long {
        val file = fileSystem.resolve(targetDir(root, directory), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun resolveFile(root: String, path: String, directory: String? = null): File {
        val file = fileSystem.resolve(targetDir(root, directory), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file
    }

    fun exportFile(
        root: String,
        path: String,
        outputStream: OutputStream,
        directory: String? = null,
    ) {
        val file = fileSystem.resolve(targetDir(root, directory), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        directory: String? = null,
    ): Boolean = fileSystem.delete(targetDir(root, directory), path, recursive)

    fun moveFile(
        root: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
        directory: String? = null,
    ): WorkspaceFileEntry = fileSystem.move(targetDir(root, directory), source, target, overwrite)

    fun glob(
        root: String,
        pattern: String,
        path: String = "",
        directory: String? = null,
    ): List<WorkspaceFileEntry> = fileSystem.glob(targetDir(root, directory), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
        directory: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(targetDir(root, directory), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        directory: String? = null,
        overtimeMillis: Long = WorkspaceShellContext.DEFAULT_OVERTIME_MILLIS,
        autoStopOnOvertime: Boolean = false,
        stdin: ByteArray? = null,
        toolchain: ToolchainEnvironment? = null,
        overflowFileName: String? = null,
        onOvertime: (() -> Unit)? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workspaceDir = targetDir(root, directory).also { it.mkdirs() }
        val workingDir = fileSystem.resolve(workspaceDir, cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        val tempDir = tempDir(root).also { it.mkdirs() }
        val wrappedCommand = if (toolchain?.shellPath != null) {
            // 有 bash 时用它执行，保留命令文本原样（位置参数传入，避免二次转义）
            "${toolchain.shellPath.shellQuote()} -lc ${command.shellQuote()}"
        } else {
            command
        }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = wrappedCommand,
                cwd = cwd,
                workingDir = workingDir,
                homeDir = homeDir(root),
                tempDir = tempDir,
                overtimeMillis = overtimeMillis,
                autoStopOnOvertime = autoStopOnOvertime,
                extraEnv = toolchain?.extraEnv.orEmpty(),
                pathPrepend = toolchain?.pathPrepend.orEmpty(),
                stdin = stdin,
                overflowFile = overflowFileName?.let { File(tempDir, it) },
                onOvertime = onOvertime,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.isValidRootName()) {
            "Invalid workspace root name: $root"
        }
    }

    /** 清理所有工作区下可安全删除的临时内容（不触碰 linux/ 旧 rootfs，也不触碰 home/） */
    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.isValidRootName()) continue
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val HOME_DIR = "home"
        private const val TEMP_DIR = "tmp"

        /**
         * 工作区目录名 = 用户输入的工作区名，因此要允许中文等 Unicode 字符。
         *
         * 只拒绝会造成路径穿越或文件系统异常的内容：路径分隔符、NUL、控制字符，
         * 以及 `.` / `..` 这两个特殊目录名。
         */
        private val ROOT_NAME_REGEX = Regex("""[^/\\\u0000-\u001F]+""")

        /** 名称是否可以直接当目录名用 */
        fun String.isValidRootName(): Boolean =
            isNotBlank() && this != "." && this != ".." && matches(ROOT_NAME_REGEX)

        /** 把工作区名规整成可用的目录名；无法转换时返回 null */
        fun toRootName(name: String): String? {
            val cleaned = name.trim()
                .replace('/', '_')
                .replace('\\', '_')
                .filterNot { it.code < 0x20 }
                .trim()
                .trimEnd('.')
            return cleaned.takeIf { it.isValidRootName() }
        }
    }
}

internal fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
