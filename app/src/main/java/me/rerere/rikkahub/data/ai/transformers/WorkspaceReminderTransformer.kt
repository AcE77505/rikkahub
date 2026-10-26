package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.SSH_TOOL_PREFIX
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.SshTarget
import me.rerere.workspace.ToolchainEnvironment
import java.io.File

/**
 * Workspace 系统提示注入转换器
 *
 * 只要有一个工作区被「声明」（设置 → 工具 → 工作区列表里的声明开关），就在系统提示词中追加一段引导，
 * 让模型知道工作区目录在哪、命令跑在什么环境里、有哪些工具可用。
 *
 * 不再依赖助手绑定工作区，也不再读取 Rootfs 发行版：命令直接跑在 Android 原生环境
 * （`/system/bin/sh` + toybox），启用 Linux 工具链时才额外有 bash / openssl / curl / ssh。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val prompt = buildWorkspacePromptSections(
            context = ctx.context,
            settings = ctx.settings,
            workspaceCwd = ctx.workspaceCwd,
            workspaceRepository = workspaceRepository,
        )
        if (prompt.isBlank()) return messages
        return messages.appendSystemPrompt(prompt)
    }
}

/**
 * 组装工作区提示词：按声明的工作区渲染 1~2 段（本地段 + 远程段）。
 *
 * 设置页的预览与真正发请求时调用同一个函数，保证「看到的」就是「注入的」。
 * 没有任何工作区相关工具时返回空串；没有声明工作区时返回沙箱提示。
 */
internal suspend fun buildWorkspacePromptSections(
    context: Context,
    settings: Settings,
    workspaceCwd: String?,
    workspaceRepository: WorkspaceRepository,
): String {
    val enabledTools = settings.localTools.toSet()
    // 文件与 shell 工具都没开时不需要工作区说明
    if (LocalToolOption.WorkspaceReadFile !in enabledTools &&
        LocalToolOption.WorkspaceShell !in enabledTools
    ) {
        return ""
    }

    val local = workspaceRepository.getDeclaredLocal()
    val remote = workspaceRepository.getDeclaredSsh()?.let { workspace ->
        workspace.sshTarget()?.let { workspace to it }
    }
    val sandbox = workspaceRepository.sandboxDir()
    // 模板可能被用户清空，清空时回退到内置默认
    val template = settings.workspacePrompt.ifBlank { DEFAULT_WORKSPACE_PROMPT }
    val toolchain = local?.let {
        runCatching { workspaceRepository.toolchainEnvironment(it.id) }.getOrNull()
    }
    val skillsDir = File(context.filesDir, FileFolders.SKILLS)
    val builtinSkillsDir = File(context.filesDir, FileFolders.BUILTIN_SKILLS)
    val uploadDir = File(context.filesDir, FileFolders.UPLOAD)

    val sections = buildList {
        val localDir = local?.let {
            runCatching { workspaceRepository.workingDirectory(it.id) }.getOrNull()
        }
        if (local != null && localDir != null) {
            add(
                renderWorkspacePrompt(
                    template = template,
                    values = workspacePromptValues(
                        workspaceName = local.name,
                        workspaceDir = localDir,
                        remote = null,
                        toolchain = toolchain,
                        enabledTools = enabledTools,
                        cwd = workspaceCwd,
                        skillsDir = skillsDir,
                        builtinSkillsDir = builtinSkillsDir,
                        uploadDir = uploadDir,
                        toolPrefix = "",
                    ),
                ) + buildAgentsPrompt(localDir, workspaceCwd)
            )
        } else {
            // 没有本地工作区：文件与 shell 工具退回沙箱
            add(buildFileToolsPrompt(sandbox, enabledTools).orEmpty())
        }

        remote?.let { (workspace, target) ->
            add(
                renderWorkspacePrompt(
                    template = template,
                    values = workspacePromptValues(
                        workspaceName = workspace.name,
                        workspaceDir = sandbox,
                        remote = target,
                        toolchain = null,
                        enabledTools = enabledTools,
                        cwd = workspaceCwd,
                        skillsDir = skillsDir,
                        builtinSkillsDir = builtinSkillsDir,
                        uploadDir = uploadDir,
                        toolPrefix = SSH_TOOL_PREFIX,
                    ),
                    // 远程读不到手机上的 skills / uploads，去掉这几行
                    dropLinesWith = setOf(
                        WorkspacePromptPlaceholders.SKILLS_DIR,
                        WorkspacePromptPlaceholders.BUILTIN_SKILLS_DIR,
                        WorkspacePromptPlaceholders.UPLOAD_DIR,
                    ),
                )
            )
        }
    }.filter { it.isNotBlank() }

    return sections.joinToString("\n\n")
}

