package me.rerere.rikkahub.data.ai.tools.dynamic

import me.rerere.ai.core.Tool

/**
 * 决定哪些工具不进 `tools` 数组，改为按需检索。
 *
 * 按**类别**筛选，而不是按体积：MCP 工具全部 defer，其余工具始终常驻。
 *
 * 这样定的理由（对齐 Reasonix 已验证的做法）：
 * - 真正膨胀上下文的只有 MCP：一个服务器动辄几十个工具、上百 KB schema，且工具清单随服务器变化；
 * - 内置工具数量固定、schema 也小，逐个按字节判断收益低，还要多维护一个阈值设置项；
 * - 按前缀判断是确定性的，不依赖「多少字节算大」这种主观阈值。
 *
 * 注意这里**不**调用 `systemPrompt()`：那是 lambda，调用会产生开销，而且对技能工具会展开
 * 整个技能清单。system prompt 随工具一起注入，无法单独 defer。
 */
internal object DeferPolicy {

    /** MCP 工具的名字前缀；与服务端工具命名保持一致 */
    const val MCP_TOOL_PREFIX = "mcp__"

    /**
     * @param alwaysLoaded 通配符白名单，命中的工具即使属于 MCP 也保持常驻
     */
    fun selectDeferred(
        tools: List<Tool>,
        alwaysLoaded: List<String> = emptyList(),
    ): List<Tool> = tools.filter { tool ->
        tool.name.startsWith(MCP_TOOL_PREFIX) && !tool.matchesAny(alwaysLoaded)
    }

    fun Tool.matchesAny(patterns: List<String>): Boolean =
        patterns.any { pattern -> globMatches(pattern, name) }

    /**
     * 简化版 glob：只支持 `*` 与 `?`。
     *
     * 白名单是给用户手填的，不需要完整的字符类与转义语义；这样实现也便于写测试。
     */
    internal fun globMatches(pattern: String, value: String): Boolean {
        if (pattern == "*") return true
        val regex = buildString {
            append('^')
            pattern.forEach { ch ->
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append('$')
        }
        return Regex(regex).matches(value)
    }
}
