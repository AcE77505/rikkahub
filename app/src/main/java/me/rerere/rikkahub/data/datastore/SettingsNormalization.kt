package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.SubAgent

/** 读盘后和每次修改后都要过一遍，保证内存里的设置始终是规整的。 */
internal fun Settings.normalized(): Settings =
    withBuiltInDefaults().withoutInvalidReferences().withValuesInRange().withToolDependencies().withValidSubAgents()

/**
 * 子代理定义去重、裁剪，并清掉助手里的悬空引用。
 *
 * 子代理定义是全局共享的，删除或无效的 id 不能继续留在 `Assistant.enabledSubAgents` 里。
 */
internal fun Settings.withValidSubAgents(): Settings {
    val cleaned = subAgents
        .map { agent ->
            agent.copy(
                name = agent.name.trim().take(SubAgent.MAX_NAME_LENGTH),
                description = agent.description.trim().take(SubAgent.MAX_DESCRIPTION_LENGTH),
                allowedTools = agent.allowedTools
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() },
                maxSteps = agent.maxSteps.coerceIn(1, SubAgent.MAX_MAX_STEPS),
                timeoutSeconds = agent.timeoutSeconds.coerceIn(10, SubAgent.MAX_TIMEOUT_SECONDS),
            )
        }
        .filter { it.name.isNotEmpty() }
        .distinctBy { it.id }

    val validIds = cleaned.mapTo(HashSet()) { it.id }
    val normalizedDefaultTools = subAgentDefaultTools
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    val updatedAssistants = assistants.map { assistant ->
        val filtered = assistant.enabledSubAgents.filterTo(LinkedHashSet()) { it in validIds }
        if (filtered == assistant.enabledSubAgents) assistant else assistant.copy(enabledSubAgents = filtered)
    }

    if (cleaned == subAgents &&
        updatedAssistants == assistants &&
        normalizedDefaultTools == subAgentDefaultTools
    ) {
        return this
    }
    return copy(
        subAgents = cleaned,
        subAgentDefaultTools = normalizedDefaultTools,
        assistants = updatedAssistants,
    )
}

/**
 * 「写入文件」「编辑文件」依赖「读取文件」。
 *
 * 开关关闭时它们不可用，这里把持久化的值一并去掉：只靠界面置灰会出现
 * 「显示是关的、实际仍注入给模型」的不一致。
 */
internal fun Settings.withToolDependencies(): Settings {
    if (LocalToolOption.WorkspaceReadFile in localTools) return this
    val filtered = localTools - LocalToolOption.WorkspaceWriteFile - LocalToolOption.WorkspaceEditFile
    return if (filtered.size == localTools.size) this else copy(localTools = filtered)
}

// 补齐缺失的内置提供商/助手/TTS，内置提供商的描述信息始终以代码里的为准
internal fun Settings.withBuiltInDefaults(): Settings {
    var providers = this.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
    DEFAULT_PROVIDERS.forEach { defaultProvider ->
        if (providers.none { it.id == defaultProvider.id }) {
            providers.add(defaultProvider.copyProvider())
        }
    }
    providers = providers.map { provider ->
        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
        if (defaultProvider != null) {
            provider.copyProvider(
                builtIn = defaultProvider.builtIn,
                description = defaultProvider.description,
                shortDescription = defaultProvider.shortDescription,
            )
        } else provider
    }.toMutableList()
    val assistants = this.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
    DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
        if (assistants.none { it.id == defaultAssistant.id }) {
            assistants.add(defaultAssistant.copy())
        }
    }
    val ttsProviders = this.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
    DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
        if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
            ttsProviders.add(defaultTTSProvider.copyProvider())
        }
    }
    return copy(
        providers = providers,
        assistants = assistants,
        ttsProviders = ttsProviders,
    )
}

// 去重并清理无效引用
internal fun Settings.withoutInvalidReferences(): Settings {
    val settings = this
    val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
    val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
    val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
    val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
    val validModelIds by lazy { settings.providers.flatMap { it.models }.mapTo(HashSet()) { it.id } }
    val asrProviders = settings.asrProviders.distinctBy { it.id }
    return settings.copy(
        providers = settings.providers.distinctBy { it.id }.map { provider ->
            provider.copyProvider(models = provider.models.distinctBy { model -> model.id })
        },
        assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
            assistant.copy(
                // 过滤掉不存在的 MCP 服务器 ID
                mcpServers = assistant.mcpServers.filter { serverId ->
                    serverId in validMcpServerIds
                }.toSet(),
                // 过滤掉不存在的模式注入 ID
                modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                    id in validModeInjectionIds
                }.toSet(),
                // 过滤掉不存在的 Lorebook ID
                lorebookIds = assistant.lorebookIds.filter { id ->
                    id in validLorebookIds
                }.toSet(),
                // 过滤掉不存在的快捷消息 ID
                quickMessageIds = assistant.quickMessageIds.filter { id ->
                    id in validQuickMessageIds
                }.toSet()
            )
        },
        ttsProviders = settings.ttsProviders.distinctBy { it.id },
        asrProviders = asrProviders,
        selectedASRProviderId = settings.selectedASRProviderId
            ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
            ?: asrProviders.firstOrNull()?.id,
        favoriteModels = settings.favoriteModels.filter { it in validModelIds },
        modeInjections = settings.modeInjections.distinctBy { it.id },
        lorebooks = settings.lorebooks.distinctBy { it.id },
        quickMessages = settings.quickMessages.distinctBy { it.id },
    )
}

// 把取值收回合法范围，比如删掉搜索服务后选中的下标可能越界
private fun Settings.withValuesInRange(): Settings = copy(
    searchServiceSelected = searchServiceSelected.coerceIn(0, (searchServices.size - 1).coerceAtLeast(0)),
    defaultTTSPlaybackSpeed = defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f),
)
