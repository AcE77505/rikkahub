package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathLinkifyTest {
    @Test
    fun `inline code path becomes a link`() {
        val result = linkifyPaths("已写入 `\u002Fsdcard/a.txt`，请查看。")
        assertTrue(result.contains("[\u002Fsdcard/a.txt]($PATH_LINK_SCHEME"))
    }

    @Test
    fun `plain absolute path becomes a link`() {
        val result = linkifyPaths("文件在 /data/user/0/app/files/a.txt")
        assertTrue(result.contains("($PATH_LINK_SCHEME"))
    }

    @Test
    fun `relative and home paths become links`() {
        assertTrue(linkifyPaths("见 ./src/Main.kt").contains(PATH_LINK_SCHEME))
        assertTrue(linkifyPaths("见 ../build/out").contains(PATH_LINK_SCHEME))
        assertTrue(linkifyPaths("见 ~/notes.md").contains(PATH_LINK_SCHEME))
    }

    @Test
    fun `paths with spaces stay whole`() {
        val path = "/storage/9A58-0A02/Ringtones/HOYO-MiX - 泊船入梦 Moored in Dreams.flac"
        assertTrue(linkifyPaths("音频在 `" + path + "`").contains("[" + path + "](" + PATH_LINK_SCHEME))
        assertTrue(linkifyPaths(path).contains("(" + PATH_LINK_SCHEME))
    }

    @Test
    fun `lone slash and chinese prose are not paths`() {
        // 「/」后面跟空格，是正文里的分隔符，不是路径
        assertFalse(linkifyPaths("/ 看它 hook 了哪些 PMS 方法），这一段").contains(PATH_LINK_SCHEME))
        assertFalse(linkifyPaths("（看它 hook 了哪些 PMS 方法），这一段").contains(PATH_LINK_SCHEME))
        // 中文后面紧跟斜杠也不算路径起点
        assertFalse(linkifyPaths("hook 了哪些 PMS 方法/说明").contains(PATH_LINK_SCHEME))
    }

    @Test
    fun `path before chinese comma stops at the comma`() {
        val result = linkifyPaths("文件在 /data/a.txt，请查看")
        assertTrue(result, result.contains("[/data/a.txt](" + PATH_LINK_SCHEME + "/data/a.txt)"))
    }

    @Test
    fun `code fences are untouched`() {
        val markdown = """
            ```
            /sdcard/should_not_link.txt
            ```
        """.trimIndent()
        assertEquals(markdown, linkifyPaths(markdown))
    }

    @Test
    fun `urls and ordinary text are untouched`() {
        assertEquals("访问 https://example.com/a/b 即可", linkifyPaths("访问 https://example.com/a/b 即可"))
        assertEquals("今天是 2026/11/08", linkifyPaths("今天是 2026/11/08"))
    }

    @Test
    fun `existing links are not nested`() {
        val markdown = "见 [文档](/sdcard/doc.md)"
        val result = linkifyPaths(markdown)
        assertEquals(markdown, result)
    }

    @Test
    fun `trailing punctuation stays outside the link`() {
        val result = linkifyPaths("路径是 /sdcard/a.txt。")
        assertTrue(result.contains("[\u002Fsdcard/a.txt]($PATH_LINK_SCHEME"))
        assertTrue(result.endsWith("。"))
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
