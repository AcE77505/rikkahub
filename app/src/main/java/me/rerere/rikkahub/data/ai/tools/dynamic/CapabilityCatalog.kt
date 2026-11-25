package me.rerere.rikkahub.data.ai.tools.dynamic

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.mcp.McpStatus

/**
 * 把一轮里可用的工具、技能、MCP 工具汇总成统一目录。
 *
 * 只做汇总，不做筛选：哪些进 deferred 集合由 [DeferPolicy] 按阈值决定。
 * 这样检索索引始终是全量的，避免「本轮没被 defer 的工具反而搜不到」。
 */
internal object CapabilityCatalog {

    const val MCP_PREFIX = "mcp__"
    const val MCP_SEPARATOR = "__"

    /**
     * @param tools 本轮已构建的工具（含本地、工作区、记忆、技能、MCP、子代理）
     * @param mcpStatus 服务器名 -> 状态；用于把「未连接」标注出来
     * @param overlays 「设置 → 工具提示词」的覆盖，检索文档要用覆盖后的文本
     * @param aliases 旧工具名 -> 现工具名；旧名也参与检索与按名查找
     */
    fun build(
        tools: List<Tool>,
        mcpStatus: Map<String, McpStatus> = emptyMap(),
        overlays: PromptOverlays = PromptOverlays.NONE,
        aliases: Map<String, String> = emptyMap(),
    ): List<CapabilityEntry> {
        val aliasesByCurrent = aliases.entries
            .groupBy({ it.value }, { it.key })
        // 技能暂不纳入目录：技能清单仍由 view_skill 的 system prompt 全量声明，
        // 这里只登记工具，避免同名技能与工具重复、也避免技能在 deferred 后无法执行
        return tools.map { tool ->
            tool.toEntry(overlays, mcpStatus, aliasesByCurrent[tool.name].orEmpty())
        }
    }

    private fun Tool.toEntry(
        overlays: PromptOverlays,
        mcpStatus: Map<String, McpStatus>,
        aliases: List<String>,
    ): CapabilityEntry {
        // MCP 工具名形如 mcp__server__tool，服务器名用于查状态
        val mcpServer = if (name.startsWith(MCP_PREFIX)) {
            name.removePrefix(MCP_PREFIX).substringBefore(MCP_SEPARATOR)
        } else {
            null
        }
        return CapabilityEntry(
            id = name,
            kind = if (mcpServer != null) CapabilityEntry.Kind.MCP else CapabilityEntry.Kind.TOOL,
            name = name,
            description = overlays.description(name) ?: description,
            systemPrompt = overlays.systemPrompt(name) ?: "",
            aliases = aliases,
            parameterNames = parameterNames(),
            tool = this,
            status = mcpServer?.toStatus(mcpStatus) ?: CapabilityEntry.Status.Ready,
            failureReason = (mcpStatus[mcpServer] as? McpStatus.Error)?.message,
        )
    }

    /** 未连接的服务器仍可搜到：调用时会自动连接，只是结果里要提示状态 */
    private fun String.toStatus(mcpStatus: Map<String, McpStatus>): CapabilityEntry.Status =
        when (val status = mcpStatus[this]) {
            null, McpStatus.Connected -> CapabilityEntry.Status.Ready
            McpStatus.Idle, McpStatus.Connecting, is McpStatus.Reconnecting ->
                CapabilityEntry.Status.NotConnected

            is McpStatus.Error, McpStatus.NeedsAuthorization, McpStatus.Authorizing ->
                CapabilityEntry.Status.Failed
        }
}

/**
 * 「设置 → 工具提示词」里的覆盖。
 *
 * 检索文档必须用覆盖后的文本，否则用户改过的措辞搜不到。
 * 技能工具从 `use_skill` 改名为 `view_skill`，旧覆盖要继续生效。
 */
internal class PromptOverlays(
    private val descriptions: Map<String, String> = emptyMap(),
    private val systemPrompts: Map<String, String> = emptyMap(),
    private val legacySkillName: String? = null,
) {
    fun description(name: String): String? =
        descriptions[name] ?: legacySkillName?.let { descriptions[it] }

    fun systemPrompt(name: String): String? =
        systemPrompts[name] ?: legacySkillName?.let { systemPrompts[it] }

    companion object {
        val NONE = PromptOverlays()
    }
}
