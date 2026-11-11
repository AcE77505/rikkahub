package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.PresetMessageGroup
import me.rerere.rikkahub.data.model.SystemPromptPreset
import me.rerere.rikkahub.data.model.effectiveSystemPrompt
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import me.rerere.rikkahub.data.repository.memoryId
import java.io.File
import kotlin.uuid.Uuid

private const val TOOL_LIST_ASSISTANTS = "list_assistants"
private const val TOOL_GET_ASSISTANT = "get_assistant"
private const val TOOL_UPDATE_ASSISTANT = "update_assistant"

private const val TOOL_LIST_ASSISTANT_MEMORIES = "list_assistant_memories"
private const val TOOL_READ_ASSISTANT_MEMORY = "read_assistant_memory"
private const val TOOL_WRITE_ASSISTANT_MEMORY = "write_assistant_memory"
private const val TOOL_DELETE_ASSISTANT_MEMORY = "delete_assistant_memory"
private const val TOOL_IMPORT_ASSISTANT_MEMORY = "import_assistant_memory"

private const val MAX_MEMORY_READ_PATHS = 20
private const val MAX_MEMORY_IMPORT_FILES = 200
private const val MAX_MEMORY_IMPORT_DEPTH = 3

/**
 * 修改其他助手的工具组。
 *
 * 当前对话所属的助手是只读的：可以列出来读，但不能通过 [TOOL_UPDATE_ASSISTANT] 改自己，
 * 也不能改自己正在使用的记忆库（含共用全局记忆的情况）。
 * 工具只暴露提示词、预设对话、生成参数和记忆文件，不暴露模型、MCP、工作区等本机引用。
 */
internal fun buildAssistantConfigTools(
    settingsStore: SettingsStore,
    memoryRepository: MemoryRepository,
    currentAssistantId: Uuid,
): List<Tool> = listOf(
    listAssistantsTool(settingsStore, currentAssistantId),
    getAssistantTool(settingsStore),
    updateAssistantTool(settingsStore, currentAssistantId),
) + buildAssistantMemoryTools(settingsStore, memoryRepository, currentAssistantId)

private fun listAssistantsTool(
    settingsStore: SettingsStore,
    currentAssistantId: Uuid,
): Tool = Tool(
    name = TOOL_LIST_ASSISTANTS,
    description = """
        List all assistants on this device except the one used by the current conversation.
        Returns each assistant's id, name, effective system prompt preview, and preset dialogue group summaries.
        Use the returned id with $TOOL_GET_ASSISTANT or $TOOL_UPDATE_ASSISTANT.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    execute = {
        val settings = settingsStore.awaitLoaded()
        val assistants = settings.assistants
            .filter { it.id != currentAssistantId }
            .map { it.summaryJson() }
        ok(buildJsonObject {
            put("assistants", buildJsonArray {
                assistants.forEach { add(it) }
            })
        })
    },
)

private fun getAssistantTool(
    settingsStore: SettingsStore,
): Tool = Tool(
    name = TOOL_GET_ASSISTANT,
    description = """
        Read one assistant's editable configuration: name, effective system prompt, system prompt presets,
        preset dialogue groups, and generation settings. Resolve the target by id (preferred) or exact name.
        This tool is read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("assistant", buildJsonObject {
                    put("type", "string")
                    put("description", "Assistant id (preferred) or exact assistant name.")
                })
            },
            required = listOf("assistant"),
        )
    },
    execute = { args ->
        val query = args.jsonObject["assistant"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) {
            return@Tool fail("MISSING_ASSISTANT", "Provide an assistant id or exact name.")
        }
        val settings = settingsStore.awaitLoaded()
        val target = resolveAssistant(settings.assistants, query)
            ?: return@Tool fail(
                "ASSISTANT_NOT_FOUND",
                "No unique assistant matches '$query'. Call $TOOL_LIST_ASSISTANTS and use the id.",
            )
        ok(target.fullJson())
    },
)

