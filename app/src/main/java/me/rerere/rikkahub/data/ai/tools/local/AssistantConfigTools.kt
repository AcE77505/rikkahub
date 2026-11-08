package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.io.File
import kotlin.uuid.Uuid

private const val TOOL_LIST_ASSISTANTS = "list_assistants"
private const val TOOL_GET_ASSISTANT = "get_assistant"
private const val TOOL_UPDATE_ASSISTANT = "update_assistant"

/**
 * 修改其他助手的工具组。
 *
 * 当前对话所属的助手是只读的：可以列出来读，但不能通过 [TOOL_UPDATE_ASSISTANT] 改自己。
 * 工具只暴露提示词、预设对话和生成参数，不暴露模型、MCP、工作区等本机引用。
 */
internal fun buildAssistantConfigTools(
    settingsStore: SettingsStore,
    currentAssistantId: Uuid,
): List<Tool> = listOf(
    listAssistantsTool(settingsStore, currentAssistantId),
    getAssistantTool(settingsStore),
    updateAssistantTool(settingsStore, currentAssistantId),
)

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
