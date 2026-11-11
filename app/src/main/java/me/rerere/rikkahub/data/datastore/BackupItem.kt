package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/**
 * 备份界面可选的内容。
 *
 * [ALL] 包含一切可备份的数据（含私有存储里的附件、技能、字体、生成媒体与 SSH 密钥），选中它时忽略其它选项。
 */
@kotlinx.serialization.Serializable(with = BackupItemSerializer::class)
enum class BackupItem {
    /** 对话与消息、收藏、助手分组、记忆、用量、翻译历史、媒体生成记录、托管文件索引 */
    CONVERSATIONS,

    /** MCP 服务器与 OAuth 授权状态 */
    MCP,

    /** 助手本身，以及子代理、快捷消息、模式注入、Lorebook */
    ASSISTANTS,

    /** 所有可备份内容：上面三项之外，还包括提供商、搜索、语音、媒体生成、界面设置、工作区和文件 */
    ALL,
    ;

    /** 是否会把密钥、token 等敏感信息写进备份。 */
    val containsSecrets: Boolean
        get() = this == MCP || this == ALL
}

/** 实际导出/恢复的资源。界面上的选项由它组合而成。 */
@kotlinx.serialization.Serializable
enum class BackupResource {
    CONVERSATIONS,
    SETTINGS,
    MCP,
    SEARCH,
    VOICE,
    MEDIA_GENERATION,
    ASSISTANTS,
    WORKSPACES,
    FILES,
}

/** 某个选项包含哪些资源。[BackupItem.ALL] 覆盖全部。 */
internal fun BackupItem.resources(): Set<BackupResource> = when (this) {
    BackupItem.CONVERSATIONS -> setOf(BackupResource.CONVERSATIONS)
    BackupItem.MCP -> setOf(BackupResource.MCP)
    BackupItem.ASSISTANTS -> setOf(BackupResource.ASSISTANTS)
    BackupItem.ALL -> BackupResource.entries.toSet()
}

/**
 * 兼容已保存的旧枚举名。
 *
 * 更早的版本只有 DATABASE / FILES；上一版有九项。它们都无法精确对应现在的四项，
 * 统一映射到 [BackupItem.ALL]：宁可多恢复，也不静默漏掉用户以为备份过的东西。
 */
internal object BackupItemSerializer : KSerializer<BackupItem> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BackupItem", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BackupItem) =
        encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): BackupItem {
        val name = decoder.decodeString()
        return when (name) {
            "DATABASE" -> BackupItem.CONVERSATIONS
            else -> BackupItem.entries.firstOrNull { it.name == name } ?: BackupItem.ALL
        }
    }
}

/** 默认就是完整备份：[BackupItem.ALL]。 */
val DEFAULT_BACKUP_ITEMS: List<BackupItem> = listOf(BackupItem.ALL)

/** 恢复时与现有数据的合并方式。 */
@kotlinx.serialization.Serializable
enum class RestoreMode {
    /** 用备份里的内容替换现有内容 */
    OVERWRITE,

    /** 只写入备份里的内容，同 id 的覆盖，其余保留 */
    MERGE,
    ;

    companion object {
        /** 用户积累的内容默认只合并不替换。 */
        fun of(item: BackupItem): RestoreMode = when (item) {
            BackupItem.CONVERSATIONS, BackupItem.ASSISTANTS, BackupItem.ALL -> MERGE
            BackupItem.MCP -> OVERWRITE
        }

        fun of(resource: BackupResource): RestoreMode = when (resource) {
            BackupResource.CONVERSATIONS, BackupResource.ASSISTANTS -> MERGE
            else -> OVERWRITE
        }
    }
}

/** 一次备份或恢复所选的项与恢复方式。 */
@kotlinx.serialization.Serializable
data class BackupSelection(
    val items: Set<BackupItem> = DEFAULT_BACKUP_ITEMS.toSet(),
    val modes: Map<BackupItem, RestoreMode> = emptyMap(),
) {
    /** 选中「所有可备份内容」时忽略其它选项。 */
    val effectiveItems: Set<BackupItem>
        get() = if (BackupItem.ALL in items) setOf(BackupItem.ALL) else items

    /** 这次实际要导出/恢复的资源。 */
    val resources: Set<BackupResource>
        get() = effectiveItems.flatMapTo(mutableSetOf()) { it.resources() }

    fun modeOf(item: BackupItem): RestoreMode = modes[item] ?: RestoreMode.of(item)

    /** 资源用包含它的那个选项的恢复方式。 */
    fun modeOf(resource: BackupResource): RestoreMode {
        val item = effectiveItems.firstOrNull { resource in it.resources() }
            ?: return RestoreMode.of(resource)
        return modeOf(item)
    }
}

/**
 * 每项资源在备份的 `settings.json` 里拥有的字段。
 *
 * 没列出来的字段只属于 [BackupResource.SETTINGS]；恢复时没被备份的字段保持当前值，不会退回默认值。
 */
internal object BackupSettingsFields {
    private val byResource: Map<BackupResource, Set<String>> = mapOf(
        BackupResource.SETTINGS to setOf(
            "dynamicColor", "themeId", "customThemes", "developerMode", "displaySetting", "networkSetting",
            "favoriteModels", "chatModelId", "fastModelId", "fastModelReasoningLevel", "memoryModelId",
            "memoryModelReasoningLevel", "enableAutoTitle", "titlePrompt",
            "translateModeId", "translatePrompt", "translateThinkingBudget", "translateTargetLanguage",
            "enableSuggestion", "suggestionPrompt", "ocrModelId", "ocrPrompt", "providers",
            "webDavConfig", "s3Config", "uploadS3Config", "localTools", "toolApprovals",
            "shellOvertimeSeconds", "shellAutoStopOnOvertime", "shellBackgroundEnabled",
            "shellBackgroundMaxRunning", "shellBackgroundNotify", "workspacePrompt", "webServerEnabled",
            "webServerPort", "webServerJwtEnabled", "webServerAccessPassword", "webServerLocalhostOnly",
            "backupReminderConfig", "sponsorAlertDismissedAt", "pathOpenPreference", "pathOpenMtPackage",
        ),
        BackupResource.MCP to setOf("mcpServers"),
        BackupResource.SEARCH to setOf("searchServices", "searchCommonOptions", "searchServiceSelected"),
        BackupResource.VOICE to setOf(
            "ttsProviders",
            "asrProviders",
            "selectedTTSProviderId",
            "defaultTTSPlaybackSpeed",
            "selectedASRProviderId",
        ),
        BackupResource.MEDIA_GENERATION to setOf("mediaGenerationProviders", "imageGenerationModelId"),
        BackupResource.ASSISTANTS to setOf(
            "assistants", "assistantTags", "assistantId", "subAgents", "subAgentDefaultTools",
            "subAgentModelId", "subAgentMaxConcurrency", "quickMessages", "modeInjections", "lorebooks",
        ),
    )

    fun of(resource: BackupResource): Set<String> = byResource[resource].orEmpty()

    fun fieldsOf(resources: Collection<BackupResource>): Set<String> =
        resources.flatMapTo(mutableSetOf()) { of(it) }

    /** 取出这些资源拥有的设置字段。 */
    fun extract(settings: Settings, resources: Collection<BackupResource>): Map<String, JsonElement> =
        BackupFields.extract(settings, fieldsOf(resources))
}