/**
 * 没有声明工作区时的提示：只说明文件/shell 工具以沙箱为根，以及可用工具。
 *
 * 不涉及工作区名称、"已声明"这类概念，避免模型误以为存在工作区。
 * 文件与 shell 都没开时返回 null。
 */
private fun buildFileToolsPrompt(
    sandbox: File,
    enabledTools: Set<LocalToolOption>,
): String? {
    val readEnabled = LocalToolOption.WorkspaceReadFile in enabledTools
    val shellEnabled = LocalToolOption.WorkspaceShell in enabledTools
    if (!readEnabled && !shellEnabled) return null
    return buildString {
        appendLine("<file_tools>")
        appendLine("No workspace has been declared. You can still work with files and commands in a sandbox.")
        appendLine(
            "- The sandbox root folder is `${sandbox.absolutePath}`. " +
                "Relative paths resolve against it; absolute paths are used as-is."
        )
        if (shellEnabled) {
            appendLine("- `shell` runs commands with the sandbox root as its working directory.")
        }
        appendLine("- Available tools:")
        if (readEnabled) {
            appendLine("  - `read_file`: read file contents (text or images).")
            if (LocalToolOption.WorkspaceWriteFile in enabledTools) {
                appendLine("  - `write_file`: create or overwrite files.")
            }
            if (LocalToolOption.WorkspaceEditFile in enabledTools) {
                appendLine("  - `edit_file`: make precise edits to existing files.")
            }
        }
        if (shellEnabled) {
            appendLine("  - `shell`: run shell commands in the sandbox.")
        }
        append("</file_tools>")
    }
}

/**
 * 在工作区目录与当前 cwd 两层查找 AGENTS.md。
 *
 * 工作区目录 = 用户声明的目录本身，cwd 是会话里的相对子目录，两者都是宿主真实路径。
 */
private fun buildAgentsPrompt(workspaceDir: File, cwd: String?): String {
    val workingDirectory = File(workspaceDir, cwd?.takeIf { it.isNotBlank() } ?: ".")
    val paths = linkedSetOf(
        File(workspaceDir, AGENTS_FILE),
        File(workingDirectory, AGENTS_FILE),
    )
    val instructions = paths.mapNotNull { file ->
        readWorkspaceText(file)?.let { file.absolutePath to it }
    }
    if (instructions.isEmpty()) return ""
    return buildString {
        appendLine()
        appendLine()
        appendLine("<workspace_instructions>")
        appendLine("Follow the AGENTS.md instructions below.")
        instructions.forEach { (path, content) ->
            appendLine()
            appendLine("AGENTS.md source: $path")
            appendLine(content)
        }
        append("</workspace_instructions>")
    }
}

