package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class DeepSeekImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun zip(conversationsJson: String): java.io.File {
        val file = temporaryFolder.newFile("deepseek-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.writeEntry("user.json", """{"user_id":"u1"}""".encodeToByteArray())
            zip.writeEntry("conversations.json", conversationsJson.encodeToByteArray())
        }
        return file
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun conversation(
        id: String,
        mapping: String,
        title: String = "Conversation",
    ): String = """
        [{
          "id": "$id",
          "title": "$title",
          "inserted_at": "2025-01-26T20:20:33.713000+08:00",
          "updated_at": "2025-01-26T20:20:38.677000+08:00",
          "mapping": { $mapping }
        }]
    """.trimIndent()

    private fun request(content: String) = """{"type": "REQUEST", "content": "$content"}"""

    private fun response(content: String) = """{"type": "RESPONSE", "content": "$content"}"""

    private fun node(
        key: String,
        parent: String?,
        children: List<String>,
        fragments: List<String> = emptyList(),
    ): String {
        val childrenJson = children.joinToString(", ") { "\"$it\"" }
        val parentJson = parent?.let { "\"$it\"" } ?: "null"
        val messageJson = if (fragments.isEmpty()) {
            "null"
        } else {
            """{
              "model": "deepseek-chat",
              "inserted_at": "2025-01-26T20:20:34.425000+08:00",
              "fragments": [${fragments.joinToString(", ")}]
            }"""
        }
        return """"$key": {"id": "$key", "parent": $parentJson, "children": [$childrenJson], "message": $messageJson}"""
    }

    private suspend fun import(file: java.io.File): Pair<DeepSeekImportResult, List<Conversation>> {
        val conversations = mutableListOf<Conversation>()
        val result = DeepSeekImporter.importStreaming(
            file = file,
            assistantId = Uuid.random(),
            onConversation = { conversation, _ -> conversations += conversation },
        )
        return result to conversations
    }

    @Test
    fun `maps fragments onto roles parts citations and search tool`() = runBlocking {
        val file = zip(
            conversation(
                id = "conv-1",
                mapping = listOf(
                    node("root", null, listOf("1")),
                    node(
                        "1",
                        "root",
                        listOf("2"),
                        listOf(
                            request("帮我看下这个文件"),
                            """{"type": "FILE", "files": [{"file_id": "f1", "file_name": "a.png", "file_size": 2048}]}""",
                        ),
                    ),
                    node(
                        "2",
                        "1",
                        emptyList(),
                        listOf(
                            """{"type": "THINK", "content": "先分析一下"}""",
                            """{"type": "SEARCH", "results": [{"url": "https://a.com/doc", "title": "A"}]}""",
                            """{"type": "RESPONSE", "content": "这是回答[citation:1]"}""",
                        ),
                    ),
                ).joinToString(", "),
            )
        )

        val (result, conversations) = import(file)
        assertEquals(1, result.parsedConversations)
        assertEquals(0, result.skippedConversations)
        assertEquals(1, result.reasoningParts)
        assertEquals(1, result.searchParts)
        assertEquals(1, result.attachmentParts)

        val conversation = conversations.single()
        assertEquals("Conversation", conversation.title)
        assertEquals(2, conversation.messageNodes.size)

        val userMessage = conversation.messageNodes[0].currentMessage
        assertEquals(MessageRole.USER, userMessage.role)
        assertTrue(userMessage.parts.filterIsInstance<UIMessagePart.Text>().any { it.text == "帮我看下这个文件" })
        assertTrue(
            userMessage.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("[Attachment] a.png") }
        )

        val assistantMessage = conversation.messageNodes[1].currentMessage
        assertEquals(MessageRole.ASSISTANT, assistantMessage.role)
        assertTrue(assistantMessage.parts.first() is UIMessagePart.Reasoning)
        assertEquals("先分析一下", (assistantMessage.parts.first() as UIMessagePart.Reasoning).reasoning)

        val searchTool = assistantMessage.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("search_web", searchTool.toolName)
        assertTrue("input=${searchTool.input}", searchTool.input.contains("帮我看下这个文件"))
        val output = searchTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(output.contains("\"url\":\"https://a.com/doc\""))
        assertTrue(output.contains("\"id\":1"))

        val answer = assistantMessage.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertEquals("这是回答[citation,a.com](1)", answer)
    }

    @Test
    fun `regenerate branches become alternatives of one reply node`() = runBlocking {
        val file = zip(
            conversation(
                id = "conv-2",
                mapping = listOf(
                    node("root", null, listOf("1")),
                    node("1", "root", listOf("2", "3"), listOf(request("问题"))),
                    node("2", "1", emptyList(), listOf(response("回答 A"))),
                    node("3", "1", emptyList(), listOf(response("回答 B"))),
                ).joinToString(", "),
            )
        )

        val (result, conversations) = import(file)
        val conversation = conversations.single()

        assertEquals(2, conversation.messageNodes.size)
        assertEquals(1, conversation.messageNodes[0].messages.size)
        assertEquals(2, conversation.messageNodes[1].messages.size)
        assertEquals(0, conversation.messageNodes[1].selectIndex)
        assertEquals(1, result.alternativeReplies)
        assertEquals(0, result.branchMessages)
    }

    @Test
    fun `edited prompt branches are kept as trailing nodes`() = runBlocking {
        val file = zip(
            conversation(
                id = "conv-3",
                mapping = listOf(
                    node("root", null, listOf("1", "3")),
                    node("1", "root", listOf("2"), listOf(request("第一个问题"))),
                    node("2", "1", emptyList(), listOf(response("第一个回答"))),
                    node("3", "root", listOf("4"), listOf(request("改写后的问题"))),
                    node("4", "3", emptyList(), listOf(response("改写后的回答"))),
                ).joinToString(", "),
            )
        )

        val (result, conversations) = import(file)
        val conversation = conversations.single()

        assertEquals(4, conversation.messageNodes.size)
        assertEquals(2, result.branchMessages)
        val texts = conversation.messageNodes.flatMap { node ->
            node.currentMessage.parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        }
        assertTrue(texts.contains("第一个问题"))
        assertTrue(texts.contains("第一个回答"))
        assertTrue(texts.contains("改写后的问题"))
        assertTrue(texts.contains("改写后的回答"))
    }

    @Test
    fun `conversation timestamps and stable ids survive reimport`() = runBlocking {
        val json = conversation(
            id = "conv-4",
            mapping = listOf(
                node("root", null, listOf("1")),
                node("1", "root", emptyList(), listOf(request("你好"))),
            ).joinToString(", "),
        )

        val (_, first) = import(zip(json))
        val (_, second) = import(zip(json))

        assertEquals(first.single().id, second.single().id)
        assertEquals(java.time.Instant.parse("2025-01-26T12:20:33.713Z"), first.single().createAt)
        assertEquals(java.time.Instant.parse("2025-01-26T12:20:38.677Z"), first.single().updateAt)
    }

    @Test
    fun `existing conversations follow the decisions and record source time`() = runBlocking {
        val mapping = listOf(
            node("root", null, listOf("1")),
            node("1", "root", emptyList(), listOf(request("hi"))),
        ).joinToString(", ")
        val json = "[" +
            conversation(id = "conv-a", title = "keep-me", mapping = mapping).removeSurrounding("[", "]") + "," +
            conversation(id = "conv-b", title = "overwrite-me", mapping = mapping).removeSurrounding("[", "]") + "," +
            conversation(id = "conv-c", title = "copy-me", mapping = mapping).removeSurrounding("[", "]") +
            "]"
        val file = zip(json)

        val states = DeepSeekImporter.scan(file).associateBy { it.rawId }
        val existingIds = states.values.map { it.conversationId }.toSet()
        val decisions = mapOf(
            states.getValue("conv-a").conversationId to DeepSeekImportDecision.KEEP,
            states.getValue("conv-b").conversationId to DeepSeekImportDecision.OVERWRITE,
            states.getValue("conv-c").conversationId to DeepSeekImportDecision.KEEP_BOTH,
        )

        val imported = mutableListOf<Pair<Conversation, DeepSeekImportAction>>()
        val result = DeepSeekImporter.importStreaming(
            file = file,
            assistantId = Uuid.random(),
            decisions = decisions,
            existingIds = existingIds,
            onConversation = { conversation, action -> imported += conversation to action },
        )

        assertEquals(1, result.keptExistingConversations)
        assertEquals(0, result.newConversations)
        assertEquals(1, result.overwrittenConversations)
        assertEquals(1, result.copiedConversations)
        assertEquals(2, imported.size)

        val kept = imported.single { it.second == DeepSeekImportAction.OVERWRITE }.first
        assertEquals(states.getValue("conv-b").conversationId, kept.id)
        assertEquals("overwrite-me", kept.title)

        val copied = imported.single { it.second == DeepSeekImportAction.KEEP_BOTH }.first
        assertEquals("copy-me", copied.title.substringBefore(" (DeepSeek "))
        assertTrue(copied.id != states.getValue("conv-c").conversationId)
        assertEquals(kept.sourceUpdatedAt, states.getValue("conv-b").updatedAt)
        assertEquals(copied.sourceUpdatedAt, states.getValue("conv-c").updatedAt)
    }

    @Test
    fun `scan reads ids titles and updated times without parsing mapping`() = runBlocking {
        val file = zip(
            conversation(
                id = "conv-scan",
                title = "Scan me",
                mapping = node("root", null, emptyList()),
            )
        )

        val summary = DeepSeekImporter.scan(file).single()
        assertEquals("conv-scan", summary.rawId)
        assertEquals("Scan me", summary.title)
        assertEquals(
            java.time.OffsetDateTime.parse("2025-01-26T20:20:38.677000+08:00").toInstant().toEpochMilli(),
            summary.updatedAt,
        )
    }

    @Test
    fun `unreadable conversations are skipped without failing the import`() = runBlocking {
        val json = """
            [
              {"id": "no-mapping", "title": "A"},
              {"id": "empty-mapping", "title": "B", "mapping": {}},
              {"id": "ok", "title": "C", "mapping": {
                "root": {"id": "root", "parent": null, "children": ["1"], "message": null},
                "1": {"id": "1", "parent": "root", "children": [], "message": {"fragments": [
                  {"type": "REQUEST", "content": "hi"}
                ]}}
              }}
            ]
        """.trimIndent()

        val (result, conversations) = import(zip(json))
        assertEquals(1, result.parsedConversations)
        assertEquals(2, result.skippedConversations)
        assertEquals("C", conversations.single().title)
    }

    @Test
    fun `export must be a top level array`() = runBlocking {
        val failure = runCatching { import(zip("""{"conversations":[]}""")) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }
}