private fun updateAssistantTool(
    settingsStore: SettingsStore,
    currentAssistantId: Uuid,
): Tool = Tool(
    name = TOOL_UPDATE_ASSISTANT,
    description = """
        Update another assistant's configuration. Resolve the target by id (preferred) or exact name.
        The current conversation's assistant cannot be modified.
        Scalar fields are optional.
        system_prompt sets a custom prompt and clears the selected system prompt preset.
        system_prompt_presets and preset_message_groups are UPSERTS: an item is matched by id or exact title,
        updated in place, or appended if it does not exist. They do NOT replace the whole list by default.
        Use replace_system_prompt_presets / replace_preset_message_groups to clear the existing list first.
        Use delete_system_prompt_presets / delete_preset_message_groups to remove items by title or id.
        default_system_prompt_preset / default_preset_message_group select an item by title or id;
        an empty string clears the default.
        Long prompts: tool-call arguments have a provider-dependent size limit. Send at most one long preset
        or dialogue group per call; repeated calls merge by title, so write them one at a time.
        Alternatively, pass an absolute local file path: system_prompt_file, prompt_file (inside a preset),
        or messages_file (inside a dialogue group). The file is read as UTF-8; messages_file accepts a JSON
        array or an object { "title": "...", "messages": [...] }. The path must be readable by the app.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        "You may manage other assistants with $TOOL_UPDATE_ASSISTANT only when the user asks. " +
            "Use $TOOL_LIST_ASSISTANTS or $TOOL_GET_ASSISTANT first, and never modify the current assistant. " +
            "Array fields are upserts by id/title, not replacements. Write at most one long preset or dialogue " +
            "group per call; provider tool-call arguments have a size limit. If a prompt or dialogue group is " +
            "too large, pass an absolute local file path via system_prompt_file, prompt_file, or messages_file " +
            "instead of inlining the text. Use the replace_* flags only when you intentionally want to clear the existing list."
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("assistant", buildJsonObject {
                    put("type", "string")
                    put("description", "Assistant id (preferred) or exact assistant name.")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "New assistant name.")
                })
                put("system_prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "New custom system prompt. Clears the selected system prompt preset.")
                })
                put("system_prompt_file", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to a UTF-8 text file used as the new custom system prompt. Ignored when system_prompt is present.")
                })
                put("system_prompt_presets", buildJsonObject {
                    put("type", "array")
                    put("description", "Upsert system prompt presets by id or title. Send one long preset per call, or use prompt_file.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("title", buildJsonObject { put("type", "string") })
                            put("prompt", buildJsonObject { put("type", "string") })
                            put("prompt_file", buildJsonObject {
                                put("type", "string")
                                put("description", "Absolute path to a UTF-8 text file. Used when prompt is absent.")
                            })
                            put("make_default", buildJsonObject {
                                put("type", "boolean")
                                put("description", "Select this preset as the active one.")
                            })
                        })
                    })
                })
                put("replace_system_prompt_presets", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Clear existing system prompt presets before upserting. Default false.")
                })
                put("delete_system_prompt_presets", buildJsonObject {
                    put("type", "array")
                    put("description", "Titles or ids of system prompt presets to delete.")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("default_system_prompt_preset", buildJsonObject {
                    put("type", "string")
                    put("description", "Title or id of the active system prompt preset. Empty string means custom system_prompt.")
                })
                put("preset_message_groups", buildJsonObject {
                    put("type", "array")
                    put("description", "Upsert dialogue groups by id or title. Send one long group per call, or use messages_file.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("title", buildJsonObject { put("type", "string") })
                            put("messages", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("role", buildJsonObject {
                                            put("type", "string")
                                            put("enum", buildJsonArray {
                                                add("user")
                                                add("assistant")
                                            })
                                        })
                                        put("content", buildJsonObject { put("type", "string") })
                                    })
                                    put("required", buildJsonArray {
                                        add("role")
                                        add("content")
                                    })
                                })
                            })
                            put("messages_file", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Absolute path to a UTF-8 JSON file containing a messages array or { title, messages }. " +
                                        "Used when messages is absent."
                                )
                            })
                        })
                    })
                })
                put("replace_preset_message_groups", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Clear existing dialogue groups before upserting. Default false.")
                })
                put("delete_preset_message_groups", buildJsonObject {
                    put("type", "array")
                    put("description", "Titles or ids of dialogue groups to delete.")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("default_preset_message_group", buildJsonObject {
                    put("type", "string")
                    put("description", "Title or id of the active dialogue group. Empty string means none.")
                })
                put("temperature", buildJsonObject {
                    put("type", "number")
                    put("description", "0 to 2.")
                })
                put("top_p", buildJsonObject {
                    put("type", "number")
                    put("description", "0 to 1.")
                })
                put("max_tokens", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum output tokens.")
                })
                put("context_message_limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum context messages; 0 means unlimited.")
                })
                put("enable_web_search", buildJsonObject { put("type", "boolean") })
                put("enable_memory", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("assistant"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val query = params["assistant"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) {
            return@Tool fail("MISSING_ASSISTANT", "Provide an assistant id or exact name.")
        }

        val settings = settingsStore.awaitLoaded()
        val target = resolveAssistant(settings.assistants, query)
            ?: return@Tool fail(
                "ASSISTANT_NOT_FOUND",
                "No unique assistant matches '$query'. Call $TOOL_LIST_ASSISTANTS and use the id.",
            )
        if (target.id == currentAssistantId) {
            return@Tool fail(
                "CANNOT_MODIFY_SELF",
                "The current conversation's assistant cannot be modified. Choose another assistant.",
            )
        }

        var updated = target

        params["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { updated = updated.copy(name = it) }

        if (params.containsKey("system_prompt") || params.containsKey("system_prompt_file")) {
            val inlinePrompt = params["system_prompt"]?.jsonPrimitive?.contentOrNull
            val promptPath = params["system_prompt_file"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val prompt = when {
                inlinePrompt != null -> inlinePrompt
                promptPath.isNotEmpty() -> readTextFile(promptPath).getOrElse {
                    return@Tool fail("FILE_READ_FAILED", "system_prompt_file '$promptPath': ${it.message}")
                }
                else -> ""
            }
            updated = updated.copy(
                systemPrompt = prompt,
                defaultSystemPromptPresetId = null,
            )
        }

        val presetsTouched = params.containsKey("system_prompt_presets") ||
            params.containsKey("delete_system_prompt_presets") ||
            params.containsKey("default_system_prompt_preset") ||
            params.containsKey("replace_system_prompt_presets")
        if (presetsTouched) {
            val replace = params["replace_system_prompt_presets"]?.jsonPrimitive?.booleanOrNull == true
            val merged = if (replace) mutableListOf() else updated.systemPromptPresets.toMutableList()
            var defaultId = if (replace) null else updated.defaultSystemPromptPresetId

            val incoming = mutableListOf<Pair<SystemPromptPreset, Boolean>>()
            for (element in params["system_prompt_presets"]?.jsonArray.orEmpty()) {
                val preset = element.jsonObject
                val inlinePrompt = preset["prompt"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                val promptPath = preset["prompt_file"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val prompt = when {
                    inlinePrompt != null -> inlinePrompt
                    promptPath.isNotEmpty() -> readTextFile(promptPath).getOrElse {
                        return@Tool fail("FILE_READ_FAILED", "prompt_file '$promptPath': ${it.message}")
                    }.takeIf { it.isNotBlank() }
                    else -> null
                } ?: continue
                incoming += SystemPromptPreset(
                    title = preset["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    prompt = prompt,
                ) to (preset["make_default"]?.jsonPrimitive?.booleanOrNull == true)
            }
            incoming.forEach { (preset, makeDefault) ->
                val index = merged.indexOfFirst {
                    it.id == preset.id || (preset.title.isNotBlank() && it.title.equals(preset.title, true))
                }
                val finalPreset = if (index >= 0) {
                    merged[index].copy(
                        title = preset.title.ifBlank { merged[index].title },
                        prompt = preset.prompt,
                    ).also { merged[index] = it }
                } else {
                    preset.also { merged.add(it) }
                }
                if (makeDefault) defaultId = finalPreset.id
            }
            if (replace && incoming.none { it.second }) {
                defaultId = merged.firstOrNull()?.id
            }

            params["delete_system_prompt_presets"]?.jsonArray?.forEach { element ->
                val key = element.jsonPrimitive.contentOrNull?.trim().orEmpty()
                if (key.isNotEmpty()) {
                    merged.removeAll { it.id.toString() == key || it.title.equals(key, ignoreCase = true) }
                }
            }

            if (params.containsKey("default_system_prompt_preset")) {
                val key = params["default_system_prompt_preset"]?.jsonPrimitive?.contentOrNull
                    ?.trim().orEmpty()
                if (key.isEmpty()) {
                    defaultId = null
                } else {
                    defaultId = merged.firstOrNull {
                        it.id.toString() == key || it.title.equals(key, ignoreCase = true)
                    }?.id ?: return@Tool fail(
                        "PRESET_NOT_FOUND",
                        "No system prompt preset matches '$key'. Send system_prompt_presets first or use an existing title/id.",
                    )
                }
            }

            if (defaultId != null && merged.none { it.id == defaultId }) {
                defaultId = merged.firstOrNull()?.id
            }
            val activePrompt = merged.firstOrNull { it.id == defaultId }?.prompt
            updated = updated.copy(
                systemPromptPresets = merged,
                defaultSystemPromptPresetId = defaultId,
                systemPrompt = activePrompt ?: updated.systemPrompt,
            )
        }

        val groupsTouched = params.containsKey("preset_message_groups") ||
            params.containsKey("delete_preset_message_groups") ||
            params.containsKey("default_preset_message_group") ||
            params.containsKey("replace_preset_message_groups")
        if (groupsTouched) {
            val replace = params["replace_preset_message_groups"]?.jsonPrimitive?.booleanOrNull == true
            val merged = if (replace) mutableListOf() else updated.presetMessageGroups.toMutableList()
            var defaultId = if (replace) null else updated.defaultPresetMessageGroupId

            val incoming = mutableListOf<PresetMessageGroup>()
            for (element in params["preset_message_groups"]?.jsonArray.orEmpty()) {
                val group = element.jsonObject
                val inlineMessages = group["messages"]?.jsonArray
                val messagesPath = group["messages_file"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                var fileTitle: String? = null
                val messages = when {
                    inlineMessages != null -> parseMessageArray(inlineMessages)
                    messagesPath.isNotEmpty() -> {
                        val text = readTextFile(messagesPath).getOrElse {
                            return@Tool fail("FILE_READ_FAILED", "messages_file '$messagesPath': ${it.message}")
                        }
                        parseMessagesJson(text).getOrElse {
                            return@Tool fail("INVALID_MESSAGES_FILE", "messages_file '$messagesPath': ${it.message}")
                        }.let { (title, parsed) ->
                            fileTitle = title
                            parsed
                        }
                    }
                    else -> emptyList()
                }
                if (messages.isEmpty()) continue
                val title = group["title"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: fileTitle.orEmpty()
                incoming += PresetMessageGroup(
                    title = title,
                    messages = messages,
                )
            }
            incoming.forEach { group ->
                val index = merged.indexOfFirst {
                    it.id == group.id || (group.title.isNotBlank() && it.title.equals(group.title, true))
                }
                if (index >= 0) {
                    merged[index] = merged[index].copy(
                        title = group.title.ifBlank { merged[index].title },
                        messages = group.messages,
                    )
                } else {
                    merged.add(group)
                }
            }
            if (replace) {
                defaultId = merged.firstOrNull()?.id
            }

            params["delete_preset_message_groups"]?.jsonArray?.forEach { element ->
                val key = element.jsonPrimitive.contentOrNull?.trim().orEmpty()
                if (key.isNotEmpty()) {
                    merged.removeAll { it.id.toString() == key || it.title.equals(key, ignoreCase = true) }
                }
            }

            if (params.containsKey("default_preset_message_group")) {
                val key = params["default_preset_message_group"]?.jsonPrimitive?.contentOrNull
                    ?.trim().orEmpty()
                if (key.isEmpty()) {
                    defaultId = null
                } else {
                    defaultId = merged.firstOrNull {
                        it.id.toString() == key || it.title.equals(key, ignoreCase = true)
                    }?.id ?: return@Tool fail(
                        "GROUP_NOT_FOUND",
                        "No dialogue group matches '$key'. Send preset_message_groups first or use an existing title/id.",
                    )
                }
            }

            if (defaultId != null && merged.none { it.id == defaultId }) {
                defaultId = merged.firstOrNull()?.id
            }
            updated = updated.copy(
                presetMessageGroups = merged,
                defaultPresetMessageGroupId = defaultId,
                presetMessages = emptyList(),
            )
        }

        params["temperature"]?.jsonPrimitive?.doubleOrNull?.let {
            updated = updated.copy(temperature = it.toFloat().coerceIn(0f, 2f))
        }
        params["top_p"]?.jsonPrimitive?.doubleOrNull?.let {
            updated = updated.copy(topP = it.toFloat().coerceIn(0f, 1f))
        }
        params["max_tokens"]?.jsonPrimitive?.intOrNull?.let {
            updated = updated.copy(maxTokens = it.coerceAtLeast(1))
        }
        params["context_message_limit"]?.jsonPrimitive?.intOrNull?.let {
            updated = updated.copy(contextMessageLimit = it.coerceAtLeast(0))
        }
        params["enable_web_search"]?.jsonPrimitive?.booleanOrNull?.let {
            updated = updated.copy(enableWebSearch = it)
        }
        params["enable_memory"]?.jsonPrimitive?.booleanOrNull?.let {
            updated = updated.copy(enableMemory = it)
        }

        settingsStore.update { current ->
            current.copy(
                assistants = current.assistants.map { assistant ->
                    if (assistant.id == updated.id) updated else assistant
                }
            )
        }

        ok(updated.fullJson())
    },
)

private fun resolveAssistant(assistants: List<Assistant>, query: String): Assistant? {
    val id = runCatching { Uuid.parse(query) }.getOrNull()
    if (id != null) {
        return assistants.firstOrNull { it.id == id }
    }
    return assistants.filter { it.name.equals(query, ignoreCase = true) }.singleOrNull()
}

private fun Assistant.summaryJson() = buildJsonObject {
    put("id", id.toString())
    put("name", name)
    put("system_prompt_preview", effectiveSystemPrompt().take(200))
    put("preset_message_groups", buildJsonArray {
        presetMessageGroups.forEach { group ->
            add(buildJsonObject {
                put("title", group.title)
                put("message_count", group.messages.size)
            })
        }
    })
}

private fun Assistant.fullJson() = buildJsonObject {
    put("id", id.toString())
    put("name", name)
    put("system_prompt", effectiveSystemPrompt())
    put("system_prompt_presets", buildJsonArray {
        systemPromptPresets.forEach { preset ->
            add(buildJsonObject {
                put("id", preset.id.toString())
                put("title", preset.title)
                put("prompt", preset.prompt)
                put("is_default", preset.id == defaultSystemPromptPresetId)
            })
        }
    })
    put("preset_message_groups", buildJsonArray {
        presetMessageGroups.forEach { group ->
            add(buildJsonObject {
                put("id", group.id.toString())
                put("title", group.title)
                put("is_default", group.id == defaultPresetMessageGroupId)
                put("messages", buildJsonArray {
                    group.messages.forEach { message ->
                        add(buildJsonObject {
                            put("role", if (message.role == MessageRole.USER) "user" else "assistant")
                            put("content", message.toText())
                        })
                    }
                })
            })
        }
    })
    put(
        "default_preset_message_group",
        presetMessageGroups.firstOrNull { it.id == defaultPresetMessageGroupId }?.title.orEmpty(),
    )
    temperature?.let { put("temperature", it) }
    topP?.let { put("top_p", it) }
    maxTokens?.let { put("max_tokens", it) }
    put("context_message_limit", contextMessageLimit)
    put("enable_web_search", enableWebSearch)
    put("enable_memory", enableMemory)
}

private const val MAX_TEXT_FILE_BYTES = 1024L * 1024L

private fun readTextFileBlocking(path: String): String {
    val file = File(path)
    require(file.isAbsolute) { "Path must be absolute: $path" }
    require(file.isFile) { "Not a file: $path" }
    require(file.canRead()) { "File is not readable: $path" }
    require(file.length() <= MAX_TEXT_FILE_BYTES) { "File is too large (max 1 MiB): $path" }
    return file.readText().removePrefix("\uFEFF")
}

private suspend fun readTextFile(path: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching { readTextFileBlocking(path) }
}

private fun parseMessageArray(array: JsonArray): List<UIMessage> = array.mapNotNull { messageElement ->
    val message = messageElement.jsonObject
    val content = message["content"]?.jsonPrimitive?.contentOrNull
        ?: return@mapNotNull null
    val role = when (message["role"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        else -> return@mapNotNull null
    }
    UIMessage(role = role, parts = listOf(UIMessagePart.Text(content)))
}

private fun parseMessagesJson(text: String): Result<Pair<String?, List<UIMessage>>> = runCatching {
    when (val element = Json.parseToJsonElement(text)) {
        is JsonArray -> null to parseMessageArray(element)
        is JsonObject -> {
            val messages = element["messages"]?.jsonArray ?: error("Missing messages array")
            element["title"]?.jsonPrimitive?.contentOrNull to parseMessageArray(messages)
        }
        else -> error("Expected a JSON array or an object with messages")
    }
}

private fun ok(json: kotlinx.serialization.json.JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(json.toString()))

private fun fail(code: String, message: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", code)
        put("message", message)
    }.toString()))
// ---------------------------------------------------------------------------
// 其他助手的记忆库
// ---------------------------------------------------------------------------

private sealed interface MemoryTarget {
    data class Ok(val memoryId: String) : MemoryTarget
    data class Error(val code: String, val message: String) : MemoryTarget
}

private suspend fun resolveMemoryTarget(
    settingsStore: SettingsStore,
    currentAssistantId: Uuid,
    query: String,
): MemoryTarget {
    if (query.isEmpty()) {
        return MemoryTarget.Error("MISSING_ASSISTANT", "Provide an assistant id or exact name.")
    }
    val settings = settingsStore.awaitLoaded()
    val target = resolveAssistant(settings.assistants, query)
        ?: return MemoryTarget.Error(
            "ASSISTANT_NOT_FOUND",
            "No unique assistant matches '$query'. Call $TOOL_LIST_ASSISTANTS and use the id.",
        )
    val current = settings.assistants.firstOrNull { it.id == currentAssistantId }
    if (target.id == currentAssistantId || (current != null && target.memoryId == current.memoryId)) {
        return MemoryTarget.Error(
            "CANNOT_MODIFY_SELF",
            "The current conversation's assistant shares this memory library. Choose another assistant " +
                "whose memory is stored separately.",
        )
    }
    return MemoryTarget.Ok(target.memoryId)
}

private fun buildAssistantMemoryTools(
    settingsStore: SettingsStore,
    memoryRepository: MemoryRepository,
    currentAssistantId: Uuid,
): List<Tool> = listOf(
    listAssistantMemoriesTool(settingsStore, memoryRepository, currentAssistantId),
    readAssistantMemoryTool(settingsStore, memoryRepository, currentAssistantId),
    writeAssistantMemoryTool(settingsStore, memoryRepository, currentAssistantId),
    deleteAssistantMemoryTool(settingsStore, memoryRepository, currentAssistantId),
    importAssistantMemoryTool(settingsStore, memoryRepository, currentAssistantId),
)

private fun listAssistantMemoriesTool(
    settingsStore: SettingsStore,
    memoryRepository: MemoryRepository,
    currentAssistantId: Uuid,
): Tool = Tool(
    name = TOOL_LIST_ASSISTANT_MEMORIES,
    description = """
        List another assistant's memory files. Returns path, title, description, version and update time.
        Use the returned path with $TOOL_READ_ASSISTANT_MEMORY, $TOOL_WRITE_ASSISTANT_MEMORY or
        $TOOL_DELETE_ASSISTANT_MEMORY. The current conversation's assistant cannot be accessed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { assistantSchema() },
            required = listOf("assistant"),
        )
    },
    execute = { args ->
        val target = resolveMemoryTarget(settingsStore, currentAssistantId, args.jsonObject.assistantQuery())
        if (target is MemoryTarget.Error) return@Tool fail(target.code, target.message)
        target as MemoryTarget.Ok
        val files = memoryRepository.getFiles(target.memoryId)
        ok(buildJsonObject {
            put("files", buildJsonArray {
                files.forEach { file ->
                    add(buildJsonObject {
                        put("path", file.path)
                        put("title", file.title)
                        put("description", file.description)
                        put("version", file.version)
                        put("updated_at", file.updatedAt)
                        put("size_bytes", file.content.toByteArray().size)
                    })
                }
            })
        })
    },
)

