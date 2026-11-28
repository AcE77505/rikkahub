package me.rerere.ai.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isInlineUserPart

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
                    UIMessage(role = MessageRole.USER, parts = part.parts)
                        // 用用户实际插话的时间，时间提醒据此算间隔；旧数据没有记录时退回助手消息的时间
                        .copy(createdAt = part.createdAt ?: message.createdAt)
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
                        UIMessage(role = MessageRole.USER, parts = part.parts)
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