// 文件不存在、过大或不可读时返回 null, 不影响提示词的其余部分
private fun readWorkspaceText(file: File): String? = try {
    if (!file.isFile) {
        null
    } else if (file.length() > MAX_AGENTS_BYTES) {
        null
    } else {
        file.readText().takeIf { it.isNotBlank() }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.d(TAG, "Skipping workspace file: ${file.absolutePath}", e)
    null
}

private const val TAG = "WorkspaceReminder"
private const val MAX_AGENTS_BYTES = 64L * 1024
private const val AGENTS_FILE = "AGENTS.md"

/** 占位符实际取值，对应「设置 → 工具 → 工作区提示词」里可用的变量 */
private fun workspacePromptValues(
    workspaceName: String,
    workspaceDir: File,
    remote: SshTarget?,
    toolchain: ToolchainEnvironment?,
    enabledTools: Set<LocalToolOption>,
    cwd: String?,
    skillsDir: File,
    builtinSkillsDir: File,
    uploadDir: File,
    toolPrefix: String,
): Map<String, String> = mapOf(
    WorkspacePromptPlaceholders.WORKSPACE_NAME to workspaceName,
    WorkspacePromptPlaceholders.WORKSPACE_DIR to (
        remote?.let { "${it.endpoint}:${it.remoteRoot}" } ?: workspaceDir.absolutePath
        ),
    WorkspacePromptPlaceholders.SHELL_ENV to if (remote != null) {
        buildRemoteShellEnv(remote)
    } else {
        buildShellEnv(toolchain)
    },
    WorkspacePromptPlaceholders.TOOL_LIST to buildToolList(enabledTools, toolPrefix),
    WorkspacePromptPlaceholders.SKILLS_DIR to skillsDir.absolutePath,
    WorkspacePromptPlaceholders.BUILTIN_SKILLS_DIR to builtinSkillsDir.absolutePath,
    WorkspacePromptPlaceholders.UPLOAD_DIR to uploadDir.absolutePath,
    WorkspacePromptPlaceholders.CWD_LINE to buildCwdLine(cwd),
)

/** 远程工作区的环境说明：命令在远端 Linux 上跑，可用工具取决于那台机器 */
private fun buildRemoteShellEnv(target: SshTarget): String = buildString {
    appendLine(
        "- Files and commands live on a remote host reached over SSH (`${target.endpoint}`), " +
            "with `${target.remoteRoot}` as the remote working directory."
    )
    appendLine(
        "- The remote side is a real Linux shell. Use whatever it provides " +
            "(python, node, java, package managers) when available."
    )
    append(
        "- `${SSH_TOOL_PREFIX}shell` runs commands with the remote login shell; " +
            "relative paths resolve against the remote working directory."
    )
}

/** 命令环境说明：原生 shell 一段，启用工具链时补上 bash 与探针结果 */
private fun buildShellEnv(toolchain: ToolchainEnvironment?): String = buildString {
    appendLine(
        "- Commands run directly in the Android native environment (`/system/bin/sh` + toybox). " +
            "This is not a Linux distribution: there is no apt, apk or any other package manager, " +
            "so do not try to install packages."
    )
    if (toolchain != null && toolchain.shellPath != null) {
        val probes = toolchain.probes
        val shellVersion = probes.firstOrNull { it.name == "bash" }?.version
        appendLine(
            "- `shell` runs commands with `bash`" +
                (shellVersion?.let { " ($it)" } ?: "") +
                ", and the toolchain ships extra tools."
        )
        if (probes.isNotEmpty()) {
            appendLine("- Available commands: " + probes.joinToString(", ") { "${it.name} (${it.version})" })
        }
    } else {
        appendLine(
            "- `shell` runs commands with `/system/bin/sh`, a POSIX shell. " +
                "Bash is not available, so avoid bash-only syntax such as arrays, " +
                "here-strings (`<<<`) and brace expansion."
        )
    }
}.trimEnd()

/**
 * 只列当前真正挂载的工具，配置与提示词不会各说各话。
 *
 * [prefix] 为空时是本地组，远程组传 [SSH_TOOL_PREFIX]。
 */
private fun buildToolList(enabledTools: Set<LocalToolOption>, prefix: String): String = buildString {
    fun tool(base: String) = "`$prefix$base`"
    appendLine("- Available tools:")
    val readEnabled = LocalToolOption.WorkspaceReadFile in enabledTools
    if (readEnabled) {
        appendLine("  - ${tool("read_file")}: read file contents (text or images).")
        if (LocalToolOption.WorkspaceWriteFile in enabledTools) {
            appendLine("  - ${tool("write_file")}: create or overwrite files.")
        }
        if (LocalToolOption.WorkspaceEditFile in enabledTools) {
            appendLine("  - ${tool("edit_file")}: make precise edits to existing files.")
        }
    }
    if (LocalToolOption.WorkspaceShell in enabledTools) {
        appendLine("  - ${tool("shell")}: run shell commands in the working directory.")
    }
}.trimEnd()

private fun buildCwdLine(cwd: String?): String =
    cwd?.takeIf { it.isNotBlank() }?.let {
        "- Current working directory: `$it` (relative to the workspace directory). " +
            "Use this as the default context for file operations and shell commands."
    }.orEmpty()

/** 追加到第一条 system 消息；不存在则插入一条 */
private fun List<UIMessage>.appendSystemPrompt(prompt: String): List<UIMessage> {
    val systemIndex = indexOfFirst { it.role == MessageRole.SYSTEM }
    return if (systemIndex >= 0) {
        toMutableList().apply {
            this[systemIndex] = this[systemIndex]
                .appendText("\n\n$prompt")
                .copy(isSynthetic = true)
        }
    } else {
        listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + this
    }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
