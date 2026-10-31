package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.SshRunner
import me.rerere.workspace.SshTarget
import me.rerere.workspace.WorkspaceFileEntry
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
private const val MAX_WRITE_FILE_BYTES = 2 * 1024 * 1024

/** 远程工作区工具的前缀；本地工具不带前缀 */
const val SSH_TOOL_PREFIX = "ssh_"

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "read_file" to false,
    "write_file" to false,
    "edit_file" to false,
    "shell" to true,
)

/**
 * 旧版本的工具名 → 当前名字。
 *
 * 升级后模型看到的工具名变了，但旧对话的历史消息里记的还是 `workspace_*`，
 * 模型也可能照着历史继续这么叫。执行、UI 渲染和审批读取都要认这套旧名字。
 */
val LEGACY_WORKSPACE_TOOL_ALIASES: Map<String, String> = mapOf(
    "workspace_read_file" to "read_file",
    "workspace_write_file" to "write_file",
    "workspace_edit_file" to "edit_file",
    "workspace_shell" to "shell",
)

/** 去掉远程前缀后的基础工具名，用于复用开关与审批设置 */
fun baseWorkspaceToolName(name: String): String =
    if (name.startsWith(SSH_TOOL_PREFIX)) name.removePrefix(SSH_TOOL_PREFIX) else name

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean {
    val base = baseWorkspaceToolName(name)
    val current = LEGACY_WORKSPACE_TOOL_ALIASES[base] ?: base
    // 新旧 key 都认：升级前存下的审批设置不能因为改名而失效；ssh_ 工具复用基础工具的配置
    val legacy = LEGACY_WORKSPACE_TOOL_ALIASES.entries.firstOrNull { it.value == current }?.key
    return overrides[current] ?: overrides[base] ?: overrides[name]
        ?: legacy?.let { overrides[it] }
        ?: WorkspaceToolDefaultApprovals[current] ?: false
}

/** 工作区工具的创建参数：目录、审批与超时策略都在创建时解析一次 */
data class WorkspaceToolContext(
    /** 声明中的工作区主键；为空表示没有工作区，此时只有文件工具可用 */
    val workspaceId: String? = null,
    val workspaceName: String = "",
    /** 文件工具的根目录（工作区目录或沙箱目录） */
    val workingDir: File,
    /** 会话当前目录（相对工作区目录），作为 shell 的默认 cwd */
    val defaultCwd: String? = null,
    val approvals: Map<String, Boolean> = emptyMap(),
    /** 命令超时提示阈值（秒）；null 表示无限制 */
    val overtimeSeconds: Int? = null,
    val autoStopOnOvertime: Boolean = false,
    /** 选中的工具名；为空表示全部创建 */
    val enabledTools: Set<String> = emptySet(),
    /**
     * 非空表示这是 SSH 远程工作区：文件读写与命令都走远端。
     *
     * 为 null 时一切沿用本地 `File` + 原生 shell 的路径。
     */
    val sshRunner: SshRunner? = null,
    val sshTarget: SshTarget? = null,
    /** 工具名前缀；远程组用 [SSH_TOOL_PREFIX]，本地组为空 */
    val toolPrefix: String = "",
) {
    /** 是否走远程；两个字段必须成对出现，缺一个就按本地处理 */
    val isRemote: Boolean get() = sshRunner != null && sshTarget != null

    /** 带前缀的工具名，例如 `ssh_read_file` */
    fun toolName(base: String): String = toolPrefix + base

    /** 根目录的展示文本：本地是绝对路径，远程是 `user@host:root` */
    val rootDescription: String
        get() = if (isRemote) {
            sshTarget?.let { "${it.endpoint}:${it.remoteRoot}" }.orEmpty()
        } else {
            workingDir.absolutePath
        }

    fun needsApproval(name: String): Boolean = resolveWorkspaceToolApproval(name, approvals)

    fun isEnabled(name: String): Boolean =
        enabledTools.isEmpty() || baseWorkspaceToolName(name) in enabledTools
}

