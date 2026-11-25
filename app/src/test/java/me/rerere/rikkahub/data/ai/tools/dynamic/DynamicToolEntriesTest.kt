package me.rerere.rikkahub.data.ai.tools.dynamic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicToolEntriesTest {

    private fun entry(
        id: String,
        description: String = "",
        needsApproval: Boolean = false,
        executor: suspend () -> String = { "ok from $id" },
    ): CapabilityEntry {
        val tool = Tool(
            name = id,
            description = description,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject { put("text", buildJsonObject { put("type", "string") }) },
                    required = listOf("text"),
                )
            },
            needsApproval = { needsApproval },
            execute = { listOf(UIMessagePart.Text(executor())) },
        )
        return CapabilityEntry(
            id = id,
            kind = CapabilityEntry.Kind.TOOL,
            name = id,
            description = description,
            parameterNames = listOf("text"),
            tool = tool,
        )
    }

    private fun dynamic(vararg entries: CapabilityEntry): DynamicTools = DynamicTools(
        tools = emptyList(),
        entries = entries.toList(),
        deferredIds = entries.mapTo(LinkedHashSet()) { it.id },
    )

    private fun calls(vararg pairs: Pair<String, String>) = buildJsonObject {
        putJsonArray("calls") {
            pairs.forEach { (name, params) ->
                add(
                    buildJsonObject {
                        put("tool_name", name)
                        put("params", params)
                    }
                )
            }
        }
    }

    @Test
    fun `search returns matching tool with parameter names and invocation hint`() = kotlinx.coroutines.runBlocking {
        val d = dynamic(entry("read_file", "read a file from the workspace"))
        val search = createDynamicToolEntries(d).first { it.name == SEARCH_TOOLS_NAME }
        val output = search.execute(buildJsonObject { put("query", "read file") })
        val text = (output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("read_file"))
        assertTrue("应给出参数名供模型填参", text.contains("\"text\""))
        assertTrue(text.contains(CALL_TOOL_NAME))
    }

    @Test
    fun `search with no match says so and lists nothing`() = kotlinx.coroutines.runBlocking {
        val d = dynamic(entry("read_file", "read a file"))
        val search = createDynamicToolEntries(d).first { it.name == SEARCH_TOOLS_NAME }
        val output = search.execute(buildJsonObject { put("query", "zzz-none") })
        val text = (output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("No matching tools found"))
        assertTrue(text.contains("\"tools\":[]"))
    }

    @Test
    fun `call_tool executes each call and one failure does not stop the rest`() =
        kotlinx.coroutines.runBlocking {
            val good = entry("good_tool")
            val bad = CapabilityEntry(
                id = "bad_tool",
                kind = CapabilityEntry.Kind.TOOL,
                name = "bad_tool",
                description = "",
                tool = Tool(
                    name = "bad_tool",
                    description = "",
                    execute = { error("boom") },
                ),
            )
            val d = dynamic(good, bad)
            val callTool = createDynamicToolEntries(d).first { it.name == CALL_TOOL_NAME }
            val output = callTool.execute(calls("good_tool" to """{"text":"hi"}""", "bad_tool" to "{}"))
            val texts = output.filterIsInstance<UIMessagePart.Text>().map { it.text }
            assertTrue("成功的调用要有结果", texts.any { it.contains("ok from good_tool") })
            assertTrue("失败的调用要报错但不中断", texts.any { it.contains("bad_tool' failed") })
            assertEquals(2, texts.size)
        }

    @Test
    fun `call_tool rejects unknown tool and invalid params`() = kotlinx.coroutines.runBlocking {
        val d = dynamic(entry("read_file"))
        val callTool = createDynamicToolEntries(d).first { it.name == CALL_TOOL_NAME }
        val output = callTool.execute(calls("nope" to "{}", "read_file" to "not-json"))
        val texts = output.filterIsInstance<UIMessagePart.Text>().map { it.text }
        val joined = texts.joinToString(" | ")
        assertTrue(joined, texts.any { it.contains("is not an available capability") })
        assertTrue(joined, texts.any { it.contains("must be a JSON object") })
    }

    @Test
    fun `call_tool inherits approval from the target tool`() {
        val plain = entry("read_file")
        val guarded = entry("shell", needsApproval = true)

        val mixed = dynamic(plain, guarded)
        assertTrue(
            "任一目标工具需要审批时，call_tool 整体也要审批",
            mixed.hasPendingApproval(calls("read_file" to "{}", "shell" to """{"text":"ls"}""")),
        )

        val safe = dynamic(plain)
        assertFalse(
            "全部目标工具都不需要审批时不触发",
            safe.hasPendingApproval(calls("read_file" to "{}")),
        )
    }

    @Test
    fun `call_tool tells the model to use view_skill for skills`() = kotlinx.coroutines.runBlocking {
        val skill = CapabilityEntry(
            id = "research",
            kind = CapabilityEntry.Kind.SKILL,
            name = "research",
            description = "research a topic",
        )
        val d = dynamic(skill)
        val callTool = createDynamicToolEntries(d).first { it.name == CALL_TOOL_NAME }
        val output = callTool.execute(calls("research" to "{}"))
        val text = (output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("view_skill"))
    }

    @Test
    fun `empty calls array is rejected`() = kotlinx.coroutines.runBlocking {
        val d = dynamic(entry("read_file"))
        val callTool = createDynamicToolEntries(d).first { it.name == CALL_TOOL_NAME }
        val output = callTool.execute(buildJsonObject { putJsonArray("calls") { } })
        assertTrue((output.single() as UIMessagePart.Text).text.contains("non-empty calls array"))
    }
}
