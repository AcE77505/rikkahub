package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.ai.transformers.DEFAULT_WORKSPACE_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCustomizationTest {
    @Test
    fun `the built-in workspace template is not marked as modified`() {
        assertFalse(Settings(workspacePrompt = DEFAULT_WORKSPACE_PROMPT).hasCustomizedWorkspacePrompt)
        assertFalse(Settings(workspacePrompt = "  $DEFAULT_WORKSPACE_PROMPT\n").hasCustomizedWorkspacePrompt)
    }

    @Test
    fun `a custom or legacy workspace template is marked as modified`() {
        assertTrue(Settings(workspacePrompt = "custom template").hasCustomizedWorkspacePrompt)
        assertTrue(Settings(workspacePrompt = "$DEFAULT_WORKSPACE_PROMPT\nextra").hasCustomizedWorkspacePrompt)
    }

    @Test
    fun `tool overrides are detected per tool`() {
        val settings = Settings(
            toolDescriptionOverrides = mapOf("read_file" to "custom description"),
            toolSystemPromptOverrides = mapOf("shell" to "custom prompt"),
        )

        assertTrue(settings.hasCustomizedToolPrompt("read_file"))
        assertTrue(settings.hasCustomizedToolPrompt("shell"))
        assertFalse(settings.hasCustomizedToolPrompt("write_file"))
    }
}
