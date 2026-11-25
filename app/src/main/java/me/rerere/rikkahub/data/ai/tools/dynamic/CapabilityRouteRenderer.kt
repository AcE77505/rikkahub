package me.rerere.rikkahub.data.ai.tools.dynamic

/**
 * 把「本轮相关的延迟工具」渲染成一小段提示，注入 system prompt 的**末尾**。
 *
 * 为什么必须放末尾：这一段每轮都会变，放在前面会让整段 system prompt 前缀失效，
 * 直接毁掉 prompt cache（OpenAI 与 Anthropic 的文档都强调把变动内容追加到末尾）。
 *
 * 为什么需要它：模型手里只有 `search_tools`，其余工具对它而言不存在。若用户描述含糊
 * 或根本没提工具需求，模型没有任何理由主动搜索。这里把 BM25 命中的少量候选直接摆到它眼前，
 * **不依赖模型的主动性**（见 docs/references/capability-tool-search-research.md 5.5.1）。
 */
internal object CapabilityRouteRenderer {

    /** 注入块最多列几条：太多又会变成提示词膨胀 */
    const val MAX_CANDIDATES = 5

    /**
     * @return 注入文本；没有命中时返回空串，调用方据此跳过注入（不能出现空壳块）
     */
    fun render(
        entries: List<CapabilityEntry>,
        query: String,
        limit: Int = MAX_CANDIDATES,
    ): String {
        if (entries.isEmpty() || query.isBlank()) return ""
        val hits = Bm25.search(entries, query, limit)
        if (hits.isEmpty()) return ""
        return hits.joinToString(
            separator = "\n",
            prefix = "<capability-route>\n" +
                "These tools are not in your tool list; call search_tools to see a definition, " +
                "then call_tool to run it. Ignore them if they are not relevant.\n",
            postfix = "\n</capability-route>",
        ) { entry -> "- ${entry.describeForRoute()}" }
    }

    private fun CapabilityEntry.describeForRoute(): String = buildString {
        append(id)
        append(": ")
        append(routeSummary())
        append(" (suggest)")
        when (status) {
            CapabilityEntry.Status.Ready -> Unit
            CapabilityEntry.Status.NotConnected -> append(" [not connected; it will connect on first call]")
            CapabilityEntry.Status.Failed -> {
                append(" [unavailable")
                failureReason?.let { append(": ").append(it.take(FAILURE_REASON_CHARS)) }
                append(']')
            }
        }
    }

    /** 取描述首句，避免把整段描述塞进注入块 */
    private fun CapabilityEntry.routeSummary(): String {
        val text = description.ifBlank { systemPrompt }.replace('\n', ' ').trim()
        if (text.isEmpty()) return name
        val firstSentence = text.substringBefore('.').substringBefore('。')
        return firstSentence.take(SUMMARY_CHARS).ifBlank { text.take(SUMMARY_CHARS) }
    }

    private const val SUMMARY_CHARS = 120
    private const val FAILURE_REASON_CHARS = 80
}
