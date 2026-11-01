package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class SubAgentPhase {
    RUNNING,
    REASONING,
    RESPONDING,
    TOOL,
}

/** 子代理运行中的预览，不进入父对话上下文 */
data class SubAgentProgress(
    val phase: SubAgentPhase,
    val text: String? = null,
    val reasoning: String? = null,
    val toolName: String? = null,
    val elapsedMs: Long = 0,
)

/**
 * 按 toolCallId 保存运行中的子代理预览，供聊天卡片实时展示。
 *
 * 只是内存状态，工具调用结束后清理；持久化结果仍以 SubAgentStore 为准。
 */
class SubAgentProgressRegistry {
    private val _progress = MutableStateFlow<Map<String, SubAgentProgress>>(emptyMap())
    val progress: StateFlow<Map<String, SubAgentProgress>> = _progress

    fun update(toolCallId: String, progress: SubAgentProgress) {
        _progress.update { it + (toolCallId to progress) }
    }

    fun clear(toolCallId: String) {
        _progress.update { it - toolCallId }
    }
}
