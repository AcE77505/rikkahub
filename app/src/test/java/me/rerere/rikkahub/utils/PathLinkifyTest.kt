package me.rerere.rikkahub.utils

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.markdown.parseMarkdown
import me.rerere.bettertext.markdown.plainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PathLinkifyTest {
    /** 解析并 linkify 后的第一段内联内容。 */
    private fun paragraphOf(markdown: String): List<MarkdownInline> =
        parseMarkdown(markdown).linkifyPaths()
            .blocks.filterIsInstance<MarkdownBlock.Paragraph>()
            .first()
            .content

    private fun List<MarkdownInline>.links(): List<MarkdownInline.Link> =
        filterIsInstance<MarkdownInline.Link>()

    @Test
    fun `plain absolute path becomes a clickable node`() {
        val path = "/data/user/0/app/files/a.txt"
        val link = paragraphOf("文件在 $path").links().single()
        assertEquals(PATH_LINK_SCHEME + path, link.destination)
        assertEquals(listOf(MarkdownInline.Text(path)), link.children)
    }

    @Test
    fun `relative and home paths become links`() {
        listOf("./src/Main.kt", "../build/out", "~/notes.md").forEach { path ->
            val link = paragraphOf("见 $path").links().single()
            assertEquals(PATH_LINK_SCHEME + path, link.destination)
        }
    }

    @Test
    fun `paths with spaces stay whole`() {
        val path = "/storage/9A58-0A02/Ringtones/HOYO-MiX - 泊船入梦 Moored in Dreams.flac"
        val link = paragraphOf("音频在 $path").links().single()
        assertEquals(path, link.children.single().let { (it as MarkdownInline.Text).text })
    }

    @Test
    fun `trailing punctuation stays outside the link`() {
        val nodes = paragraphOf("路径是 /sdcard/a.txt。")
        assertEquals(PATH_LINK_SCHEME + "/sdcard/a.txt", nodes.links().single().destination)
        assertTrue(nodes.last() is MarkdownInline.Text && (nodes.last() as MarkdownInline.Text).text.contains("。"))
    }

    @Test
    fun `code blocks and inline code are untouched`() {
        val document = parseMarkdown(
            """
            ```
            /sdcard/should_not_link.txt
            ```
            """.trimIndent()
        ).linkifyPaths()
        val code = document.blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("/sdcard/should_not_link.txt", code.code)

        assertTrue(paragraphOf("`\u002Fsdcard/a.txt`").none { it is MarkdownInline.Link })
    }

    @Test
    fun `existing links are not nested`() {
        val link = paragraphOf("见 [文档](/sdcard/doc.md)").links().single()
        assertEquals("/sdcard/doc.md", link.destination)
    }

    @Test
    fun `styled path keeps its style`() {
        val styled = paragraphOf("**\u002Fsdcard/a.txt**")
            .filterIsInstance<MarkdownInline.Styled>()
            .single()
        assertTrue(styled.children.any { it is MarkdownInline.Link })
    }

    @Test
    fun `urls and ordinary text are untouched`() {
        assertEquals("访问 https://example.com/a/b 即可", paragraphOf("访问 https://example.com/a/b 即可").plainText())
        assertEquals("今天是 2026/11/08", paragraphOf("今天是 2026/11/08").plainText())
    }

    @Test
    fun `model written internal links are normalized`() {
        val path = "/data/user/0/me.rerere.rikkahub/files/workspaces/default/home"
        // 标签里带路径时保留标签
        assertEquals("HOME=$path", stripInternalPathLinks("[HOME=$path]($PATH_LINK_SCHEME$path)"))
        // 标签只是描述时用链接目标，避免地址丢失
        assertEquals(path, stripInternalPathLinks("[查看]($PATH_LINK_SCHEME$path)"))
    }

    @Test
    fun `bare internal scheme is removed`() {
        val path = "/sdcard/a.txt"
        assertEquals("路径 $path", stripInternalPathLinks("路径 rikkahub-path:$path"))
        assertEquals(path, stripInternalPathLinks("rikkahub-path=$path"))
    }

    @Test
    fun `message text parts are sanitized for copy and export`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("见 [查看]($PATH_LINK_SCHEME/sdcard/a.txt)"),
                UIMessagePart.Text("没有标记"),
            ),
        )
        val cleaned = message.withoutInternalPathLinks()
        assertEquals("见 /sdcard/a.txt", (cleaned.parts[0] as UIMessagePart.Text).text)
        assertEquals("没有标记", (cleaned.parts[1] as UIMessagePart.Text).text)
        // 没有内部标记时直接返回原对象，避免复制/导出时重建消息
        assertSame(cleaned, cleaned.withoutInternalPathLinks())
    }

    @Test
    fun `path encoding round trips`() {
        val path = "/sdcard/中文 目录/a(1).txt"
        assertEquals(path, decodePath(encodePath(path)))
        assertEquals(path, decodePathLink(PATH_LINK_SCHEME + encodePath(path)))
    }

    @Test
    fun `looks like path rejects prose`() {
        assertFalse(looksLikeOpenablePath("hello/world"))
        assertFalse(looksLikeOpenablePath("https://example.com/a"))
        assertFalse(looksLikeOpenablePath("/"))
        assertTrue(looksLikeOpenablePath("/sdcard"))
    }
}
