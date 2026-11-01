package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SubAgent
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class SubAgentNormalizationTest {
    @Test
    fun `trims names and clamps limits`() {
        val settings = Settings(
            subAgents = listOf(
                SubAgent(
                    id = Uuid.random(),
                    name = "  Code Expert  ",
                    description = "  handles code  ",
                    maxSteps = 999,
                    timeoutSeconds = 1,
                )
            )
        ).withValidSubAgents()

        val saved = settings.subAgents.single()
        assertEquals("Code Expert", saved.name)
        assertEquals("handles code", saved.description)
        assertEquals(SubAgent.MAX_MAX_STEPS, saved.maxSteps)
        assertEquals(10, saved.timeoutSeconds)
    }

    @Test
    fun `drops blank and duplicate definitions`() {
        val duplicateId = Uuid.random()
        val settings = Settings(
            subAgents = listOf(
                SubAgent(id = duplicateId, name = "A"),
                SubAgent(id = duplicateId, name = "A again"),
                SubAgent(id = Uuid.random(), name = "  "),
            )
        ).withValidSubAgents()

        assertEquals(1, settings.subAgents.size)
        assertEquals("A", settings.subAgents.single().name)
    }

    @Test
    fun `clears assistant references to removed definitions`() {
        val kept = SubAgent(id = Uuid.random(), name = "Kept")
        val removed = SubAgent(id = Uuid.random(), name = "")
        val assistant = Assistant(
            id = Uuid.random(),
            name = "Role",
            enabledSubAgents = setOf(kept.id, removed.id),
        )

        val settings = Settings(
            subAgents = listOf(kept, removed),
            assistants = listOf(assistant),
        ).withValidSubAgents()

        assertEquals(setOf(kept.id), settings.assistants.single().enabledSubAgents)
    }

    @Test
    fun `normalizes the shared default tool template`() {
        val settings = Settings(
            subAgentDefaultTools = setOf(" read_file ", "", "shell"),
        ).withValidSubAgents()

        assertEquals(setOf("read_file", "shell"), settings.subAgentDefaultTools)
    }
}
