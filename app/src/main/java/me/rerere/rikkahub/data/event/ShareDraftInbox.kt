package me.rerere.rikkahub.data.event

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * 分享内容「投递到当前对话」的一次性收件箱。
 *
 * 分享进来时如果 RouteActivity 当前栈顶就是一个聊天页，就不再新建会话，而是把内容写进这里、
 * 指向那个会话 id；对应 ChatPage 订阅后消费并注入输入框（预备上传，不自动发送）。
 * 栈顶不是聊天页时才回退到新建会话。
 */
data class ShareDraft(
    val conversationId: Uuid,
    val text: String,
    val files: List<String>,
)

class ShareDraftInbox {
    private val _pending = MutableStateFlow<ShareDraft?>(null)
    val pending: StateFlow<ShareDraft?> = _pending.asStateFlow()

    /** 投递一份草稿；同一时刻只保留最新的这一份。 */
    fun offer(draft: ShareDraft) {
        _pending.value = draft
    }

    /**
     * 消费与该会话匹配的草稿。匹配并消费成功返回草稿，否则返回 null。
     * 消费后清空，保证同一份分享只被注入一次。
     */
    fun consume(conversationId: Uuid): ShareDraft? {
        val current = _pending.value ?: return null
        if (current.conversationId != conversationId) return null
        _pending.value = null
        return current
    }
}
