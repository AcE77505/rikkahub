package me.rerere.rikkahub.data.ai.tools.dynamic

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool

/**
 * 动态工具目录里的一条能力。
 *
 * 工具、技能、MCP 工具共用这一个模型：检索只关心 [searchText]，而执行时按 [kind] 分流。
 * 状态来自 host 观察（不是模型猜的），避免模型对未连接的 MCP 服务器盲目调用。
 */
data class CapabilityEntry(
    /** 唯一标识，也是 search_tools 返回给模型的名字 */
    val id: String,
    val kind: Kind,
    /** 展示用名称 */
    val name: String,
    val description: String,
    /** 工具自带的系统提示词；技能为 SKILL.md 摘要；MCP 为空 */
    val systemPrompt: String = "",
    /** 别名（旧工具名），检索和按名查找都要认 */
    val aliases: List<String> = emptyList(),
    /** 参数名，参与检索；用于「按参数语义」找工具 */
    val parameterNames: List<String> = emptyList(),
    /** 实际调用的工具对象；技能没有可执行本体，为 null */
    val tool: Tool? = null,
    /** 运行时状态，进检索结果的提示 */
    val status: Status = Status.Ready,
    /** 失败原因，仅 status != Ready 时有意义 */
    val failureReason: String? = null,
) {
    enum class Kind { TOOL, SKILL, MCP }

    enum class Status {
        Ready,

        /** 已配置但尚未连接（MCP） */
        NotConnected,

        /** 连接出错或需要授权 */
        Failed,
    }

    /** 参与 BM25 检索的全部文本，构建一次后复用 */
    val searchText: String by lazy {
        buildString {
            append(name)
            append(' ')
            append(id)
            aliases.forEach { append(' ').append(it) }
            append(' ')
            append(description)
            append(' ')
            append(systemPrompt)
            parameterNames.forEach { append(' ').append(it) }
        }
    }
}

/** 一次检索的结果：命中的条目 + 被省略的数量（供提示词说明「还有更多」） */
internal data class CapabilitySearchResult(
    val entries: List<CapabilityEntry>,
    val totalDeferred: Int,
)

/** 参数 schema 里的属性名；工具没有 schema 时返回空 */
fun Tool.parameterNames(): List<String> =
    when (val schema = parameters()) {
        is me.rerere.ai.core.InputSchema.Obj -> (schema.properties as JsonObject).keys.toList()
        null -> emptyList()
    }
