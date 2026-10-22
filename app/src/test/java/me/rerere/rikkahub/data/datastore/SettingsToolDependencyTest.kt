package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.migration.mergeAssistantLocalTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsToolDependencyTest {

    @Test
    fun `write and edit are dropped when read file is off`() {
        val settings = Settings(
            localTools = listOf(
                LocalToolOption.TimeInfo,
                LocalToolOption.WorkspaceWriteFile,
                LocalToolOption.WorkspaceEditFile,
                LocalToolOption.WorkspaceShell,
            ),
        ).withToolDependencies()

        assertFalse(LocalToolOption.WorkspaceWriteFile in settings.localTools)
        assertFalse(LocalToolOption.WorkspaceEditFile in settings.localTools)
        // 执行命令不依赖读文件
        assertTrue(LocalToolOption.WorkspaceShell in settings.localTools)
        assertTrue(LocalToolOption.TimeInfo in settings.localTools)
    }

    @Test
    fun `write and edit are kept when read file is on`() {
        val settings = Settings(
            localTools = listOf(
                LocalToolOption.WorkspaceReadFile,
                LocalToolOption.WorkspaceWriteFile,
                LocalToolOption.WorkspaceEditFile,
            ),
        ).withToolDependencies()

        assertEquals(
            listOf(
                LocalToolOption.WorkspaceReadFile,
                LocalToolOption.WorkspaceWriteFile,
                LocalToolOption.WorkspaceEditFile,
            ),
            settings.localTools,
        )
    }

    @Test
    fun `every tool is disabled by default`() {
        val settings = Settings()
        LocalToolOption.WORKSPACE_TOOLS.forEach { tool ->
            assertFalse("$tool should be off by default", tool in settings.localTools)
        }
        assertTrue(settings.localTools.isEmpty())
    }

    @Test
    fun `overtime defaults to thirty seconds and auto stop is off`() {
        val settings = Settings()
        assertEquals(DEFAULT_SHELL_OVERTIME_SECONDS, settings.shellOvertimeSeconds)
        assertEquals(30, settings.shellOvertimeSeconds)
        assertFalse(settings.shellAutoStopOnOvertime)
    }

    @Test
    fun `local tools union migrates every assistant switch`() {
        val assistantsJson = """
            [
              {"id":"a","localTools":[{"type":"time_info"}]},
              {"id":"b","localTools":[{"type":"chart_display"},{"type":"time_info"}]},
              {"id":"c"}
            ]
        """.trimIndent()

        val merged = mergeAssistantLocalTools(assistantsJson)

        assertTrue(merged.contains("\"time_info\""))
        assertTrue(merged.contains("\"chart_display\""))
        // 并集去重：time_info 只出现一次
        assertEquals(1, Regex("\"time_info\"").findAll(merged).count())
        // 工作区工具不在并集里被意外打开
        assertFalse(merged.contains("workspace_"))
    }

    @Test
    fun `local tools union tolerates broken json`() {
        assertEquals("[]", mergeAssistantLocalTools("not json"))
        assertEquals("[]", mergeAssistantLocalTools("[]"))
    }
}
