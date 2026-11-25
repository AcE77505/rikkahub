package me.rerere.rikkahub.data.ai.tools.dynamic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSearchTest {

    // ---- 分词 ----

    @Test
    fun `cjk text is split into character bigrams`() {
        // CJK 与 ASCII 不跨边界拼词：中文段切二元组，拉丁段整体保留
        assertEquals(listOf("提个", "bug"), Tokenizer.tokenize("提个bug"))
        assertEquals(listOf("工具", "具检", "检索"), Tokenizer.tokenize("工具检索"))
    }

    @Test
    fun `single cjk character falls back to itself`() {
        // 单字回退，否则一个字的查询永远搜不到
        assertEquals(listOf("查"), Tokenizer.tokenize("查"))
    }

    @Test
    fun `camelCase is split at boundaries`() {
        val tokens = Tokenizer.tokenize("getAccessPolicy")
        assertTrue(tokens.containsAll(listOf("get", "access", "policy")))
    }

    @Test
    fun `full width characters are normalized`() {
        // 全角字母经 NFKC 归一后按普通 ASCII 词处理
        assertTrue(Tokenizer.tokenize("ＡＢＣ").contains("abc"))
    }

    // ---- BM25 ----

    private fun entry(
        id: String,
        description: String = "",
        prompt: String = "",
        aliases: List<String> = emptyList(),
    ) = CapabilityEntry(
        id = id,
        kind = CapabilityEntry.Kind.TOOL,
        name = id,
        description = description,
        systemPrompt = prompt,
        aliases = aliases,
    )

    @Test
    fun `exact id wins outright`() {
        val entries = listOf(
            entry("read_file", "read a file"),
            entry("write_file", "write a file"),
        )
        val result = Bm25.search(entries, "read_file")
        assertEquals(listOf("read_file"), result.map { it.id })
    }

    @Test
    fun `alias matches by exact name`() {
        val entries = listOf(entry("read_file", "read a file", aliases = listOf("workspace_read_file")))
        val result = Bm25.search(entries, "workspace_read_file")
        assertEquals(listOf("read_file"), result.map { it.id })
    }

    @Test
    fun `chinese query matches chinese description`() {
        val entries = listOf(
            entry("search_web", "搜索网页上的最新信息"),
            entry("read_file", "读取本地文件内容"),
        )
        val result = Bm25.search(entries, "搜索网上的信息")
        assertEquals("search_web", result.first().id)
    }

    @Test
    fun `system prompt participates in the index`() {
        // 这是相对 Open WebUI 的扩展：用户写在工具提示词里的词也要能搜到
        val entries = listOf(
            entry("shell", "run commands", prompt = "obscura 无头浏览器 截图"),
            entry("read_file", "read a file"),
        )
        val result = Bm25.search(entries, "无头浏览器")
        assertEquals("shell", result.first().id)
    }

    @Test
    fun `idf zero does not hide matches in a small corpus`() {
        // 只有两条文档，且查询词在两份里都出现：BM25 的 IDF 归零，靠原始词重叠兜底
        val entries = listOf(
            entry("a", "shared keyword alpha"),
            entry("b", "shared keyword beta"),
        )
        val result = Bm25.search(entries, "shared keyword")
        assertEquals(2, result.size)
    }

    @Test
    fun `no match returns empty list`() {
        val entries = listOf(entry("read_file", "read a file"))
        assertTrue(Bm25.search(entries, "zzzz-not-present").isEmpty())
    }

    // ---- defer 策略 ----

    @Test
    fun `glob whitelist matches using star and question mark`() {
        assertTrue(DeferPolicy.globMatches("mcp__mt__*", "mcp__mt__search"))
        assertTrue(DeferPolicy.globMatches("read_fil?", "read_file"))
        assertTrue(!DeferPolicy.globMatches("mcp__mt__*", "read_file"))
    }

    @Test
    fun `only mcp tools are deferred`() {
        val mcp = me.rerere.ai.core.Tool(
            name = "mcp__mt__search",
            description = "search files",
            execute = { emptyList() },
        )
        val builtin = me.rerere.ai.core.Tool(
            name = "read_file",
            description = "x".repeat(5000),
            execute = { emptyList() },
        )
        val deferred = DeferPolicy.selectDeferred(listOf(mcp, builtin))
        assertEquals(listOf("mcp__mt__search"), deferred.map { it.name })
    }

    @Test
    fun `whitelisted mcp tools stay resident`() {
        val whitelisted = me.rerere.ai.core.Tool(
            name = "mcp__mt__search",
            description = "",
            execute = { emptyList() },
        )
        val other = me.rerere.ai.core.Tool(
            name = "mcp__xhs__list",
            description = "",
            execute = { emptyList() },
        )
        val deferred = DeferPolicy.selectDeferred(
            listOf(whitelisted, other),
            alwaysLoaded = listOf("mcp__mt__*"),
        )
        assertEquals(listOf("mcp__xhs__list"), deferred.map { it.name })
    }
}
