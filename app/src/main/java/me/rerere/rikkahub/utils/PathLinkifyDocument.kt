package me.rerere.rikkahub.utils

import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.MarkdownDocument
import me.rerere.bettertext.markdown.MarkdownInline

/**
 * 把已经解析好的 Markdown 文档里的路径换成内部链接节点。
 *
 * 只在内存里的节点树上做替换：`MarkdownDocument.source` 和消息正文都不变，所以复制、导出、Web 端
 * 拿到的永远是原始文本，不需要为 `rikkahub-path` 做任何清理。链接目标只用来让渲染层识别
 * 「这是一段可点击的路径」，不会出现在任何字符串里。
 */
internal fun MarkdownDocument.linkifyPaths(): MarkdownDocument =
    copy(blocks = blocks.map { it.linkifyPaths() })

private fun MarkdownBlock.linkifyPaths(): MarkdownBlock = when (this) {
    is MarkdownBlock.Paragraph -> copy(content = content.linkifyPaths())
    is MarkdownBlock.Heading -> copy(content = content.linkifyPaths())
    is MarkdownBlock.Quote -> copy(children = children.map { it.linkifyPaths() })
    is MarkdownBlock.ListBlock -> copy(
        items = items.map { item ->
            item.copy(children = item.children.map { it.linkifyPaths() })
        }
    )
    is MarkdownBlock.Table -> copy(
        header = header.map { it.copy(content = it.content.linkifyPaths()) },
        rows = rows.map { row -> row.map { it.copy(content = it.content.linkifyPaths()) } },
    )
    is MarkdownBlock.Details -> copy(
        summary = summary.linkifyPaths(),
        children = children.map { it.linkifyPaths() },
    )
    is MarkdownBlock.Container -> copy(children = children.map { it.linkifyPaths() })
    is MarkdownBlock.CodeBlock, MarkdownBlock.Divider -> this
}

private fun List<MarkdownInline>.linkifyPaths(): List<MarkdownInline> =
    flatMap { node ->
        when (node) {
            is MarkdownInline.Text -> node.linkifyPaths()
            // 样式节点内部仍可能是路径，保留样式包裹链接
            is MarkdownInline.Styled -> listOf(node.copy(children = node.children.linkifyPaths()))
            // 已有链接、行内代码、图片和公式保持原样
            is MarkdownInline.Link, is MarkdownInline.Code,
            is MarkdownInline.Image, is MarkdownInline.Math -> listOf(node)
        }
    }

private fun MarkdownInline.Text.linkifyPaths(): List<MarkdownInline> {
    val matches = PATH_PATTERN.findAll(text).toList()
    if (matches.isEmpty()) return listOf(this)

    val result = mutableListOf<MarkdownInline>()
    var cursor = 0
    for (match in matches) {
        val raw = match.value
        val trailing = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
        // 两个连续空格通常是路径后面的说明文字
        val candidate = raw.dropLast(trailing.length).substringBefore("  ").trimEnd()
        if (!looksLikeOpenablePath(candidate)) continue

        val start = match.range.first
        if (start > cursor) result += MarkdownInline.Text(text.substring(cursor, start))
        result += MarkdownInline.Link(
            destination = PATH_LINK_SCHEME + encodePath(candidate),
            children = listOf(MarkdownInline.Text(candidate)),
        )
        cursor = start + candidate.length
    }
    if (cursor == 0) return listOf(this)
    if (cursor < text.length) result += MarkdownInline.Text(text.substring(cursor))
    return result
}
