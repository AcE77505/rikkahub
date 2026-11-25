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
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.subagent.SubAgentApprovalRegistry
import me.rerere.rikkahub.data.ai.subagent.SubAgentProgressRegistry
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentStore
import me.rerere.rikkahub.data.ai.tools.dynamic.CapabilityCatalog
import me.rerere.rikkahub.data.ai.tools.dynamic.DeferPolicy
import me.rerere.rikkahub.data.ai.tools.dynamic.DynamicTools
import me.rerere.rikkahub.data.ai.tools.dynamic.PromptOverlays
import me.rerere.rikkahub.data.ai.tools.dynamic.createDynamicToolEntries
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

/**
 * 不参与动态工具的 defer。
 *
 * 技能清单由 view_skill 的 system prompt 全量声明，若把它 defer 掉，模型既看不到清单
 * 也无法通过 search_tools 拿到技能本体（技能没有可执行工具）。
 */
private val ALWAYS_LOADED_TOOLS = listOf(SKILL_VIEW_TOOL_NAME, "export_skill")
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
    private val subAgentProgressRegistry: SubAgentProgressRegistry,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
    ): List<Tool> = createDynamicTools(settings, assistant, model, workspaceCwd, conversationId).tools

    /**
     * 构建本轮的工具集合，并按需应用动态工具策略。
     *
     * 动态工具开启时：体积超过阈值的工具**不进入** [DynamicTools.tools]（也就不会发给 provider），
     * 但仍在 [DynamicTools.entries] 里，供 `search_tools` 检索、`call_tool` 执行。
     * 这样 AgentLoop 不需要知道 defer 的存在，`findTool` 只会在要发的工具里找。
     */
    internal suspend fun createDynamicTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
    ): DynamicTools {
        // 工作区工具不再依赖助手绑定：开关在设置里，目标是被声明的工作区
        val declaredLocal = workspaceRepository.getDeclaredLocal()
        // 过长的输出存成文件让模型按需读取；没有声明本地工作区时落到沙箱，
        // 本地的 read_file / shell 仍然够得着
        val workspaceDir = declaredLocal
            ?.let { runCatching { workspaceRepository.workingDirectory(it.id) }.getOrNull() }
            ?.takeIf { it.isDirectory }
            ?: workspaceRepository.sandboxDir()
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
                // 搜索/导出/导入也是记忆库工具，跟随助手的「记忆」开关
                addAll(buildMemoryFileTools(memoryRepository, assistant.memoryId, workspaceDir))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(settings.localTools, workspaceCwd, assistant.id, conversationId))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                        workspaceRepository = workspaceRepository,
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
        val allTools = if (conversationId != null) {
            val taskTool = createTaskTool(
                runner = subAgentRunner,
                store = subAgentStore,
                settings = settings,
                assistant = assistant,
                parentModel = model,
                baseTools = tools,
                conversationId = conversationId,
                approvalRegistry = subAgentApprovalRegistry,
                progressRegistry = subAgentProgressRegistry,
            )
            if (taskTool != null) {
                val fleetTool = createFleetTool(
                    runner = subAgentRunner,
                    settings = settings,
                    assistant = assistant,
                    parentModel = model,
                    baseTools = tools,
                    conversationId = conversationId,
                    approvalRegistry = subAgentApprovalRegistry,
                    progressRegistry = subAgentProgressRegistry,
                )
                buildList {
                    addAll(tools)
                    add(taskTool)
                    add(createReadSubAgentResultTool(subAgentStore, conversationId))
                    fleetTool?.let(::add)
                }
            } else {
                tools
            }
        } else {
            tools
        }
        val prepared = allTools
            .map { withToolPromptOverrides(it, settings) }
            .map { withOutputTruncation(it, workspaceDir) }
        return applyDynamicTools(prepared, settings, assistant)
    }

    /**
     * 应用动态工具策略。
     *
     * 关闭时原样返回。开启时把超过阈值的工具从 [DynamicTools.tools] 里移走，再加两个入口工具；
     * 被移走的工具仍留在 [DynamicTools.entries] 里供检索与执行。
     */
    private suspend fun applyDynamicTools(
        tools: List<Tool>,
        settings: Settings,
        assistant: Assistant,
    ): DynamicTools {
        if (!settings.dynamicToolsEnabled) {
            return DynamicTools(tools, emptyList(), emptySet())
        }
        val deferred = DeferPolicy.selectDeferred(
            tools = tools,
            // 技能相关工具不参与 defer：技能清单要靠 view_skill 全量声明给模型，
            // 它自己的 system prompt 也就是那份清单；export_skill 与它配对使用
            alwaysLoaded = settings.dynamicToolsAlwaysLoaded + ALWAYS_LOADED_TOOLS,
        )
        if (deferred.isEmpty()) {
            // 没有工具需要 defer 时不要注入两个空入口，否则只是白白增加提示词
            return DynamicTools(tools, emptyList(), emptySet())
        }
        val deferredIds = deferred.mapTo(LinkedHashSet()) { it.name }
        // MCP 状态是 host 观察到的结果：未连接的服务器在检索结果里要标注，避免模型盲目调用
        val mcpStatuses = mcpManager.syncingStatus.value
        val statusByName = settings.mcpServers.associate { server ->
            server.commonOptions.name to (mcpStatuses[server.id] ?: McpStatus.Idle)
        }
        val overlays = PromptOverlays(
            descriptions = settings.toolDescriptionOverrides,
            systemPrompts = settings.toolSystemPromptOverrides,
            legacySkillName = LEGACY_SKILL_VIEW_TOOL_NAME,
        )
        val entries = CapabilityCatalog.build(
            tools = tools,
            mcpStatus = statusByName,
            overlays = overlays,
            aliases = LEGACY_WORKSPACE_TOOL_ALIASES + SKILL_TOOL_ALIASES,
        )
        val visible = tools.filter { it.name !in deferredIds }
        val shell = DynamicTools(
            tools = visible,
            entries = entries,
            deferredIds = deferredIds,
        )
        return DynamicTools(
            tools = visible + createDynamicToolEntries(shell, overlays),
            entries = entries,
            deferredIds = deferredIds,
        )
    }

    /**
     * 应用「设置 → 工具提示词」里的覆盖。工作区远程工具的覆盖沿用基础工具名
     * （`ssh_read_file` 使用 `read_file` 的覆盖），避免本地/远程两套文案。
     */
    private fun withToolPromptOverrides(tool: Tool, settings: Settings): Tool {
        val baseName = if (tool.name.startsWith(SSH_TOOL_PREFIX)) baseWorkspaceToolName(tool.name) else tool.name
        // 技能工具从 use_skill 改名为 view_skill，旧设置里的覆盖继续生效
        val legacyName = if (tool.name == SKILL_VIEW_TOOL_NAME) LEGACY_SKILL_VIEW_TOOL_NAME else null
        val description = settings.toolDescriptionOverrides[tool.name]
            ?: legacyName?.let { settings.toolDescriptionOverrides[it] }
            ?: settings.toolDescriptionOverrides[baseName]
        val systemPrompt = settings.toolSystemPromptOverrides[tool.name]
            ?: legacyName?.let { settings.toolSystemPromptOverrides[it] }
            ?: settings.toolSystemPromptOverrides[baseName]
        if (description == null && systemPrompt == null) return tool
        return tool.copy(
            description = description ?: tool.description,
            systemPrompt = systemPrompt?.let { custom -> { _, _ -> custom } } ?: tool.systemPrompt,
        )
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
