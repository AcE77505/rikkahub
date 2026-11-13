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
)

internal val TOOL_PROMPT_ENTRIES: List<ToolPromptEntry> = listOf(
    // 技能
    ToolPromptEntry("use_skill", ToolPromptCategory.SKILLS),
    ToolPromptEntry("export_skill", ToolPromptCategory.SKILLS),

    // 记忆
    ToolPromptEntry("memory_read", ToolPromptCategory.MEMORY),
    ToolPromptEntry("memory_write", ToolPromptCategory.MEMORY),
    ToolPromptEntry("memory_str_replace", ToolPromptCategory.MEMORY),
    ToolPromptEntry("memory_append", ToolPromptCategory.MEMORY),
    ToolPromptEntry("memory_delete", ToolPromptCategory.MEMORY),
    ToolPromptEntry("memory_list", ToolPromptCategory.MEMORY),

    // 搜索
    ToolPromptEntry("search_web", ToolPromptCategory.SEARCH),
    ToolPromptEntry("scrape_web", ToolPromptCategory.SEARCH),

    // 对话检索
    ToolPromptEntry("recent_chats", ToolPromptCategory.CONVERSATION),
    ToolPromptEntry("conversation_search", ToolPromptCategory.CONVERSATION),
    ToolPromptEntry("conversation_read", ToolPromptCategory.CONVERSATION),

    // 本地工具
    ToolPromptEntry("get_time_info", ToolPromptCategory.LOCAL_TOOLS),
    ToolPromptEntry("clipboard_tool", ToolPromptCategory.LOCAL_TOOLS),
    ToolPromptEntry("text_to_speech", ToolPromptCategory.LOCAL_TOOLS),
    ToolPromptEntry("get_screen_time", ToolPromptCategory.LOCAL_TOOLS),
    ToolPromptEntry("calendar_query", ToolPromptCategory.LOCAL_TOOLS),
    ToolPromptEntry("calendar_create", ToolPromptCategory.LOCAL_TOOLS),
    ToolPromptEntry("chart_display", ToolPromptCategory.LOCAL_TOOLS),
    ToolPromptEntry("ask_user", ToolPromptCategory.LOCAL_TOOLS),

    // 工作区工具（ssh_ 前缀复用基础工具的覆盖）
    ToolPromptEntry("read_file", ToolPromptCategory.WORKSPACE_TOOLS),
    ToolPromptEntry("write_file", ToolPromptCategory.WORKSPACE_TOOLS),
    ToolPromptEntry("edit_file", ToolPromptCategory.WORKSPACE_TOOLS),
    ToolPromptEntry("shell", ToolPromptCategory.WORKSPACE_TOOLS),
    ToolPromptEntry("shell_output", ToolPromptCategory.WORKSPACE_TOOLS),
    ToolPromptEntry("shell_kill", ToolPromptCategory.WORKSPACE_TOOLS),

    // 修改其他助手的工具
    ToolPromptEntry("list_assistants", ToolPromptCategory.ASSISTANT_CONFIG),
    ToolPromptEntry("get_assistant", ToolPromptCategory.ASSISTANT_CONFIG),
    ToolPromptEntry("update_assistant", ToolPromptCategory.ASSISTANT_CONFIG),
    ToolPromptEntry("list_assistant_memories", ToolPromptCategory.ASSISTANT_CONFIG),
    ToolPromptEntry("read_assistant_memory", ToolPromptCategory.ASSISTANT_CONFIG),
    ToolPromptEntry("write_assistant_memory", ToolPromptCategory.ASSISTANT_CONFIG),
    ToolPromptEntry("delete_assistant_memory", ToolPromptCategory.ASSISTANT_CONFIG),
    ToolPromptEntry("import_assistant_memory", ToolPromptCategory.ASSISTANT_CONFIG),
)
