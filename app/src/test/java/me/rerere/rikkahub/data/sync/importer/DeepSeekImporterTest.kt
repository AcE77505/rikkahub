package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class DeepSeekImporterTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `rejects a web export without manifest`() = runBlocking {
        val file = archive(mapOf("conversations.json" to "[]"))
        val error = runCatching { DeepSeekImporter.scan(file) }.exceptionOrNull()
        assertTrue(error is DeepSeekAppExportException)
    }

    @Test
    fun `rejects an old export format`() = runBlocking {
        val file = archive(
            mapOf(
                "manifest.json" to """{"export_format_version":2}""",
                "chat_sessions/s.json" to sessionJson(),
            )
        )
        val error = runCatching { DeepSeekImporter.scan(file) }.exceptionOrNull()
        assertTrue(error is DeepSeekAppExportException)
    }

    @Test
    fun `scans the app export index`() = runBlocking {
        val file = archive(
            mapOf(
                "manifest.json" to """{"export_format_version":3}""",
                "chat_session_index.json" to """
                    [{"id":"s1","title":"Title","pinned":false,
                      "inserted_at":"2026-11-17T00:00:00.000Z",
                      "updated_at":"2026-11-17T01:00:00.000Z"}]
                """.trimIndent(),
                "chat_sessions/s1.json" to sessionJson(),
            )
        )
        val summaries = DeepSeekImporter.scan(file)
        assertEquals(1, summaries.size)
        assertEquals("Title", summaries[0].title)
        assertTrue(summaries[0].updatedAt != null)
    }

    @Test
    fun `imports current branch plus alternative replies and branches`() = runBlocking {
        val json = """
            {
              "id":"s1","title":"Tree","pinned":false,"current_message_id":"m5",
              "inserted_at":"2026-11-17T00:00:00.000Z","updated_at":"2026-11-17T01:00:00.000Z",
              "messages":[
                {"id":"m1","parent_id":null,"role":"USER","inserted_at":"2026-11-17T00:00:01.000Z",
                 "fragments":[{"type":"REQUEST","content":"hello"}]},
                {"id":"m2","parent_id":"m1","role":"ASSISTANT","inserted_at":"2026-11-17T00:00:02.000Z",
                 "fragments":[{"type":"RESPONSE","content":"answer A"}]},
                {"id":"m3","parent_id":"m1","role":"ASSISTANT","inserted_at":"2026-11-17T00:00:03.000Z",
                 "fragments":[{"type":"RESPONSE","content":"answer B"}]},
                {"id":"m4","parent_id":"m2","role":"USER","inserted_at":"2026-11-17T00:00:04.000Z",
                 "fragments":[{"type":"REQUEST","content":"follow-up"}]},
                {"id":"m5","parent_id":"m4","role":"ASSISTANT","inserted_at":"2026-11-17T00:00:05.000Z",
                 "fragments":[{"type":"RESPONSE","content":"answer C"}]},
                {"id":"m6","parent_id":"m1","role":"USER","inserted_at":"2026-11-17T00:00:06.000Z",
                 "fragments":[{"type":"REQUEST","content":"other branch"}]},
                {"id":"m7","parent_id":"m6","role":"ASSISTANT","inserted_at":"2026-11-17T00:00:07.000Z",
                 "fragments":[{"type":"RESPONSE","content":"other answer"}]}
              ]
            }
        """.trimIndent()
        val file = archive(
            mapOf(
                "manifest.json" to """{"export_format_version":3}""",
                "chat_sessions/s1.json" to json,
            )
        )
        var imported: me.rerere.rikkahub.data.model.Conversation? = null
        val result = DeepSeekImporter.importStreaming(
            file = file,
            assistantId = Uuid.random(),
        ) { conversation, _ -> imported = conversation }

        assertEquals(1, result.newConversations)
        assertEquals(2, result.branchMessages)
        assertEquals(1, result.alternativeReplies)
        val nodes = requireNotNull(imported).messageNodes
        // m1, m2/m3 候选, m4, m5, 然后 m6/m7 作为分支
        assertEquals(6, nodes.size)
        assertEquals(2, nodes[1].messages.size)
        assertEquals(MessageRole.ASSISTANT, nodes[1].messages[0].role)
    }

    @Test
    fun `imports attachments and keeps a placeholder for a missing file`() = runBlocking {
        val json = """
            {
              "id":"s1","title":"Files","current_message_id":"m2",
              "inserted_at":"2026-11-17T00:00:00.000Z","updated_at":"2026-11-17T01:00:00.000Z",
              "messages":[
                {"id":"m1","parent_id":null,"role":"USER","inserted_at":"2026-11-17T00:00:01.000Z",
                 "fragments":[{"type":"FILE","files":[
                   {"file_name":"a.png","export_path":"chat_sessions/s1_files/a.png","is_image":true},
                   {"file_name":"b.txt","export_path":"chat_sessions/s1_files/missing.txt","is_image":false}
                 ]}]},
                {"id":"m2","parent_id":"m1","role":"ASSISTANT","inserted_at":"2026-11-17T00:00:02.000Z",
                 "fragments":[{"type":"RESPONSE","content":"ok"}]}
              ]
            }
        """.trimIndent()
        val file = archive(
            mapOf(
                "manifest.json" to """{"export_format_version":3}""",
                "chat_sessions/s1.json" to json,
                "chat_sessions/s1_files/a.png" to "PNG",
            )
        )
        var imported: me.rerere.rikkahub.data.model.Conversation? = null
        val result = DeepSeekImporter.importStreaming(
            file = file,
            assistantId = Uuid.random(),
            saveAttachment = { attachment -> "file:///uploads/${attachment.fileName}" },
        ) { conversation, _ -> imported = conversation }

        assertEquals(1, result.newConversations)
        assertEquals(2, result.attachmentParts)
        val parts = requireNotNull(imported).messageNodes.first().messages.first().parts
        assertTrue(parts.any { it is UIMessagePart.Image })
        assertTrue(parts.any { it is UIMessagePart.Text && "b.txt" in it.text })
    }

    private fun archive(entries: Map<String, String>): java.io.File {
        val file = temp.newFile("deepseek-export.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun sessionJson(): String = """
        {
          "id":"s1","title":"Title","current_message_id":"m2",
          "inserted_at":"2026-11-17T00:00:00.000Z","updated_at":"2026-11-17T01:00:00.000Z",
          "messages":[
            {"id":"m1","parent_id":null,"role":"USER","inserted_at":"2026-11-17T00:00:01.000Z",
             "fragments":[{"type":"REQUEST","content":"hello"}]},
            {"id":"m2","parent_id":"m1","role":"ASSISTANT","inserted_at":"2026-11-17T00:00:02.000Z",
             "fragments":[{"type":"RESPONSE","content":"hi"}]}
          ]
        }
    """.trimIndent()
}
