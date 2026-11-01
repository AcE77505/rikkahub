package me.rerere.rikkahub.data.ai.subagent

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 当前对话内已经批准过的子代理。
 *
 * 用户批准一次 `task` 派发后，同一对话里再次派发同一个子代理不再要求审批；
 * 只在内存里保存，应用重启或删除对话后失效。
 */
class SubAgentApprovalRegistry {
    private val approved = ConcurrentHashMap<String, MutableSet<String>>()

    fun markApproved(conversationId: Uuid, subAgentId: Uuid) {
        approved.computeIfAbsent(conversationId.toString()) { ConcurrentHashMap.newKeySet() }
            .add(subAgentId.toString())
    }

    fun isApproved(conversationId: Uuid, subAgentId: Uuid): Boolean =
        approved[conversationId.toString()]?.contains(subAgentId.toString()) == true

    fun clear(conversationId: Uuid) {
        approved.remove(conversationId.toString())
    }
}
