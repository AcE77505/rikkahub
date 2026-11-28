package me.rerere.ai.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isInlineUserPart

/**
 * 用户生成中途插话时，发给模型的文本前缀（取自 Reasonix 的做法）。
 *
 * 插话拆出来就是一条普通 user 消息，模型只能靠位置猜它是不是中途插入的；
 * 加上这一行，模型才知道这是当前任务的补充指引，而不是新任务或纠正。
 * 只加在发给模型的内容上：展示、导出和回放都用 [stripMidTurnSteerPrefix] 还原成用户实际说的话。
 */
const val MID_TURN_STEER_PREFIX: String =
    "[Mid-turn steer queued by the user. Do not treat this as a new task; " +
        "use it only as additional guidance for the current task after completing the current step.]"

/** 去掉插话前缀，还原成用户实际输入的内容；不是插话时原样返回 */
fun String.stripMidTurnSteerPrefix(): String =
    removePrefix(MID_TURN_STEER_PREFIX).removePrefix("\n")

/**
 * 把用户插话（[UIMessagePart.Steer]）从助手消息里拆出来，变成独立的 user 消息，放在它原来的位置。
 *
 * 插话要在输入转换器之前拆开：文档解析、OCR、模板、用户正则这些和普通用户消息一样要作用在它身上，
 * 否则插话里的附件会被静默丢掉。拆出来的消息不标 [UIMessage.isSynthetic]，
 * 因为它确实是用户说的话，应该走普通用户消息的整条处理链。
 *
 * 运行时事件（[UIMessagePart.Notice]）不在这里处理：它们不是用户发言，要在转换之后由
 * [expandNotices] 拆成 synthetic 消息，避免被套上模板和时间提醒。
 */
fun List<UIMessage>.extractSteers(): List<UIMessage> = flatMap { message ->
    if (message.parts.none { it is UIMessagePart.Steer }) {
        return@flatMap listOf(message)
    }
    buildList {
        val buffer = mutableListOf<UIMessagePart>()
        fun flush() {
            if (buffer.isNotEmpty()) add(message.copy(parts = buffer.toList()))
            buffer.clear()
        }
        message.parts.forEach { part ->
            if (part is UIMessagePart.Steer) {
                flush()
                add(
                    UIMessage(role = MessageRole.USER, parts = part.parts.markedAsMidTurnSteer())
                        // 用用户实际插话的时间，时间提醒据此算间隔；旧数据没有记录时退回助手消息的时间
                        .copy(
                            createdAt = part.createdAt ?: message.createdAt,
                            steerDiagnosticId = part.diagnosticId,
                        )
                )
            } else {
                buffer.add(part)
            }
        }
        flush()
    }
}

/**
 * 把消息里内联的用户输入（[UIMessagePart.Steer]）和运行时事件（[UIMessagePart.Notice]）
 * 在所在位置拆成 user 消息，组装请求时在最后调用。
 *
 * 助手消息里的这两类 part 都是这一轮中途取走的，只会出现在开头或一批工具执行完之后，拆开后和模型看到的轮次一致：
 * `[Tool, Notice, Text]` 变成 助手(tool_use) → user(tool_result) → user(notice) → 助手(text)。
 * 开始新的一轮时取走的 Notice 单独存成 user 消息（见 [UIMessage.isNoticeOnly]）。
 *
 * 插话正常应该已经被 [extractSteers] 在转换之前拆走；这里保留兜底，免得哪条组装路径漏了它，
 * 把插话原样发给 provider 而被忽略。
 *
 * 由调用方在组装请求时调用，不放进 agent 循环：压缩这类不经过 [runAgent] 的请求也要用，否则请求前缀不一致。
 */
fun List<UIMessage>.expandNotices(): List<UIMessage> = flatMap { message ->
    if (message.parts.none { it.isInlineUserPart() }) {
        return@flatMap listOf(message)
    }
    buildList {
        val buffer = mutableListOf<UIMessagePart>()
        fun flush() {
            if (buffer.isNotEmpty()) add(message.copy(parts = buffer.toList()))
            buffer.clear()
        }
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Notice -> {
                    flush()
                    add(UIMessage.user(part.text).copy(createdAt = message.createdAt, isSynthetic = true))
                }

                is UIMessagePart.Steer -> {
                    flush()
                    add(
                        UIMessage(role = MessageRole.USER, parts = part.parts.markedAsMidTurnSteer())
                            // 用用户实际插话的时间，时间提醒据此算间隔；旧数据没有记录时退回助手消息的时间
                            .copy(createdAt = part.createdAt ?: message.createdAt)
                    )
                }

                else -> buffer.add(part)
            }
        }
        flush()
    }
}

/**
 * 给插话的文本 part 加上 [MID_TURN_STEER_PREFIX]，非文本 part（附件）原样保留。
 * 只作用于发给模型的这份副本，会话里存的仍然是用户原话。
 */
private fun List<UIMessagePart>.markedAsMidTurnSteer(): List<UIMessagePart> = map { part ->
    if (part is UIMessagePart.Text) {
        part.copy(text = MID_TURN_STEER_PREFIX + "\n" + part.text)
    } else {
        part
    }
}
