package me.rerere.ai.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 当前工具调用的上下文，供工具在执行期间把进度、日志或 UI 状态关联回具体的 toolCallId。
 *
 * AgentLoop 在执行工具前把它放进协程上下文，工具内部通过
 * `coroutineContext[ToolCallContext]` 读取。
 */
data class ToolCallContext(
    val toolCallId: String,
) : AbstractCoroutineContextElement(ToolCallContext) {
    companion object Key : CoroutineContext.Key<ToolCallContext>
}
