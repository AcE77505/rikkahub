package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.subagent.SubAgentApprovalRegistry
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentStore
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

/** 长输出落盘的子目录名（相对工作区目录）；没有工作区时回落到 app 私有 files/tool_outputs */
private const val TOOL_OUTPUT_DIR = ".tool_outputs"

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val subAgentRunner: SubAgentRunner,
    private val subAgentStore: SubAgentStore,
    private val subAgentApprovalRegistry: SubAgentApprovalRegistry,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
    ): List<Tool> {
        // 工作区工具不再依赖助手绑定：开关在设置里，目标是被声明的工作区
        val declaredLocal = workspaceRepository.getDeclaredLocal()
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(settings.localTools, workspaceCwd))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // 过长的输出存成文件让模型按需读取；没有声明本地工作区时落到沙箱，
        // 本地的 read_file / shell 仍然够得着
        val workspaceDir = declaredLocal
            ?.let { runCatching { workspaceRepository.workingDirectory(it.id) }.getOrNull() }
            ?.takeIf { it.isDirectory }
            ?: workspaceRepository.sandboxDir()
        val allTools = if (conversationId != null) {
            val taskTool = createTaskTool(
                runner = subAgentRunner,
                settings = settings,
                assistant = assistant,
                parentModel = model,
                baseTools = tools,
                conversationId = conversationId,
                approvalRegistry = subAgentApprovalRegistry,
            )
            if (taskTool != null) {
                tools + taskTool + createReadSubAgentResultTool(subAgentStore, conversationId)
            } else {
                tools
            }
        } else {
            tools
        }
        return allTools.map { withOutputTruncation(it, workspaceDir) }
    }

    private fun withOutputTruncation(tool: Tool, workspaceDir: File): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it), workspaceDir) })
    }

    private fun truncateOutput(output: List<UIMessagePart>, workspaceDir: File): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output
        // shell 工具已经在 runner 层把完整输出落过盘，这里再存一份只会让两处路径提示互相打架
        if (textParts.any(::hasFullOutputPath)) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(workspaceDir, TOOL_OUTPUT_DIR).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)
        val relativePath = "$TOOL_OUTPUT_DIR/$fileName"

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved as `$relativePath` inside the workspace directory.")
                    appendLine("Read it with read_file (path: \"$relativePath\")")
                    appendLine("or with shell: `cat \"$relativePath\"` / `grep \"pattern\" \"$relativePath\"`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    private fun hasFullOutputPath(part: UIMessagePart.Text): Boolean = runCatching {
        Json.parseToJsonElement(part.text).jsonObject["fullOutputPath"] != null
    }.getOrDefault(false)

    /** 保留给没有工作区时使用的落盘位置（app 私有目录） */
    private fun fallbackOutputDir(): File =
        File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
}
