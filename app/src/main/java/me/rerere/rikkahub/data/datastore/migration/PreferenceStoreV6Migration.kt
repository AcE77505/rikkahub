package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 工具开关从「每个助手一份」改为「全局一份」。
 *
 * 迁移取所有助手 `localTools` 的并集：原先任一助手开着的工具，升级后仍然可用，
 * 不会因为助手之间配置不同而在切换助手后少一批工具。
 * 工作区工具默认全关，因此不在并集里被意外打开。
 */
class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        if (prefs[SettingsStore.LOCAL_TOOLS] == null) {
            prefs[SettingsStore.ASSISTANTS]?.let { json ->
                prefs[SettingsStore.LOCAL_TOOLS] = mergeAssistantLocalTools(json)
            }
        }

        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/** 取所有助手 `localTools` 的并集，按首次出现的顺序去重 */
internal fun mergeAssistantLocalTools(assistantsJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching "[]"
        val merged = LinkedHashMap<String, JsonElement>()
        root.forEach { assistant ->
            val options = (assistant as? JsonObject)?.get(KEY) as? JsonArray ?: return@forEach
            options.forEach { option ->
                // sealed class 的判别字段，同名只保留一份
                val name = (option as? JsonObject)
                    ?.get(DISCRIMINATOR)
                    ?.jsonPrimitiveOrNull
                    ?.contentOrNull
                    ?: return@forEach
                merged.putIfAbsent(name, option)
            }
        }
        JsonInstant.encodeToString(JsonArray(merged.values.toList()))
    }.getOrDefault("[]")
}

private const val KEY = "localTools"
private const val DISCRIMINATOR = "type"
