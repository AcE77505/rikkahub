package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
sealed class ToolApprovalState {
    @Serializable
    @SerialName("auto")
    data object Auto : ToolApprovalState()

    @Serializable
    @SerialName("pending")
    data object Pending : ToolApprovalState()

    @Serializable
    @SerialName("approved")
    data object Approved : ToolApprovalState()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String = "") : ToolApprovalState()

    @Serializable
    @SerialName("answered")
    data class Answered(val answer: String) : ToolApprovalState()
}

fun ToolApprovalState.canResumeToolExecution(): Boolean {
    return when (this) {
        ToolApprovalState.Approved -> true
        is ToolApprovalState.Denied -> true
        is ToolApprovalState.Answered -> true
        ToolApprovalState.Auto,
        ToolApprovalState.Pending,
        -> false
    }
}

/**
 * 服务端工具调用的通用生命周期状态。
 *
 * Provider 返回的更细粒度状态应保存在 [UIMessagePart.ServerTool.metadata] 中，避免在通用层绑定具体协议。
 */
@Serializable
enum class ServerToolStatus {
    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,
}

/** The kind of text carried by a reasoning part. */
@Serializable
enum class ReasoningType {
    @SerialName("reasoning_text")
    REASONING_TEXT,

    @SerialName("summary_text")
    SUMMARY_TEXT,
}

@Serializable
sealed class UIMessagePart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override var metadata: JsonObject? = null,
        val reasoningType: ReasoningType = ReasoningType.REASONING_TEXT,
    ) : UIMessagePart()

    @Deprecated("Deprecated")
    @Serializable
    @SerialName("search")
    data object Search : UIMessagePart() {
        override var metadata: JsonObject? = null
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        fun merge(other: ToolCall): ToolCall {
            return ToolCall(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                arguments = arguments + other.arguments,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: JsonElement,
        val arguments: JsonElement,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    /**
     * 由 Provider 在服务端执行的工具调用，例如网页搜索、文件检索或代码执行。
     *
     * 与 [Tool] 不同，此类型只追踪调用及结果，不参与客户端审批或执行流程。[input] 和 [output]
     * 使用通用 JSON，以容纳不同 Provider、不同工具的协议结构；需要原样回传的 Provider 数据也应保留在其中。
     */
    @Serializable
    @SerialName("server_tool")
    data class ServerTool(
        val toolCallId: String,
        val toolName: String,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val status: ServerToolStatus,
        override var metadata: JsonObject? = null,
    ) : UIMessagePart() {
        val isFinished: Boolean
            get() = status == ServerToolStatus.COMPLETED || status == ServerToolStatus.FAILED
    }

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<UIMessagePart> = emptyList(),
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null,
        // 本地执行的起止时间，只统计真正运行工具的耗时；拒绝、问答和旧消息都为空
        val startedAt: Instant? = null,
        val finishedAt: Instant? = null,
    ) : UIMessagePart() {
        /** Whether the tool has been executed (has output) */
        val isExecuted: Boolean get() = output.isNotEmpty()

        /** Whether the tool is pending user approval */
        val isPending: Boolean get() = !isExecuted && approvalState is ToolApprovalState.Pending

        /** Whether generation can resume and handle this tool immediately */
        val canResumeExecution: Boolean get() = !isExecuted && approvalState.canResumeToolExecution()

        /** Parse input string as JsonElement */
        fun inputAsJson(): JsonElement = runCatching {
            json.parseToJsonElement(input.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }

        fun merge(other: Tool): Tool {
            return Tool(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                input = input + other.input,
                output = output + other.output,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
                startedAt = startedAt,
                finishedAt = finishedAt,
            )
        }
    }

    /**
     * 运行过程中要告诉模型的事件，例如后台命令结束。
     *
     * 生成中由 agent 循环在轮到模型回复时追加到助手消息里，开始新的一轮时单独存成一条 user 消息，
     * 和其它 part 一样保存在会话中；
     * 请求时由 [me.rerere.ai.agent.expandNotices] 在所在位置拆成一条 user 消息，前缀固定，可以命中提示词缓存。
     * [title] 只用于界面展示，[text] 是发给模型的内容。
     */
    @Serializable
    @SerialName("notice")
    data class Notice(
        val title: String,
        val text: String,
        override var metadata: JsonObject? = null,
    ) : UIMessagePart()

    /**
     * 用户在模型生成中途插的话。
     *
     * 与 [Notice] 走同一条路：生成中由 agent 循环在轮到模型回复时追加到助手消息里，
     * 之后和其它 part 一样保存在会话中；请求时由 [me.rerere.ai.agent.expandNotices] 在所在位置
     * 拆成一条 user 消息，模型看到的轮次与用户实际插话的时机一致，前缀也保持稳定。
     * 区别只在语义和展示：这是用户自己说的话，要按用户消息显示，而不是运行时事件。
     */
    @Serializable
    @SerialName("steer")
    data class Steer(
        /** 插话内容，和普通用户消息的 parts 一样，可以带附件 */
        val parts: List<UIMessagePart>,
        /** 用户实际发出插话的时间；拆成 user 消息时用它，而不是助手消息的创建时间 */
        val createdAt: LocalDateTime? = null,
        /** In-memory diagnostic correlation ID; null for historical and manually constructed steers. */
        @Transient
        val diagnosticId: Uuid? = null,
        override var metadata: JsonObject? = null,
    ) : UIMessagePart()
}

/**
 * 内联在助手消息里、请求组装时要拆成 user 消息的 part：用户插话和运行时事件。
 *
 * 二者的位置决定它在请求里插在哪（见 [me.rerere.ai.agent.expandNotices]），
 * 所以任何会重排 parts 的逻辑都要先看它。
 */
fun UIMessagePart?.isInlineUserPart(): Boolean =
    this is UIMessagePart.Notice || this is UIMessagePart.Steer
