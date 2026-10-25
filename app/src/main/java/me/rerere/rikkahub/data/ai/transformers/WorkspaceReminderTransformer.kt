package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.repository.WorkspaceRepository
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
        // 没有声明工作区时不再整体跳过：文件工具仍然挂着，补一段只讲根目录的轻量提示
        val workspace = workspaceRepository.getDeclared()
        if (workspace == null) {
            val fileToolsPrompt = buildFileToolsPrompt(ctx) ?: return messages
            return messages.appendSystemPrompt(fileToolsPrompt)
        }
        val workspaceDir = runCatching { workspaceRepository.workingDirectory(workspace.id) }
            .getOrElse { error ->
                Log.w(TAG, "Skip workspace prompt, cannot resolve workspace dir", error)
                return messages
            }
        val toolchain = runCatching { workspaceRepository.toolchainEnvironment(workspace.id) }
            .getOrNull()

        // 模板可能被用户清空，清空时回退到内置默认
        val template = ctx.settings.workspacePrompt.ifBlank { DEFAULT_WORKSPACE_PROMPT }
        val prompt = renderWorkspacePrompt(
            template = template,
            values = workspacePromptValues(
                workspaceName = workspace.name,
                workspaceDir = workspaceDir,
                toolchain = toolchain,
                enabledTools = ctx.settings.localTools.toSet(),
                cwd = ctx.workspaceCwd,
                skillsDir = File(ctx.context.filesDir, FileFolders.SKILLS),
                builtinSkillsDir = File(ctx.context.filesDir, FileFolders.BUILTIN_SKILLS),
                uploadDir = File(ctx.context.filesDir, FileFolders.UPLOAD),
            ),
        ) + buildAgentsPrompt(workspaceDir, ctx.workspaceCwd)

        return messages.appendSystemPrompt(prompt)
    }

    /**
     * 没有声明工作区时的提示：只说明文件工具的根目录与可用工具。
     *
     * 不涉及工作区名称、"已声明"这类概念，避免模型误以为存在工作区。
     * 「读取文件」没开时返回 null，调用方直接原样返回消息。
     */
    private suspend fun buildFileToolsPrompt(ctx: TransformerContext): String? {
        val enabled = ctx.settings.localTools.toSet()
        if (LocalToolOption.WorkspaceReadFile !in enabled) return null
        val rootDir = runCatching { workspaceRepository.sandboxDir() }.getOrNull() ?: return null
        return buildString {
            appendLine("<file_tools>")
            appendLine("You can read and write files with these tools.")
            appendLine(
                "- Their root folder is `${rootDir.absolutePath}`. " +
                    "Relative paths resolve against it; absolute paths are used as-is."
            )
            appendLine("- Available tools:")
            appendLine("  - `read_file`: read file contents (text or images).")
            if (LocalToolOption.WorkspaceWriteFile in enabled) {
                appendLine("  - `write_file`: create or overwrite files.")
            }
            if (LocalToolOption.WorkspaceEditFile in enabled) {
                appendLine("  - `edit_file`: make precise edits to existing files.")
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

    private companion object {
        const val TAG = "WorkspaceReminder"
        const val MAX_AGENTS_BYTES = 64L * 1024
        const val AGENTS_FILE = "AGENTS.md"
    }
}

/** 占位符实际取值，对应「设置 → 工具 → 工作区提示词」里可用的变量 */
private fun workspacePromptValues(
    workspaceName: String,
    workspaceDir: File,
    toolchain: ToolchainEnvironment?,
    enabledTools: Set<LocalToolOption>,
    cwd: String?,
    skillsDir: File,
    builtinSkillsDir: File,
    uploadDir: File,
): Map<String, String> = mapOf(
    WorkspacePromptPlaceholders.WORKSPACE_NAME to workspaceName,
    WorkspacePromptPlaceholders.WORKSPACE_DIR to workspaceDir.absolutePath,
    WorkspacePromptPlaceholders.SHELL_ENV to buildShellEnv(toolchain),
    WorkspacePromptPlaceholders.TOOL_LIST to buildToolList(enabledTools),
    WorkspacePromptPlaceholders.SKILLS_DIR to skillsDir.absolutePath,
    WorkspacePromptPlaceholders.BUILTIN_SKILLS_DIR to builtinSkillsDir.absolutePath,
    WorkspacePromptPlaceholders.UPLOAD_DIR to uploadDir.absolutePath,
    WorkspacePromptPlaceholders.CWD_LINE to buildCwdLine(cwd),
)

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
            "- `workspace_shell` runs commands with `bash`" +
                (shellVersion?.let { " ($it)" } ?: "") +
                ", and the toolchain ships extra tools."
        )
        if (probes.isNotEmpty()) {
            appendLine("- Available commands: " + probes.joinToString(", ") { "${it.name} (${it.version})" })
        }
    } else {
        appendLine(
            "- `workspace_shell` runs commands with `/system/bin/sh`, a POSIX shell. " +
                "Bash is not available, so avoid bash-only syntax such as arrays, " +
                "here-strings (`<<<`) and brace expansion."
        )
    }
}.trimEnd()

/** 只列当前真正挂载的工具，配置与提示词不会各说各话 */
private fun buildToolList(enabledTools: Set<LocalToolOption>): String = buildString {
    appendLine("- Available tools:")
    val readEnabled = LocalToolOption.WorkspaceReadFile in enabledTools
    if (readEnabled) {
        appendLine("  - `read_file`: read file contents (text or images).")
        if (LocalToolOption.WorkspaceWriteFile in enabledTools) {
            appendLine("  - `write_file`: create or overwrite files.")
        }
        if (LocalToolOption.WorkspaceEditFile in enabledTools) {
            appendLine("  - `edit_file`: make precise edits to existing files.")
        }
    }
    if (LocalToolOption.WorkspaceShell in enabledTools) {
        appendLine("  - `workspace_shell`: run shell commands in the workspace directory.")
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
