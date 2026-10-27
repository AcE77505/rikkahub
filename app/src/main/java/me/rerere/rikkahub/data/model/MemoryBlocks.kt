package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

/**
 * 记忆正文的一个块。
 *
 * 列表项（`- ...`、`1. ...`）是 [Entry]，可以编辑、删除和拖动排序；
 * 标题、普通段落和空行是 [Raw]，只读保留，避免结构化编辑破坏原始 Markdown。
 */
sealed interface MemoryBlock {
    val id: String

    data class Entry(
        override val id: String = Uuid.random().toString(),
        /** 原始列表前缀，包含缩进，例如 `- `、`  * `、`1. ` */
        val prefix: String,
        /** 去掉列表前缀和 `[stated]` 标记后的内容，可能有多行续行 */
        val text: String,
        /** 原文是否带 `[stated]`；界面上隐藏，保存时补回 */
        val stated: Boolean,
    ) : MemoryBlock

    data class Raw(
        override val id: String = Uuid.random().toString(),
        val lines: List<String>,
    ) : MemoryBlock
}

private val LIST_ITEM = Regex("""^(\s*(?:[-*+]|\d+[.)])\s+)(.*)$""")
private const val STATED_PREFIX = "[stated] "

/** 把正文拆成「列表项 + 只读块」；空行会留在只读块里，拼回去时保持原始换行 */
fun parseMemoryBlocks(body: String): List<MemoryBlock> {
    val lines = body.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<MemoryBlock>()
    var i = 0
    while (i < lines.size) {
        val match = LIST_ITEM.find(lines[i])
        if (match != null) {
            val prefix = match.groupValues[1]
            val rawFirst = match.groupValues[2]
            val stated = rawFirst.startsWith(STATED_PREFIX)
            val first = if (stated) rawFirst.removePrefix(STATED_PREFIX) else rawFirst
            val content = mutableListOf(first)
            i++
            // 缩进续行属于同一个条目；空行或下一个列表项结束它
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank() || LIST_ITEM.containsMatchIn(next)) break
                if (!next.startsWith(" ") && !next.startsWith("\t")) break
                content += next
                i++
            }
            blocks += MemoryBlock.Entry(
                prefix = prefix,
                text = content.joinToString("\n"),
                stated = stated,
            )
        } else {
            val start = i
            while (i < lines.size && !LIST_ITEM.containsMatchIn(lines[i])) i++
            blocks += MemoryBlock.Raw(lines = lines.subList(start, i).toList())
        }
    }
    return blocks
}

/** 把块拼回正文；[MemoryBlock.Entry] 的前缀和 `[stated]` 标记会原样补回 */
fun joinMemoryBlocks(blocks: List<MemoryBlock>): String =
    blocks.joinToString("\n") { block ->
        when (block) {
            is MemoryBlock.Entry -> {
                val lines = block.text.split("\n")
                val first = block.prefix + (if (block.stated) STATED_PREFIX else "") + lines.first()
                (listOf(first) + lines.drop(1)).joinToString("\n")
            }

            is MemoryBlock.Raw -> block.lines.joinToString("\n")
        }
    }