private fun readAssistantMemoryTool(
    settingsStore: SettingsStore,
    memoryRepository: MemoryRepository,
    currentAssistantId: Uuid,
): Tool = Tool(
    name = TOOL_READ_ASSISTANT_MEMORY,
    description = """
        Read one or more memory files of another assistant. Returns each file's content and version.
        Pass at most $MAX_MEMORY_READ_PATHS paths per call, e.g. ["/topics/food.md"].
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                assistantSchema()
                put("path", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("maxItems", MAX_MEMORY_READ_PATHS)
                    put("description", "Memory file paths to read")
                })
            },
            required = listOf("assistant", "path"),
        )
    },
    execute = { args ->
        val target = resolveMemoryTarget(settingsStore, currentAssistantId, args.jsonObject.assistantQuery())
        if (target is MemoryTarget.Error) return@Tool fail(target.code, target.message)
        target as MemoryTarget.Ok
        val paths = args.jsonObject["path"].memoryPaths().take(MAX_MEMORY_READ_PATHS)
        if (paths.isEmpty()) return@Tool fail("MISSING_PATH", "Provide at least one memory file path.")
        val files = paths.associateWith { memoryRepository.getFile(target.memoryId, it) }
        ok(buildJsonObject {
            put("files", buildJsonArray {
                files.values.filterNotNull().forEach { file ->
                    add(buildJsonObject {
                        put("path", file.path)
                        put("version", file.version)
                        put("content", file.content)
                    })
                }
            })
            val notFound = files.filterValues { it == null }.keys
            if (notFound.isNotEmpty()) {
                put("not_found", buildJsonArray { notFound.forEach { add(it) } })
            }
        })
    },
)

private fun writeAssistantMemoryTool(
    settingsStore: SettingsStore,
    memoryRepository: MemoryRepository,
    currentAssistantId: Uuid,
): Tool = Tool(
    name = TOOL_WRITE_ASSISTANT_MEMORY,
    description = """
        Create or overwrite a memory file of another assistant. content is the complete file.
        Instead of inlining long content, pass content_file with an absolute path readable by the app;
        the file is read as UTF-8 (max 1 MiB) and used only when content is absent.
        if_version: "new" for a new file, or the version returned by the latest read/write; omit to allow upsert.
        Memory files are limited to 48 KiB each.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                assistantSchema()
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute memory path ending in .md, e.g. /topics/food.md")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Complete file content")
                })
                put("content_file", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to a UTF-8 text file used as content. Ignored when content is present.")
                })
                put("if_version", buildJsonObject {
                    put("type", "string")
                    put("description", "\"new\" for a new file, otherwise the file's current version")
                })
            },
            required = listOf("assistant", "path"),
        )
    },
    execute = { args ->
        val target = resolveMemoryTarget(settingsStore, currentAssistantId, args.jsonObject.assistantQuery())
        if (target is MemoryTarget.Error) return@Tool fail(target.code, target.message)
        target as MemoryTarget.Ok
        val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (path.isBlank()) return@Tool fail("MISSING_PATH", "Provide path, e.g. /topics/food.md.")
        val inline = args.jsonObject["content"]?.jsonPrimitive?.contentOrNull
        val sourcePath = args.jsonObject["content_file"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val content = when {
            inline != null -> inline
            sourcePath.isNotEmpty() -> readTextFile(sourcePath).getOrElse {
                return@Tool fail("FILE_READ_FAILED", "content_file '$sourcePath': ${it.message}")
            }
            else -> return@Tool fail(
                "MISSING_CONTENT",
                "Provide content, or content_file with an absolute path.",
            )
        }
        val ifVersion = args.jsonObject["if_version"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        when (val result = memoryRepository.writeFile(target.memoryId, path, content, ifVersion)) {
            is MemoryWriteResult.Success -> ok(buildJsonObject {
                put("path", result.file?.path ?: path)
                put("version", result.file?.version.orEmpty())
            })
            is MemoryWriteResult.Conflict -> fail(
                "VERSION_CONFLICT",
                "The file changed or does not exist with that version. Read it first and retry.",
            )
            is MemoryWriteResult.Rejected -> fail("WRITE_REJECTED", result.reason)
        }
    },
)

private fun deleteAssistantMemoryTool(
    settingsStore: SettingsStore,
    memoryRepository: MemoryRepository,
    currentAssistantId: Uuid,
): Tool = Tool(
    name = TOOL_DELETE_ASSISTANT_MEMORY,
    description = "Delete one memory file of another assistant. Pass if_version to avoid deleting a file that changed.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                assistantSchema()
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Memory file path to delete")
                })
                put("if_version", buildJsonObject {
                    put("type", "string")
                    put("description", "Version returned by the latest read/write (optional)")
                })
            },
            required = listOf("assistant", "path"),
        )
    },
    execute = { args ->
        val target = resolveMemoryTarget(settingsStore, currentAssistantId, args.jsonObject.assistantQuery())
        if (target is MemoryTarget.Error) return@Tool fail(target.code, target.message)
        target as MemoryTarget.Ok
        val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (path.isEmpty()) return@Tool fail("MISSING_PATH", "Provide the memory file path.")
        val ifVersion = args.jsonObject["if_version"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        when (val result = memoryRepository.deleteFile(target.memoryId, path, ifVersion)) {
            is MemoryWriteResult.Success -> ok(buildJsonObject { put("deleted", path) })
            is MemoryWriteResult.Conflict -> fail(
                "NOT_FOUND_OR_STALE",
                "The file does not exist or its version changed. List the memory files and retry.",
            )
            is MemoryWriteResult.Rejected -> fail("DELETE_REJECTED", result.reason)
        }
    },
)

