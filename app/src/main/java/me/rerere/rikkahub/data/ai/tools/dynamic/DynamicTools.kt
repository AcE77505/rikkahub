package me.rerere.rikkahub.data.ai.tools.dynamic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 动态工具模式下，一轮请求需要携带的全部状态。
 *
 * [tools] 是真正发给模型的工具（常驻工具 + `search_tools` + `call_tool`），
 * [entries] 是**全量**目录，[deferredIds] 标出其中哪些不在 [tools] 里。
 *
 * 分开保存的原因：检索必须能搜到被 defer 的工具，但那些工具不能出现在 `tools` 数组里。
 */
class DynamicTools(
    val tools: List<Tool>,
    val entries: List<CapabilityEntry>,
    val deferredIds: Set<String>,
) {
    val deferredCount: Int get() = deferredIds.size

    /** 被 defer 的条目按领域去重，供「还有 N 个工具未列出」的提示 */
    val deferredDomains: List<String>
        get() = entries
            .filter { it.id in deferredIds }
            .map { it.domainLabel() }
            .distinct()
            .sorted()

    /** 本轮注入的路由块；没有命中时为空串，调用方据此跳过注入 */
    fun renderRoute(query: String): String =
        CapabilityRouteRenderer.render(entries.filter { it.id in deferredIds }, query)

    /**
     * 批量调用里只要有一个目标工具需要审批，整个 `call_tool` 调用就标记为需要审批。
     *
     * 不能整体放行：被 defer 的工具不在 `request.tools` 里，AgentLoop 的 `findTool` 找不到它们，
     * 也就无法为它们单独发起审批。把审批提升到 `call_tool` 这一层，至少不会绕过用户的审批设置。
     */
    fun hasPendingApproval(input: JsonElement): Boolean {
        val calls = (input as? JsonObject)?.get("calls") as? JsonArray ?: return false
        return calls.any { call ->
            val obj = call as? JsonObject ?: return@any false
            val toolName = obj["tool_name"]?.jsonPrimitive?.content ?: return@any false
            val tool = findEntry(toolName)?.tool ?: return@any false
            val paramsText = obj["params"]?.jsonPrimitive?.content ?: "{}"
            val params = runCatching { JsonInstant.parseToJsonElement(paramsText) }.getOrNull()
                ?: return@any false
            runCatching { tool.needsApproval(params) }.getOrDefault(false)
        }
    }

    /** 按 id、名称或别名查找目录条目；`search_tools` 与 `call_tool` 共用同一套匹配规则 */
    fun findEntry(name: String): CapabilityEntry? = entries.firstOrNull { entry ->
        entry.id == name || entry.name == name || entry.aliases.any { it == name }
    }

    companion object {
        /** 关闭动态工具时的空实现，让调用方无需到处判空 */
        val DISABLED = DynamicTools(emptyList(), emptyList(), emptySet())
    }
}

/**
 * 从目录条目推断领域标签，用于「未列出的工具大概覆盖什么」的提示。
 *
 * 只做粗分类：MCP 工具归到服务器类，其余按内置/技能/子代理，避免又列一遍完整清单。
 */
internal fun CapabilityEntry.domainLabel(): String = when (kind) {
    CapabilityEntry.Kind.SKILL -> "skills"
    CapabilityEntry.Kind.MCP -> "MCP servers"
    CapabilityEntry.Kind.TOOL -> when {
        id.startsWith("mcp__") -> "MCP servers"
        id.startsWith("ssh_") -> "SSH workspaces"
        id in WORKSPACE_TOOL_NAMES -> "workspace files and shell"
        id in SUB_AGENT_TOOL_NAMES -> "subagents"
        id.startsWith("memory_") -> "memory"
        else -> "built-in tools"
    }
}

private val WORKSPACE_TOOL_NAMES =
    setOf("read_file", "write_file", "edit_file", "shell", "shell_output", "shell_kill")

private val SUB_AGENT_TOOL_NAMES = setOf("task", "fleet", "read_subagent_result")
