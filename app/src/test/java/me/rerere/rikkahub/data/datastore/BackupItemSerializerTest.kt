package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [BackupItem] 用自定义序列化器兼容旧版本的枚举名。
 *
 * 这段初始化曾因 wrapped descriptor 同名而抛 IllegalArgumentException，设置读取失败后进程直接退出（闪退），
 * 所以这里同时覆盖 serializer 初始化和旧值解码两条路径。
 */
class BackupItemSerializerTest {
    @Test
    fun `round trips current items`() {
        val items = listOf(BackupItem.CONVERSATIONS, BackupItem.MCP, BackupItem.ASSISTANTS, BackupItem.ALL)
        val json = JsonInstant.encodeToString(items)
        assertEquals(items, JsonInstant.decodeFromString<List<BackupItem>>(json))
    }

    @Test
    fun `maps legacy DATABASE to conversations`() {
        assertEquals(
            listOf(BackupItem.CONVERSATIONS),
            JsonInstant.decodeFromString<List<BackupItem>>("""["DATABASE"]"""),
        )
    }

    @Test
    fun `unknown legacy items fall back to all`() {
        // 更早的版本有 FILES / SETTINGS / AGENTS 等名字，无法精确对应，统一当成完整备份
        assertEquals(
            listOf(BackupItem.ALL),
            JsonInstant.decodeFromString<List<BackupItem>>("""["FILES"]"""),
        )
        assertEquals(
            setOf(BackupItem.ALL),
            JsonInstant.decodeFromString<List<BackupItem>>("""["AGENTS","SEARCH"]""").toSet(),
        )
    }

    @Test
    fun `webdav config with legacy items still decodes`() {
        val config = JsonInstant.decodeFromString<WebDavConfig>(
            """{"url":"https://dav.example","items":["DATABASE","FILES"]}""",
        )
        assertEquals("https://dav.example", config.url)
        assertEquals(listOf(BackupItem.CONVERSATIONS, BackupItem.ALL), config.items)
    }

    @Test
    fun `all overrides other selections`() {
        val selection = BackupSelection(items = setOf(BackupItem.CONVERSATIONS, BackupItem.ALL))
        assertEquals(setOf(BackupItem.ALL), selection.effectiveItems)
        assertEquals(BackupResource.entries.toSet(), selection.resources)
    }

    @Test
    fun `individual selections expand to their resources`() {
        val selection = BackupSelection(
            items = setOf(BackupItem.CONVERSATIONS, BackupItem.ASSISTANTS, BackupItem.MCP),
        )
        assertEquals(
            setOf(BackupResource.CONVERSATIONS, BackupResource.ASSISTANTS, BackupResource.MCP),
            selection.resources,
        )
        // 未包含的设置类资源不应被导出
        assertFalse(BackupResource.SETTINGS in selection.resources)
    }
}
