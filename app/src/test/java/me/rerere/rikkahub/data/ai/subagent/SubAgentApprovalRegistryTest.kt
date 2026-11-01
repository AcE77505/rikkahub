package me.rerere.rikkahub.data.ai.subagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAgentApprovalRegistryTest {
    @Test
    fun `approval is scoped to conversation and sub-agent`() {
        val registry = SubAgentApprovalRegistry()
        val conversation = Uuid.random()
        val otherConversation = Uuid.random()
        val subAgent = Uuid.random()
        val otherSubAgent = Uuid.random()

        registry.markApproved(conversation, subAgent)

        assertTrue(registry.isApproved(conversation, subAgent))
        assertFalse(registry.isApproved(otherConversation, subAgent))
        assertFalse(registry.isApproved(conversation, otherSubAgent))
    }

    @Test
    fun `clear removes all approvals for a conversation`() {
        val registry = SubAgentApprovalRegistry()
        val conversation = Uuid.random()
        val subAgent = Uuid.random()
        registry.markApproved(conversation, subAgent)

        registry.clear(conversation)

        assertFalse(registry.isApproved(conversation, subAgent))
    }
}