/**
 * 创建工作区工具：本地组和远程组各一套，远程组名带 [SSH_TOOL_PREFIX] 前缀。
 *
 * 文件读写是纯 Kotlin 文件 IO，不再经过 shell；命令直接跑在 Android 原生环境
 * （默认 `/system/bin/sh` + toybox，启用 Linux 工具链时为 termex 的 bash）。
 */
fun createWorkspaceTools(
    context: WorkspaceToolContext,
    workspaceRepository: WorkspaceRepository,
): List<Tool> = buildList {
    add(createReadFileTool(context, workspaceRepository))
    add(createWriteFileTool(context, workspaceRepository))
    add(createEditFileTool(context, workspaceRepository))
    // 没有声明任何工作区时 shell 仍然可用，目标目录回落到沙箱
    add(createShellTool(context, workspaceRepository))
}.filter { context.isEnabled(it.name) }

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    context: WorkspaceToolContext,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = context.toolName("read_file"),
    description = """
        Read a file. Relative paths resolve against ${context.rootDescription}.
        Absolute paths are allowed, so the skills, uploads and built-in skills folders can be read directly.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { putPathProperty(required = true) },
            required = listOf("path"),
        )
    },
    needsApproval = { context.needsApproval("read_file") },
    execute = {
        val raw = it.jsonObject.string("path") ?: error("path is required")
        if (context.isRemote) {
            readRemoteFile(context, raw)
        } else {
            val file = resolveToolFile(raw, context.workingDir)
            if (file.path.isImagePath()) {
                readImageFile(file, raw)
            } else {
                val text = readToolText(file, raw)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", raw)
                            put("text", text)
                        }.toString()
                    )
                )
            }
        }
    },
)

private fun createWriteFileTool(
    context: WorkspaceToolContext,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = context.toolName("write_file"),
    description = """
        Write a UTF-8 text file. Relative paths resolve against ${context.rootDescription}.
        Parent directories are created as needed. Files outside the workspace directory require user approval.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    // 审批只看「工具审批」开关：开关关掉后不再对工作区外路径追加确认
    needsApproval = { context.needsApproval("write_file") },
    execute = {
        val params = it.jsonObject
        val raw = params.string("path") ?: error("path is required")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = if (context.isRemote) {
            writeRemoteText(context, raw, text, overwrite)
        } else {
            val file = resolveToolFile(raw, context.workingDir)
            writeToolText(file, text, overwrite, context.workingDir, raw)
        }
        listOf(UIMessagePart.Text(entry.toString()))
    },
)

