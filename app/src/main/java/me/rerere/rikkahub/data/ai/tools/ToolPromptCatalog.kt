package me.rerere.rikkahub.data.ai.tools

/**
 * 「设置 → 工具提示词」里可编辑的工具分类。
 *
 * 只列出确实会向模型发送 description / system prompt 的内置工具；MCP 工具的描述由服务端提供，
 * 子代理工具的描述会动态列出当前可用的子代理，因此都不在这里开放。
 */
internal enum class ToolPromptCategory {
    SKILLS,
    MEMORY,
    SEARCH,
    CONVERSATION,
    LOCAL_TOOLS,
    WORKSPACE_TOOLS,
    ASSISTANT_CONFIG,
}

internal data class ToolPromptEntry(
    val name: String,
    val category: ToolPromptCategory,
    val label: String = name,
    val defaultDescription: String,
    val defaultSystemPrompt: String = "",
)

private fun desc(text: String): String = text.trimIndent().replace("\n", " ")

private fun prompt(text: String): String = text.trimIndent()

internal val TOOL_PROMPT_ENTRIES: List<ToolPromptEntry> = listOf(
    // 技能
    ToolPromptEntry(
        SKILL_VIEW_TOOL_NAME,
        ToolPromptCategory.SKILLS,
        defaultDescription = """
            Load and apply a skill to get specialized instructions or capabilities.
            Call this tool when the user's request matches one of the available skills.
            Pass `path` to read a supporting file (text is returned as text, images as image content).
        """.let(::desc),
        defaultSystemPrompt = prompt(
            """
            **Skills**
            You have access to the following skills. Use the `view_skill` tool to load a skill's instructions when the user's request matches.
            - Call `view_skill` with `name` to read `SKILL.md`. Pass `path` to read a file referenced by the skill; text files come back as text, image files (png/jpeg/webp/gif) come back as image content so you can look at them directly when the model supports images.
            - Only use paths that appear in the `SKILL.md` content or in the skill's file list. Do not guess or infer paths.
            - To run a skill's scripts or work with binary files, call `export_skill` to copy the skill into the local workspace (or the app sandbox when no local workspace is declared), then use the local shell and file tools on the exported files.
            <available_skills> is appended at runtime; saving an override replaces this whole prompt, including that list.
            """
        ),
    ),
    ToolPromptEntry(
        "export_skill",
        ToolPromptCategory.SKILLS,
        defaultDescription = """
            Copy a skill's files into the local workspace (or the app sandbox when no local workspace is declared) so you can run its scripts or process binary files.
            Defaults to exporting the whole skill to `skills/<skill-name>/`; pass `path` to export only one file or subdirectory, and `destination` to choose another directory inside that workspace.
        """.let(::desc),
    ),

    // 记忆
    ToolPromptEntry(
        "memory_read",
        ToolPromptCategory.MEMORY,
        defaultDescription = "Read one or more memory files. Returns each file's content and version.".let(::desc),
    ),
    ToolPromptEntry(
        "memory_write",
        ToolPromptCategory.MEMORY,
        defaultDescription = """
            Create a memory file or overwrite it entirely. content is the complete file; lines not included are removed.
            if_version: pass "new" for a new file, or the version returned by the most recent read or write of an existing file.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "memory_str_replace",
        ToolPromptCategory.MEMORY,
        defaultDescription = """
            Replace the single occurrence of old_str in a memory file with new_str. An empty new_str deletes it.
            Rejected when old_str matches zero or several places.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "memory_append",
        ToolPromptCategory.MEMORY,
        defaultDescription = "Append one line to the end of a memory file.".let(::desc),
    ),
    ToolPromptEntry(
        "memory_delete",
        ToolPromptCategory.MEMORY,
        defaultDescription = "Delete an entire memory file. Only use when the user explicitly asks to forget a whole topic.".let(::desc),
    ),
    ToolPromptEntry(
        "memory_list",
        ToolPromptCategory.MEMORY,
        defaultDescription = "List memory files (path, size, update time), optionally filtered by path prefix.".let(::desc),
    ),

    // 搜索
    ToolPromptEntry(
        "search_web",
        ToolPromptCategory.SEARCH,
        defaultDescription = """
            Search the web for up-to-date or specific information.
            Use this when the user asks for the latest news, current facts, or needs verification.
            Do not treat result order as proof of freshness. Prefer primary sources and inspect
            each result's title, URL, publication date, and content before making a current claim.
            Use the optional publication-date and domain filters only when they match the question.
            If a date or primary source is missing, or sources conflict, run another focused search
            or use scrape_web to verify the most relevant source before answering.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "scrape_web",
        ToolPromptCategory.SEARCH,
        defaultDescription = """
            Scrape a URL for detailed page content.
            Use this when the user requests content from a specific page, when search snippets are insufficient,
            or when a current claim needs verification against a specific source.
            Avoid using it for common questions unless the user asks.
        """.let(::desc),
    ),

    // 对话检索
    ToolPromptEntry(
        "recent_chats",
        ToolPromptCategory.CONVERSATION,
        defaultDescription = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "conversation_search",
        ToolPromptCategory.CONVERSATION,
        defaultDescription = """
            Full-text search across the user's past conversations to recall specific information they mentioned before.
            Use focused keywords. Run multiple searches with different keywords if needed.
            Each result includes the conversation title, a snippet with matched keywords wrapped in [brackets], and the date.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "conversation_read",
        ToolPromptCategory.CONVERSATION,
        defaultDescription = """
            Read the messages of one conversation in full, by position.
            Use it when a message you were given was cut off, or a search snippet needs its surrounding context.
            Positions are the #numbers shown next to each message.
        """.let(::desc),
    ),

    // 本地工具
    ToolPromptEntry(
        "get_time_info",
        ToolPromptCategory.LOCAL_TOOLS,
        defaultDescription = """
            Get the current local date and time info from the device.
            Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "clipboard_tool",
        ToolPromptCategory.LOCAL_TOOLS,
        defaultDescription = """
            Read or write plain text from the device clipboard.
            Use action: read or write. For write, provide text.
            Do NOT write to the clipboard unless the user has explicitly requested it.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "text_to_speech",
        ToolPromptCategory.LOCAL_TOOLS,
        defaultDescription = """
            Speak text aloud to the user using the device's text-to-speech engine.
            Use this when the user asks you to read something aloud, or when audio output is appropriate.
            The tool returns immediately; audio plays in the background on the device.
            Provide natural, readable text without markdown formatting.
        """.let(::desc),
        // 默认 system prompt 由当前 TTS provider 的语气引导决定，这里是空的
    ),
    ToolPromptEntry(
        "get_screen_time",
        ToolPromptCategory.LOCAL_TOOLS,
        defaultDescription = """
            Get the user's app screen usage (screen time) over a time range.
            Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week).
            Returns the total foreground time and a per-app breakdown sorted by usage time (descending).
            The device timezone is '{timezone}'; times without an explicit offset are interpreted in this timezone.
            Requires the 'Usage access' special permission; if it is not granted, the device's usage
            access settings page is opened automatically and an error is returned.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "calendar_query",
        ToolPromptCategory.LOCAL_TOOLS,
        defaultDescription = """
            Query calendar events on the user's device within a time range.
            Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week/month).
            Returns a list of events with title, description, location, start/end times, and calendar info.
            The device timezone is '{timezone}'; times without an explicit offset are interpreted in this timezone.
            Requires the 'Calendar' permission; if it is not granted, an error is returned and the
            permission request is triggered automatically.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "calendar_create",
        ToolPromptCategory.LOCAL_TOOLS,
        defaultDescription = """
            Create a new calendar event on the user's device.
            Requires title and start time at minimum. End time defaults to 1 hour after start.
            The device timezone is '{timezone}'; times without an explicit offset are interpreted in this timezone.
            Requires the 'Calendar' permission; if it is not granted, an error is returned and the
            permission request is triggered automatically.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "chart_display",
        ToolPromptCategory.LOCAL_TOOLS,
        defaultDescription = """
            Display a line, bar, or scatter chart to the user inside the chat.
            For line and bar charts, every series must use 'values' (numbers aligned by position with x_axis.data)
            and must not use 'points'. For scatter charts, every series must use 'points' ({x, y} numbers)
            and must not use 'values'.
            Bar charts always start from 0, so axis 'min'/'max' are not allowed for bar charts.
            For line and bar charts the X axis is categorical: x_axis only supports 'title' and 'data',
            so x_axis 'min'/'max' and log scale are not allowed; use y_axis for range and log scale.
            Axis 'data' labels are only allowed on x_axis for line and bar charts.
            The chart is rendered for the user directly; you don't need to repeat the raw data in your reply.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "ask_user",
        ToolPromptCategory.LOCAL_TOOLS,
        defaultDescription = """
            Ask the user one or more questions when you need clarification, additional information, or confirmation.
            Each question can optionally provide a list of suggested options for the user to choose from.
            The user may provide a free-text answer for every question, including single and multi selection questions.
            For multi selection questions, custom text can be combined with selected options.
            The answers will be returned as a JSON object mapping question IDs to the user's responses.
        """.let(::desc),
    ),

    // 工作区工具（{workspace_root} 在运行时替换为实际目录）
    ToolPromptEntry(
        "read_file",
        ToolPromptCategory.WORKSPACE_TOOLS,
        defaultDescription = """
            Read a file. Relative paths resolve against {workspace_root}.
            Absolute paths are allowed, so the skills, uploads and built-in skills folders can be read directly.
            Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
        """.let(::desc),
    ),
    ToolPromptEntry(
        "write_file",
        ToolPromptCategory.WORKSPACE_TOOLS,
        defaultDescription = """
            Write a UTF-8 text file. Relative paths resolve against {workspace_root}.
            Parent directories are created as needed. Files outside the workspace directory require user approval.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "edit_file",
        ToolPromptCategory.WORKSPACE_TOOLS,
        defaultDescription = """
            Edit a UTF-8 text file. Relative paths resolve against {workspace_root}.
            Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
            If no exact match is found, whitespace-tolerant line matching is attempted automatically.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "shell",
        ToolPromptCategory.WORKSPACE_TOOLS,
        defaultDescription = """
            Run a shell command in the workspace or sandbox. Use cwd for a path relative to that directory.
            The command runs in the local Android shell (or the remote Linux shell for an SSH workspace).
        """.let(::desc),
    ),
    ToolPromptEntry(
        "shell_output",
        ToolPromptCategory.WORKSPACE_TOOLS,
        defaultDescription = """
            Read the output of a background command started by shell with run_in_background.
            Returns output written since the previous call, plus the exit code once it has finished.
            You are notified automatically when a background command finishes, so only use this when you
            need its output before then, such as checking that a server started.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "shell_kill",
        ToolPromptCategory.WORKSPACE_TOOLS,
        defaultDescription = """
            Stop a background command started by shell with run_in_background.
            The process and its descendants are terminated; output written so far stays in the log file.
        """.let(::desc),
    ),

    // 修改其他助手的工具
    ToolPromptEntry(
        "list_assistants",
        ToolPromptCategory.ASSISTANT_CONFIG,
        defaultDescription = """
            List all assistants on this device except the one used by the current conversation.
            Returns each assistant's id, name, effective system prompt preview, and preset dialogue group summaries.
            Use the returned id with get_assistant or update_assistant.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "get_assistant",
        ToolPromptCategory.ASSISTANT_CONFIG,
        defaultDescription = """
            Read one assistant's editable configuration: name, effective system prompt, system prompt presets,
            preset dialogue groups, and generation settings. Resolve the target by id (preferred) or exact name.
            This tool is read-only.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "update_assistant",
        ToolPromptCategory.ASSISTANT_CONFIG,
        defaultDescription = """
            Update another assistant's configuration. Resolve the target by id (preferred) or exact name.
            The current conversation's assistant cannot be modified. Scalar fields are optional.
            system_prompt_presets and preset_message_groups are upserts by id/title, not replacements.
            Use replace_* to clear a list and delete_* to remove items. Long prompts can be passed
            as an absolute local file path via system_prompt_file, prompt_file, or messages_file.
        """.let(::desc),
        defaultSystemPrompt = """
            You may manage other assistants with update_assistant only when the user asks.
            Use list_assistants or get_assistant first, and never modify the current assistant.
            Array fields are upserts by id/title, not replacements. Write at most one long preset or dialogue
            group per call; provider tool-call arguments have a size limit. If a prompt or dialogue group is
            too large, pass an absolute local file path via system_prompt_file, prompt_file, or messages_file
            instead of inlining the text. Use the replace_* flags only when you intentionally want to clear the existing list.
        """.let(::prompt),
    ),
    ToolPromptEntry(
        "list_assistant_memories",
        ToolPromptCategory.ASSISTANT_CONFIG,
        defaultDescription = """
            List another assistant's memory files. Returns path, title, description, version and update time.
            Use the returned path with read_assistant_memory, write_assistant_memory or delete_assistant_memory.
            The current conversation's assistant cannot be accessed.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "read_assistant_memory",
        ToolPromptCategory.ASSISTANT_CONFIG,
        defaultDescription = """
            Read one or more memory files of another assistant. Returns each file's content and version.
            Pass at most 20 paths per call, e.g. ["/topics/food.md"].
        """.let(::desc),
    ),
    ToolPromptEntry(
        "write_assistant_memory",
        ToolPromptCategory.ASSISTANT_CONFIG,
        defaultDescription = """
            Create or overwrite a memory file of another assistant. content is the complete file.
            Instead of inlining long content, pass content_file with an absolute path readable by the app;
            the file is read as UTF-8 (max 1 MiB) and used only when content is absent.
            if_version: "new" for a new file, or the version returned by the latest read/write; omit to allow upsert.
            Memory files are limited to 48 KiB each.
        """.let(::desc),
    ),
    ToolPromptEntry(
        "delete_assistant_memory",
        ToolPromptCategory.ASSISTANT_CONFIG,
        defaultDescription = "Delete one memory file of another assistant. Pass if_version to avoid deleting a file that changed.".let(::desc),
    ),
    ToolPromptEntry(
        "import_assistant_memory",
        ToolPromptCategory.ASSISTANT_CONFIG,
        defaultDescription = """
            Import memory files from a local path into another assistant's memory library.
            source is an absolute path to a single .md file or a directory (scanned up to 3 levels, at most 200 files).
            For a single file, path overrides the target path (default /<file name>.md).
            For a directory, files keep their relative paths.
            overwrite=false (default) skips files that already exist; overwrite=true replaces them.
            Each file is limited to 48 KiB.
        """.let(::desc),
    ),
)