private fun importAssistantMemoryTool(
    settingsStore: SettingsStore,
    memoryRepository: MemoryRepository,
    currentAssistantId: Uuid,
): Tool = Tool(
    name = TOOL_IMPORT_ASSISTANT_MEMORY,
    description = """
        Import memory files from a local path into another assistant's memory library.
        source is an absolute path to a single .md file or a directory (scanned up to
        $MAX_MEMORY_IMPORT_DEPTH levels, at most $MAX_MEMORY_IMPORT_FILES files).
        For a single file, path overrides the target path (default /<file name>.md).
        For a directory, files keep their relative paths.
        overwrite=false (default) skips files that already exist; overwrite=true replaces them.
        Each file is limited to 48 KiB.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                assistantSchema()
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to a .md file or a directory")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Target memory path when source is a single file")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Replace existing files instead of skipping them. Default false.")
                })
            },
            required = listOf("assistant", "source"),
        )
    },
    execute = { args ->
        val target = resolveMemoryTarget(settingsStore, currentAssistantId, args.jsonObject.assistantQuery())
        if (target is MemoryTarget.Error) return@Tool fail(target.code, target.message)
        target as MemoryTarget.Ok
        val sourcePath = args.jsonObject["source"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (sourcePath.isEmpty()) return@Tool fail("MISSING_SOURCE", "Provide an absolute source path.")
        val overwrite = args.jsonObject["overwrite"]?.jsonPrimitive?.booleanOrNull == true
        val explicitPath = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }

        val sources = collectMemoryImportSources(File(sourcePath), explicitPath).getOrElse {
            return@Tool fail("INVALID_SOURCE", it.message ?: "Cannot read $sourcePath")
        }
        if (sources.isEmpty()) {
            return@Tool fail("NO_MEMORY_FILES", "No .md files found under '$sourcePath'.")
        }

        val imported = mutableListOf<JsonObject>()
        val skipped = mutableListOf<JsonObject>()
        val failed = mutableListOf<JsonObject>()
        for ((file, targetPath) in sources) {
            val read = readTextFile(file.absolutePath)
            val content = read.getOrNull()
            if (content == null) {
                failed += buildJsonObject {
                    put("source", file.absolutePath)
                    put("path", targetPath)
                    put("error", read.exceptionOrNull()?.message ?: "read failed")
                }
                continue
            }
            val ifVersion = if (overwrite) null else MemoryRepository.NEW_FILE_VERSION
            when (val result = memoryRepository.writeFile(target.memoryId, targetPath, content, ifVersion)) {
                is MemoryWriteResult.Success -> imported += buildJsonObject {
                    put("path", result.file?.path ?: targetPath)
                    put("version", result.file?.version.orEmpty())
                }
                is MemoryWriteResult.Conflict -> skipped += buildJsonObject {
                    put("path", targetPath)
                    put("reason", "already exists")
                }
                is MemoryWriteResult.Rejected -> failed += buildJsonObject {
                    put("source", file.absolutePath)
                    put("path", targetPath)
                    put("error", result.reason)
                }
            }
        }
        ok(buildJsonObject {
            put("imported", buildJsonArray { imported.forEach { add(it) } })
            put("skipped", buildJsonArray { skipped.forEach { add(it) } })
            put("failed", buildJsonArray { failed.forEach { add(it) } })
        })
    },
)

private fun JsonObjectBuilder.assistantSchema() {
    put("assistant", buildJsonObject {
        put("type", "string")
        put("description", "Assistant id (preferred) or exact assistant name.")
    })
}

private fun JsonObject.assistantQuery(): String =
    this["assistant"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

private fun JsonElement?.memoryPaths(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    is JsonPrimitive -> listOfNotNull(contentOrNull?.trim()?.takeIf(String::isNotEmpty))
    else -> emptyList()
}

private fun collectMemoryImportSources(
    source: File,
    explicitPath: String?,
): Result<List<Pair<File, String>>> = runCatching {
    require(source.exists()) { "Path does not exist: ${source.absolutePath}" }
    if (source.isFile) {
        require(source.extension.equals("md", ignoreCase = true)) {
            "Not a .md file: ${source.absolutePath}"
        }
        return@runCatching listOf(source to (explicitPath ?: "/${source.name}"))
    }
    require(source.isDirectory) { "Not a file or directory: ${source.absolutePath}" }
    val files = source.walkTopDown()
        .maxDepth(MAX_MEMORY_IMPORT_DEPTH)
        .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
        .filter { file ->
            file.relativeTo(source).invariantSeparatorsPath.split('/').none { segment ->
                segment.startsWith(".")
            }
        }
        .take(MAX_MEMORY_IMPORT_FILES + 1)
        .toList()
    require(files.size <= MAX_MEMORY_IMPORT_FILES) {
        "Too many files (max $MAX_MEMORY_IMPORT_FILES). Import a subdirectory instead."
    }
    files.map { file ->
        val relative = file.relativeTo(source).invariantSeparatorsPath
        file to "/$relative"
    }
}