private fun createEditFileTool(
    context: WorkspaceToolContext,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = context.toolName("edit_file"),
    description = """
        Edit a UTF-8 text file. Relative paths resolve against ${context.rootDescription}.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    // 审批只看「工具审批」开关：开关关掉后不再对工作区外路径追加确认
    needsApproval = { context.needsApproval("edit_file") },
    execute = {
        val params = it.jsonObject
        val raw = params.string("path") ?: error("path is required")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = if (context.isRemote) {
            readRemoteText(context, raw)
        } else {
            readToolText(resolveToolFile(raw, context.workingDir), raw)
        }
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $raw)")
        }
        val entry = if (context.isRemote) {
            writeRemoteText(context, raw, result.updated, overwrite = true)
        } else {
            val file = resolveToolFile(raw, context.workingDir)
            writeToolText(file, result.updated, overwrite = true, context.workingDir, raw)
        }
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    context: WorkspaceToolContext,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = context.toolName("shell"),
    description = buildString {
        if (context.isRemote) {
            val target = context.sshTarget
            append("Run a shell command in the workspace \"${context.workspaceName}\" ")
            append("on the remote host ${target?.endpoint} (remote directory: ${target?.remoteRoot}). ")
            append(
                "The command runs in a real Linux shell there; tools like python, node or java are available if the host provides them. "
            )
        } else if (context.workspaceId != null) {
            append("Run a shell command in the workspace \"${context.workspaceName}\" ")
            append("(directory: ${context.workingDir.absolutePath}). ")
        } else {
            append("Run a shell command in the sandbox ")
            append("(directory: ${context.workingDir.absolutePath}). ")
        }
        append("Use cwd for a path relative to that directory. ")
        if (!context.defaultCwd.isNullOrBlank()) {
            append("Defaults to '${context.defaultCwd}'. ")
        }
        append(overtimeDescription(context.overtimeSeconds, context.autoStopOnOvertime))
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!context.defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace directory. Defaults to '${context.defaultCwd}'."
                        } else {
                            "Working directory relative to the workspace directory. Defaults to the workspace directory."
                        }
                    )
                })
                put("overtime", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Optional overtime notice threshold in seconds for this call, replacing the global setting. " +
                            "This does not stop the command unless auto-stop is enabled."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { context.needsApproval("shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: context.defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val overtimeOverride = params.string("overtime")?.toLongOrNull()?.takeIf { it > 0 }
        val overflowFileName = "shell-${System.currentTimeMillis()}.log"
        val workspaceId = context.workspaceId
        val result = if (workspaceId == null) {
            // 没有声明任何工作区：命令仍然可用，根目录是沙箱
            workspaceRepository.executeSandboxCommandWithSettings(
                command = command,
                cwd = cwd,
                timeoutSecondsOverride = overtimeOverride,
                overflowFileName = overflowFileName,
            )
        } else {
            workspaceRepository.executeCommandWithSettings(
                id = workspaceId,
                command = command,
                cwd = cwd,
                timeoutSecondsOverride = overtimeOverride,
                overflowFileName = overflowFileName,
            )
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    if (result.timedOut) put("timedOut", true)
                    if (result.truncated) {
                        put("truncated", true)
                        result.fullOutputPath?.let { path ->
                            put("fullOutputPath", path)
                            put(
                                "note",
                                "输出超过上限已截断；完整输出保存在 fullOutputPath（这是一个临时文件），" +
                                    "可用 head / tail / grep 读取。"
                            )
                        }
                    }
                }.toString()
            )
        )
    },
)

private fun overtimeDescription(overtimeSeconds: Int?, autoStop: Boolean): String = when {
    overtimeSeconds == null ->
        "Commands are never stopped automatically and there is no overtime notice; only the user can stop a running command."
    autoStop ->
        "Commands running longer than ${overtimeSeconds}s are reported to the user and stopped automatically."
    else ->
        "Commands are not stopped automatically. If a command runs longer than ${overtimeSeconds}s the user is notified and may stop it."
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

/** 相对路径按工作区目录解析，绝对路径原样使用 */
private fun resolveToolFile(raw: String, workingDir: File): File {
    val trimmed = raw.replace('\\', '/').trim()
    require(trimmed.isNotEmpty()) { "path is required" }
    require(!trimmed.contains('\u0000')) { "path contains invalid character" }
    return if (trimmed.startsWith("/")) File(trimmed) else File(workingDir, trimmed)
}

/** 远程读文本：先 stat 判断存在/类型/大小，再取内容，避免把目录或超大文件整个拉过来 */
private suspend fun readRemoteText(context: WorkspaceToolContext, raw: String): String =
    withContext(Dispatchers.IO) {
        val runner = context.sshRunner ?: error("SSH 未配置")
        val target = context.sshTarget ?: error("SSH 未配置")
        val stat = runner.stat(target, raw) ?: error("File does not exist: $raw")
        require(!stat.isDirectory) { "Path is not a file: $raw" }
        require(stat.sizeBytes <= MAX_READ_FILE_BYTES) {
            "File is too large to read: $raw (${stat.sizeBytes / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
        }
        runner.readText(target, raw)
    }

/** 远程读文件，图片走 base64 拉回来再交给聊天文件管理器 */
private suspend fun readRemoteFile(context: WorkspaceToolContext, raw: String): List<UIMessagePart> {
    if (!raw.isImagePath()) {
        val text = readRemoteText(context, raw)
        return listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", raw)
                    put("text", text)
                }.toString()
            )
        )
    }
    val bytes = withContext(Dispatchers.IO) {
        val runner = context.sshRunner ?: error("SSH 未配置")
        val target = context.sshTarget ?: error("SSH 未配置")
        val stat = runner.stat(target, raw) ?: error("File does not exist: $raw")
        require(!stat.isDirectory) { "Path is not a file: $raw" }
        require(stat.sizeBytes <= MAX_READ_FILE_BYTES) {
            "File is too large to read: $raw (${stat.sizeBytes / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB)."
        }
        runner.readBytes(target, raw)
    }
    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", raw)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

/** 远程写文本：内容经 stdin 一次性发过去，在远端本地落盘 */
private suspend fun writeRemoteText(
    context: WorkspaceToolContext,
    raw: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry = withContext(Dispatchers.IO) {
    val runner = context.sshRunner ?: error("SSH 未配置")
    val target = context.sshTarget ?: error("SSH 未配置")
    val bytes = text.toByteArray()
    require(bytes.size <= MAX_WRITE_FILE_BYTES) {
        "Content is too large to write: ${bytes.size} bytes (max $MAX_WRITE_FILE_BYTES)"
    }
    if (!overwrite) {
        require(runner.stat(target, raw) == null) { "File already exists: $raw" }
    }
    val stat = runner.writeText(target, raw, text, overwrite)
    WorkspaceFileEntry(
        path = raw,
        name = raw.trimEnd('/').substringAfterLast('/'),
        isDirectory = false,
        sizeBytes = stat.sizeBytes,
        updatedAt = System.currentTimeMillis(),
    )
}

private suspend fun readToolText(file: File, raw: String): String = withContext(Dispatchers.IO) {
    require(file.exists()) { "File does not exist: $raw" }
    require(file.isFile) { "Path is not a file: $raw" }
    require(file.length() <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $raw (${file.length() / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    file.readText()
}

private suspend fun writeToolText(
    file: File,
    text: String,
    overwrite: Boolean,
    workingDir: File,
    raw: String,
): me.rerere.workspace.WorkspaceFileEntry = withContext(Dispatchers.IO) {
    val bytes = text.toByteArray()
    require(bytes.size <= MAX_WRITE_FILE_BYTES) {
        "Content is too large to write: ${bytes.size} bytes (max $MAX_WRITE_FILE_BYTES)"
    }
    require(!file.exists() || overwrite) { "File already exists: $raw" }
    require(!file.exists() || file.isFile) { "Path is not a file: $raw" }
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
    me.rerere.workspace.WorkspaceFileEntry(
        path = relativeToWorkingDir(file, workingDir),
        name = file.name,
        isDirectory = false,
        sizeBytes = file.length(),
        updatedAt = file.lastModified(),
    )
}

private fun relativeToWorkingDir(file: File, workingDir: File): String = runCatching {
    file.canonicalFile.relativeTo(workingDir.canonicalFile).path.replace(File.separatorChar, '/')
}.getOrElse { file.absolutePath }

private suspend fun readImageFile(file: File, raw: String): List<UIMessagePart> {
    val bytes = withContext(Dispatchers.IO) {
        require(file.exists()) { "File does not exist: $raw" }
        require(file.isFile) { "Path is not a file: $raw" }
        require(file.length() <= MAX_READ_FILE_BYTES) {
            "File is too large to read: $raw (${file.length() / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB)."
        }
        file.readBytes()
    }
    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", raw)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Path to the file. Relative paths resolve against the workspace directory; absolute paths are used as-is."
            } else {
                "Optional path to the file. Relative paths resolve against the workspace directory; absolute paths are used as-is."
            }
        )
    })
}
