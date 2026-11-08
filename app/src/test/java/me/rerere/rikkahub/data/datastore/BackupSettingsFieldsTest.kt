package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 选择「所有可备份内容」时，`settings.json` 必须覆盖 DataStore 里持久化的每一个字段。
 *
 * 漏掉字段不会影响本版本的恢复（缺失字段保持现值），但旧版本的备份实现会把缺失字段写成默认值，
 * 所以这里用 [SETTINGS_FIELDS] 做权威校对。
 */
class BackupSettingsFieldsTest {
    @Test
    fun `all resources cover every persisted setting`() {
        val persisted = SETTINGS_FIELDS.map { it.property.name }.toSet()
        val covered = BackupSettingsFields.fieldsOf(BackupResource.entries)

        assertEquals(persisted - covered, emptySet<String>())
    }

    /** 「所有可备份内容」写完整 settings.json，旧版本的实现也能直接解码。 */
    @Test
    fun `full settings include every persisted field`() {
        val persisted = SETTINGS_FIELDS.map { it.property.name }.toSet()
        assertEquals(persisted, BackupFields.all(Settings()).keys)
    }
}
