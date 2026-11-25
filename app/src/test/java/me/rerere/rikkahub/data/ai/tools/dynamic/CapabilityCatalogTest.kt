package me.rerere.rikkahub.data.ai.tools.dynamic

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCatalogTest {

    private fun tool(name: String, description: String = "") = Tool(
        name = name,
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("path", buildJsonObject { put("type", "string") }) },
            )
        },
        execute = { emptyList() },
    )

    @Test
    fun `mcp tools are classified by name prefix`() {
        val entries = CapabilityCatalog.build(
            tools = listOf(tool("mcp__mt__search"), tool("read_file")),
        )
        assertEquals(CapabilityEntry.Kind.MCP, entries.first { it.id == "mcp__mt__search" }.kind)
        assertEquals(CapabilityEntry.Kind.TOOL, entries.first { it.id == "read_file" }.kind)
    }

    @Test
    fun `mcp status marks not connected and failed servers`() {
        val entries = CapabilityCatalog.build(
            tools = listOf(tool("mcp__mt__search"), tool("mcp__xhs__list"), tool("mcp__ok__ping")),
            mcpStatus = mapOf(
                "mt" to McpStatus.Idle,
                "xhs" to McpStatus.Error(message = "auth required"),
                "ok" to McpStatus.Connected,
            ),
        )
        assertEquals(CapabilityEntry.Status.NotConnected, entries.first { it.id == "mcp__mt__search" }.status)
        val failed = entries.first { it.id == "mcp__xhs__list" }
        assertEquals(CapabilityEntry.Status.Failed, failed.status)
        assertEquals("auth required", failed.failureReason)
        assertEquals(CapabilityEntry.Status.Ready, entries.first { it.id == "mcp__ok__ping" }.status)
    }

    @Test
    fun `prompt overlays replace description and feed the search text`() {
        val entries = CapabilityCatalog.build(
            tools = listOf(tool("shell", description = "Run a command.")),
            overlays = PromptOverlays(
                descriptions = mapOf("shell" to "无头浏览器 截图"),
                systemPrompts = mapOf("shell" to "obscura fetch"),
            ),
        )
        val shell = entries.first { it.id == "shell" }
        assertEquals("无头浏览器 截图", shell.description)
        assertTrue("覆盖后的提示词要进入检索文本", shell.searchText.contains("obscura"))
    }

    @Test
    fun `alias is searchable and resolvable`() {
        val entries = CapabilityCatalog.build(
            tools = listOf(tool("read_file", "read a file")),
            aliases = mapOf("workspace_read_file" to "read_file"),
        )
        val readFile = entries.first { it.id == "read_file" }
        assertEquals(listOf("workspace_read_file"), readFile.aliases)
        assertTrue(readFile.searchText.contains("workspace_read_file"))
        assertEquals("read_file", Bm25.search(entries, "workspace_read_file").single().id)
    }

    @Test
    fun `deferred domains are coarse labels`() {
        val d = DynamicTools(
            tools = emptyList(),
            entries = listOf(
                CapabilityEntry("mcp__mt__search", CapabilityEntry.Kind.MCP, "mcp__mt__search", ""),
                CapabilityEntry("read_file", CapabilityEntry.Kind.TOOL, "read_file", ""),
            ),
            deferredIds = setOf("mcp__mt__search", "read_file"),
        )
        assertEquals(listOf("MCP servers", "workspace files and shell"), d.deferredDomains)
    }

    @Test
    fun `route block is empty when nothing matches`() {
        val entries = CapabilityCatalog.build(
            tools = listOf(tool("read_file", "read a file")),
        )
        val d = DynamicTools(entries = entries, tools = emptyList(), deferredIds = setOf("read_file"))
        assertEquals("", d.renderRoute("zzz-no-match"))
        assertTrue(d.renderRoute("read a file").contains("<capability-route>"))
    }

    @Test
    fun `route block surfaces mcp connection state`() {
        val d = DynamicTools(
            tools = emptyList(),
            entries = listOf(
                CapabilityEntry(
                    id = "mcp__mt__search",
                    kind = CapabilityEntry.Kind.MCP,
                    name = "mcp__mt__search",
                    description = "find files",
                    status = CapabilityEntry.Status.NotConnected,
                ),
            ),
            deferredIds = setOf("mcp__mt__search"),
        )
        val block = d.renderRoute("find files")
        assertTrue(block.contains("not connected"))
        assertFalse("Ready 状态不该打标注", block.contains("Ready"))
    }
}
