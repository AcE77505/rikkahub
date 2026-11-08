package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

/** 备份里的部分设置字段：键名与 [Settings] 属性名一致，值是该属性的 JSON。 */
internal typealias SettingsFieldsValues = Map<String, JsonElement>

internal object BackupFields {
    const val LAUNCH_COUNT_KEY = "launchCount"

    /** 按 [items] 需要的字段名取出备份字段。 */
    fun extract(current: Settings, keys: Collection<String>): SettingsFieldsValues {
        if (keys.isEmpty()) return emptyMap()
        val json = JsonInstant.encodeToJsonElement(Settings.serializer(), current).jsonObject
        val fields = HashMap<String, JsonElement>(keys.size)
        for (key in keys) json[key]?.let { fields[key] = it }
        return fields
    }

    /** 完整设置字段（除 @Transient 的 init），用于输出旧版本也能读的 settings.json。 */
    fun all(current: Settings): SettingsFieldsValues =
        JsonInstant.encodeToJsonElement(Settings.serializer(), current).jsonObject

    /** 把备份字段合进当前设置；没有提供的字段保持 [current] 的值，规整交给 [SettingsStore]。 */
    fun apply(current: Settings, fields: SettingsFieldsValues): Settings {
        if (fields.isEmpty()) return current
        val merged = JsonObject(
            JsonInstant.encodeToJsonElement(Settings.serializer(), current).jsonObject + fields
        )
        return JsonInstant.decodeFromJsonElement(Settings.serializer(), merged)
    }

    /** 启动次数不在 Settings 里，和旧备份一样放在备份的 settings.json 里。 */
    fun withLaunchCount(fields: SettingsFieldsValues, launchCount: Int): String =
        JsonInstant.encodeToString(
            JsonObject(fields.toMutableMap().apply { put(LAUNCH_COUNT_KEY, JsonPrimitive(launchCount)) })
        )

    /** 更早的备份没有这个字段，按 0 恢复，和它还在 Settings 里时的默认值一致。 */
    fun launchCountOf(settingsJson: String): Int =
        runCatching {
            JsonInstant.parseToJsonElement(
                settingsJson
            ).jsonObject[LAUNCH_COUNT_KEY]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 0

    /** 去掉启动次数，剩下的就是设置字段。 */
    fun fieldsOf(settingsJson: String): SettingsFieldsValues =
        JsonInstant.parseToJsonElement(settingsJson).jsonObject - LAUNCH_COUNT_KEY
}
