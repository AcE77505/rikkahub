package me.rerere.rikkahub.data.sync.importer

import kotlin.uuid.Uuid

/** 导入 App 端 DeepSeek 导出时的汇总统计。 */
data class DeepSeekImportResult(
    val parsedConversations: Int,
    val skippedConversations: Int,
    val newConversations: Int,
    val overwrittenConversations: Int,
    val copiedConversations: Int,
    val keptExistingConversations: Int,
    val reasoningParts: Int,
    val searchParts: Int,
    val attachmentParts: Int,
    val emptyMessages: Int,
    val branchMessages: Int,
    val alternativeReplies: Int,
    val skippedTipParts: Int,
    val skippedToolParts: Int,
    val unknownFragments: Int,
)

/** 扫描阶段得到的对话元信息，用来和本地记录比较是否需要用户决定。 */
data class DeepSeekConversationSummary(
    val conversationId: Uuid,
    val rawId: String,
    val title: String,
    val updatedAt: Long?,
)

/** 已存在对话的处理方式；没有决定的对话视为「保留」。 */
enum class DeepSeekImportDecision {
    KEEP,
    OVERWRITE,
    KEEP_BOTH,
}

/** 这次实际执行的动作，回调给调用方决定如何落库。 */
enum class DeepSeekImportAction {
    NEW,
    OVERWRITE,
    KEEP_BOTH,
}

/** 从 App 导出 ZIP 里读出的一个附件。 */
data class DeepSeekAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * 当前文件不是 DeepSeek App 端导出（缺少 manifest，或格式版本低于 3）。
 *
 * 网页版导出的 ZIP 只有 `conversations.json`，结构与此不同，必须让用户重新导出。
 */
class DeepSeekAppExportException(message: String) : IllegalArgumentException(message)
