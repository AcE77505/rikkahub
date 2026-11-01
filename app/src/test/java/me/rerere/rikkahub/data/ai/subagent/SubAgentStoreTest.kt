package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SubAgentStoreTest {
    private fun meta(ref: String, parentConversationId: String = "conv") = SubAgentRunMeta(
        ref = ref,
        parentConversationId = parentConversationId,
        parentAssistantId = "assistant",
        subAgentId = "subagent",
        label = "Code Expert",
        status = SubAgentRunStatus.RUNNING,
        startedAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `completed result can be paged`() = runBlocking {
        val dir = Files.createTempDirectory("subagent-store").toFile()
        try {
            val store = SubAgentStore(dir)
            val ref = store.newRef()
            store.prepare(meta(ref))
            store.complete(
                ref,
                listOf(UIMessage.user("question"), UIMessage.assistant("hello 世界")),
                steps = 2,
            )

            val first = store.readResult(ref, "conv", offsetBytes = 0, limitBytes = 4)
            assertEquals(SubAgentRunStatus.COMPLETED, first.status)
            assertTrue(first.text.isNotEmpty())
            assertNotNull(first.nextOffsetBytes)

            val pages = buildString {
                var offset = 0
                while (true) {
                    val page = store.readResult(ref, "conv", offset, 4)
                    append(page.text)
                    offset = page.nextOffsetBytes ?: break
                }
            }
            assertEquals("hello 世界", pages)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cancelled result exposes partial transcript`() = runBlocking {
        val dir = Files.createTempDirectory("subagent-store").toFile()
        try {
            val store = SubAgentStore(dir)
            val ref = store.newRef()
            store.prepare(meta(ref))
            store.cancel(
                ref,
                listOf(UIMessage.user("question"), UIMessage.assistant("partial finding")),
                "stopped",
            )

            val result = store.readResult(ref, "conv", 0, 1024)
            assertEquals(SubAgentRunStatus.CANCELLED, result.status)
            assertTrue(result.text.contains("partial finding"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cleanup marks stale running interrupted`() = runBlocking {
        val dir = Files.createTempDirectory("subagent-store").toFile()
        try {
            val store = SubAgentStore(dir)
            val ref = store.newRef()
            store.prepare(meta(ref))

            store.cleanupStaleRunning()

            val failure = runCatching { store.readResult(ref, "conv", 0, 1024) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("interrupted"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `delete by parent removes records`() = runBlocking {
        val dir = Files.createTempDirectory("subagent-store").toFile()
        try {
            val store = SubAgentStore(dir)
            val ref = store.newRef()
            store.prepare(meta(ref))
            store.complete(ref, listOf(UIMessage.assistant("done")), steps = 1)

            store.deleteByParent("conv")

            val failure = runCatching { store.readResult(ref, "conv", 0, 1024) }.exceptionOrNull()
            assertNotNull(failure)
            assertNull(store.listByParent("conv").firstOrNull())
        } finally {
            dir.deleteRecursively()
        }
    }
}
