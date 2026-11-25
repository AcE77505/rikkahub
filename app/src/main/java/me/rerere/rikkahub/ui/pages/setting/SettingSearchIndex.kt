package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.TOOL_PROMPT_ENTRIES
import me.rerere.rikkahub.data.ai.tools.ToolPromptCategory
import kotlin.uuid.Uuid

/**
 * 设置主页搜索用的目录项。
 *
 * [title] 和 [description] 用于匹配，[path] 会代替原来的描述文字显示在搜索结果里。
 * 搜索不包含设置项的当前取值，例如滑块上的数字或用户选中的选项。
 */
data class SettingSearchEntry(
    val title: String,
    val description: String,
    val path: String,
    val target: Screen,
    /** 设置项对应的 AI 可见工具英文名，也参与搜索匹配 */
    val toolNames: List<String> = emptyList(),
)

/** 用 “页面 / 分组” 这样的层级路径描述设置项在设置里的位置。 */
private fun settingPath(vararg segments: String): String = segments.joinToString(" / ")

@Composable
private fun searchEntry(
    @StringRes title: Int,
    path: String,
    target: Screen,
    @StringRes description: Int? = null,
    toolNames: List<String> = emptyList(),
): SettingSearchEntry = SettingSearchEntry(
    title = stringResource(title),
    description = description?.let { stringResource(it) }.orEmpty(),
    path = path,
    target = target,
    toolNames = toolNames,
)

/** 关键字命中标题或描述即算匹配；标题开头命中的排在前面。 */
fun List<SettingSearchEntry>.filterBySettingQuery(query: String): List<SettingSearchEntry> {
    val keyword = query.trim()
    if (keyword.isBlank()) return emptyList()
    val tokens = keyword.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
    if (tokens.isEmpty()) return emptyList()
    val loweredKeyword = keyword.lowercase()
    return mapNotNull { entry ->
        val title = entry.title.lowercase()
        val description = entry.description.lowercase()
        val toolNames = entry.toolNames.joinToString(" ").lowercase()
        val matched = tokens.all { title.contains(it) || description.contains(it) || toolNames.contains(it) }
        if (!matched) {
            null
        } else {
            val score = when {
                title.startsWith(loweredKeyword) -> 0
                title.contains(loweredKeyword) -> 1
                else -> 2
            }
            entry to score
        }
    }.sortedBy { it.second }.map { it.first }
}

/**
 * 设置里所有可搜索的项。新增设置页或设置项时同步补在这里。
 */
@Composable
fun rememberSettingSearchEntries(assistantId: Uuid): List<SettingSearchEntry> {
    val groupGeneral = stringResource(R.string.setting_page_general_settings)
    val groupModels = stringResource(R.string.setting_page_model_and_services)
    val groupData = stringResource(R.string.setting_page_data_settings)
    val groupAbout = stringResource(R.string.setting_page_about)

    val preferences = stringResource(R.string.setting_page_preferences)
    val prefTheme = stringResource(R.string.setting_page_preferences_theme)
    val prefNotification = stringResource(R.string.setting_page_preferences_notification)
    val prefGeneral = stringResource(R.string.setting_page_preferences_general)
    val prefUi = stringResource(R.string.setting_page_preferences_ui)
    val prefNetwork = stringResource(R.string.setting_page_preferences_network)

    val modelSettings = stringResource(R.string.setting_model_page_title)
    val themeSettings = stringResource(R.string.setting_page_theme_setting)
    val providers = stringResource(R.string.setting_provider_page_title)
    val searchSettings = stringResource(R.string.setting_page_search_title)
    val speechSettings = stringResource(R.string.speech_page_title)
    val mediaSettings = stringResource(R.string.setting_page_media)
    val mcpSettings = stringResource(R.string.setting_mcp_page_title)
    val webSettings = stringResource(R.string.setting_page_web_server)
    val storageSettings = stringResource(R.string.setting_page_chat_storage)
    val tools = stringResource(R.string.setting_page_tools)
    val toolPrompts = stringResource(R.string.setting_page_tool_prompts)
    val subAgents = stringResource(R.string.subagent_page_title)
    val workspacePrompt = stringResource(R.string.setting_tools_workspace_prompt)
    val extensions = stringResource(R.string.setting_page_extensions)
    val assistant = stringResource(R.string.setting_page_assistant)
    val promptCategories = mapOf(
        ToolPromptCategory.SKILLS to stringResource(R.string.tool_prompts_category_skills),
        ToolPromptCategory.MEMORY to stringResource(R.string.tool_prompts_category_memory),
        ToolPromptCategory.SEARCH to stringResource(R.string.tool_prompts_category_search),
        ToolPromptCategory.CONVERSATION to stringResource(R.string.tool_prompts_category_conversation),
        ToolPromptCategory.LOCAL_TOOLS to stringResource(R.string.tool_prompts_category_local_tools),
        ToolPromptCategory.WORKSPACE_TOOLS to stringResource(R.string.tool_prompts_category_workspace_tools),
        ToolPromptCategory.DYNAMIC_TOOLS to stringResource(R.string.tool_prompts_category_dynamic_tools),
        ToolPromptCategory.ASSISTANT_CONFIG to stringResource(R.string.tool_prompts_category_assistant_config),
    )

    val entries = mutableListOf<SettingSearchEntry>()

    // 设置主页
    entries += searchEntry(
        R.string.setting_page_color_mode,
        settingPath(groupGeneral),
        Screen.Setting,
    )
    entries += searchEntry(
        R.string.setting_page_preferences,
        settingPath(groupGeneral),
        Screen.SettingPreferences,
        R.string.setting_page_preferences_desc,
    )
    entries += searchEntry(
        R.string.setting_page_assistant,
        settingPath(groupGeneral),
        Screen.Assistant,
        R.string.setting_page_assistant_desc,
    )
    entries += searchEntry(
        R.string.setting_page_extensions,
        settingPath(groupGeneral),
        Screen.Extensions,
        R.string.setting_page_extensions_desc,
    )
    entries += searchEntry(
        R.string.setting_page_tools,
        settingPath(groupGeneral),
        Screen.SettingTools,
        R.string.setting_page_tools_desc,
    )
    entries += searchEntry(
        R.string.setting_page_tool_prompts,
        settingPath(groupGeneral),
        Screen.SettingToolPrompts,
        R.string.setting_page_tool_prompts_desc,
    )
    entries += searchEntry(
        R.string.setting_page_default_model,
        settingPath(groupModels),
        Screen.SettingModels,
        R.string.setting_page_default_model_desc,
    )
    entries += searchEntry(
        R.string.setting_page_providers,
        settingPath(groupModels),
        Screen.SettingProvider,
        R.string.setting_page_providers_desc,
    )
    entries += searchEntry(
        R.string.setting_page_search_service,
        settingPath(groupModels),
        Screen.SettingSearch,
        R.string.setting_page_search_service_desc,
    )
    entries += searchEntry(
        R.string.setting_page_tts_service,
        settingPath(groupModels),
        Screen.SettingSpeech,
        R.string.setting_page_tts_service_desc,
    )
    entries += searchEntry(
        R.string.setting_page_media,
        settingPath(groupModels),
        Screen.SettingMedia,
        R.string.setting_page_media_desc,
    )
    entries += searchEntry(
        R.string.setting_page_mcp,
        settingPath(groupModels),
        Screen.SettingMcp,
        R.string.setting_page_mcp_desc,
    )
    entries += searchEntry(
        R.string.setting_page_web_server,
        settingPath(groupModels),
        Screen.SettingWeb,
        R.string.setting_page_web_server_desc,
    )
    entries += searchEntry(
        R.string.setting_page_data_backup,
        settingPath(groupData),
        Screen.Backup,
        R.string.setting_page_data_backup_desc,
    )
    entries += searchEntry(
        R.string.setting_page_chat_storage,
        settingPath(groupData),
        Screen.SettingFiles,
    )
    entries += searchEntry(
        R.string.setting_page_about,
        settingPath(groupAbout),
        Screen.SettingAbout,
        R.string.setting_page_about_desc,
    )
    entries += searchEntry(
        R.string.setting_page_request_logs,
        settingPath(groupAbout),
        Screen.Log,
        R.string.setting_page_request_logs_desc,
    )

    // 偏好设置
    entries += searchEntry(
        R.string.setting_page_preferences_theme,
        settingPath(preferences),
        Screen.SettingPreferencesTheme,
        R.string.setting_page_preferences_theme_desc,
    )
    entries += searchEntry(
        R.string.setting_page_preferences_notification,
        settingPath(preferences),
        Screen.SettingPreferencesNotification,
        R.string.setting_page_preferences_notification_desc,
    )
    entries += searchEntry(
        R.string.setting_page_preferences_general,
        settingPath(preferences),
        Screen.SettingPreferencesGeneral,
        R.string.setting_page_preferences_general_desc,
    )
    entries += searchEntry(
        R.string.setting_page_preferences_ui,
        settingPath(preferences),
        Screen.SettingPreferencesUI,
        R.string.setting_page_preferences_ui_desc,
    )
    entries += searchEntry(
        R.string.setting_page_preferences_network,
        settingPath(preferences),
        Screen.SettingPreferencesNetwork,
        R.string.setting_page_preferences_network_desc,
    )

    // 偏好设置 / 主题
    entries += searchEntry(
        R.string.setting_page_dynamic_color,
        settingPath(preferences, prefTheme),
        Screen.SettingPreferencesTheme,
        R.string.setting_page_dynamic_color_desc,
    )
    entries += searchEntry(
        R.string.setting_page_theme_setting,
        settingPath(preferences, prefTheme),
        Screen.SettingTheme,
        R.string.setting_page_theme_setting_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_amoled_dark_mode_title,
        settingPath(preferences, prefTheme),
        Screen.SettingPreferencesTheme,
        R.string.setting_display_page_amoled_dark_mode_desc,
    )

    // 偏好设置 / 通知
    entries += searchEntry(
        R.string.setting_display_page_show_updates_title,
        settingPath(preferences, prefNotification),
        Screen.SettingPreferencesNotification,
        R.string.setting_display_page_show_updates_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_notification_message_generated,
        settingPath(preferences, prefNotification),
        Screen.SettingPreferencesNotification,
        R.string.setting_display_page_notification_message_generated_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_live_update_notification,
        settingPath(preferences, prefNotification),
        Screen.SettingPreferencesNotification,
        R.string.setting_display_page_live_update_notification_desc,
    )
    entries += searchEntry(
        R.string.setting_page_preferences_notification_floating_window,
        settingPath(preferences, prefNotification),
        Screen.SettingPreferencesNotification,
        R.string.setting_page_preferences_notification_floating_window_desc,
    )
    entries += searchEntry(
        R.string.setting_page_preferences_notification_overlay_permission,
        settingPath(preferences, prefNotification),
        Screen.SettingPreferencesNotification,
        R.string.setting_page_preferences_notification_overlay_permission_desc,
    )

    // 偏好设置 / 常规
    entries += searchEntry(
        R.string.setting_display_page_create_new_conversation_on_start_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_create_new_conversation_on_start_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_send_on_enter_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_send_on_enter_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_auto_compress_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_auto_compress_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_auto_compress_threshold_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_auto_compress_threshold_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_show_message_jumper_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_show_message_jumper_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_message_jumper_position_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_message_jumper_position_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_enable_auto_scroll_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_enable_auto_scroll_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_enable_blur_effect_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_enable_blur_effect_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_background_effect_type,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
    )
    entries += searchEntry(
        R.string.setting_display_page_enable_message_generation_haptic_effect_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_enable_message_generation_haptic_effect_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_skip_crop_image_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_skip_crop_image_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_paste_long_text_as_file_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_paste_long_text_as_file_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_paste_long_text_threshold_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
    )
    entries += searchEntry(
        R.string.setting_display_page_volume_key_scroll_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_volume_key_scroll_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_volume_key_scroll_ratio,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
    )
    entries += searchEntry(
        R.string.setting_tts_page_default_playback_speed,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_tts_page_default_playback_speed_description,
    )
    entries += searchEntry(
        R.string.setting_display_page_tts_only_read_quoted_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_tts_only_read_quoted_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_tts_read_outside_brackets_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_tts_read_outside_brackets_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_auto_play_tts_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_auto_play_tts_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_voice_listen_while_replying_title,
        settingPath(preferences, prefGeneral),
        Screen.SettingPreferencesGeneral,
        R.string.setting_display_page_voice_listen_while_replying_desc,
    )

    // 偏好设置 / 界面
    entries += searchEntry(
        R.string.setting_display_page_show_user_avatar_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_show_user_avatar_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_show_assistant_bubble_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_show_assistant_bubble_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_bubble_opacity_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
    )
    entries += searchEntry(
        R.string.setting_display_page_chat_list_model_icon_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_chat_list_model_icon_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_show_model_name_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_show_model_name_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_show_datetime_in_message_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_show_datetime_in_message_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_show_token_usage_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_show_token_usage_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_show_thinking_content_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_show_thinking_content_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_auto_collapse_thinking_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_auto_collapse_thinking_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_enable_latex_rendering_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_enable_latex_rendering_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_enable_quote_highlight_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_enable_quote_highlight_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_chat_font_family_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
    )
    entries += searchEntry(
        R.string.setting_display_page_custom_font_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_custom_font_not_imported,
    )
    entries += searchEntry(
        R.string.setting_display_page_font_size_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
    )
    entries += searchEntry(
        R.string.setting_display_page_code_block_auto_wrap_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_code_block_auto_wrap_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_code_block_auto_collapse_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_code_block_auto_collapse_desc,
    )
    entries += searchEntry(
        R.string.setting_display_page_show_line_numbers_title,
        settingPath(preferences, prefUi),
        Screen.SettingPreferencesUI,
        R.string.setting_display_page_show_line_numbers_desc,
    )

    // 偏好设置 / 网络
    entries += searchEntry(
        R.string.setting_page_preferences_network_auto_retry,
        settingPath(preferences, prefNetwork),
        Screen.SettingPreferencesNetwork,
        R.string.setting_page_preferences_network_auto_retry_desc,
    )
    entries += searchEntry(
        R.string.setting_page_preferences_network_user_agent,
        settingPath(preferences, prefNetwork),
        Screen.SettingPreferencesNetwork,
    )
    entries += searchEntry(
        R.string.setting_page_preferences_network_proxy,
        settingPath(preferences, prefNetwork),
        Screen.SettingPreferencesNetwork,
        R.string.setting_page_preferences_network_proxy_desc,
    )

    // 模型设置
    entries += searchEntry(
        R.string.setting_model_page_chat_model,
        settingPath(modelSettings),
        Screen.SettingModels,
        R.string.setting_model_page_chat_model_desc,
    )
    entries += searchEntry(
        R.string.setting_model_page_fast_model,
        settingPath(modelSettings),
        Screen.SettingModels,
        R.string.setting_model_page_fast_model_desc,
    )
    entries += searchEntry(
        R.string.assistant_page_thinking_budget,
        settingPath(modelSettings),
        Screen.SettingModels,
    )
    entries += searchEntry(
        R.string.setting_model_page_enable_auto_title,
        settingPath(modelSettings),
        Screen.SettingModels,
    )
    entries += searchEntry(
        R.string.setting_model_page_enable_suggestion,
        settingPath(modelSettings),
        Screen.SettingModels,
    )
    entries += searchEntry(
        R.string.setting_model_page_memory_model,
        settingPath(modelSettings),
        Screen.SettingModels,
        R.string.setting_model_page_memory_model_desc,
    )
    entries += searchEntry(
        R.string.setting_model_page_translate_model,
        settingPath(modelSettings),
        Screen.SettingModels,
        R.string.setting_model_page_translate_model_desc,
    )
    entries += searchEntry(
        R.string.setting_model_page_ocr_model,
        settingPath(modelSettings),
        Screen.SettingModels,
        R.string.setting_model_page_ocr_model_desc,
    )
    entries += searchEntry(
        R.string.setting_model_page_prompt_translation,
        settingPath(modelSettings),
        Screen.SettingModels,
    )
    entries += searchEntry(
        R.string.setting_model_page_prompt_title,
        settingPath(modelSettings),
        Screen.SettingModels,
    )
    entries += searchEntry(
        R.string.setting_model_page_prompt_suggestion,
        settingPath(modelSettings),
        Screen.SettingModels,
    )
    entries += searchEntry(
        R.string.setting_model_page_prompt_ocr,
        settingPath(modelSettings),
        Screen.SettingModels,
    )

    // 主题
    entries += searchEntry(
        R.string.setting_theme_page_preset_themes,
        settingPath(preferences, prefTheme, themeSettings),
        Screen.SettingTheme,
    )
    entries += searchEntry(
        R.string.setting_theme_page_custom_themes,
        settingPath(preferences, prefTheme, themeSettings),
        Screen.SettingTheme,
    )
    entries += searchEntry(
        R.string.setting_theme_page_add_theme,
        settingPath(preferences, prefTheme, themeSettings),
        Screen.SettingTheme,
    )
    entries += searchEntry(
        R.string.setting_theme_page_import_theme,
        settingPath(preferences, prefTheme, themeSettings),
        Screen.SettingTheme,
    )

    // 提供商
    entries += searchEntry(
        R.string.setting_provider_page_search_providers,
        settingPath(providers),
        Screen.SettingProvider,
    )
    entries += searchEntry(
        R.string.setting_provider_page_recommend,
        settingPath(providers),
        Screen.SettingProvider,
    )
    entries += searchEntry(
        R.string.setting_provider_page_add_provider,
        settingPath(providers),
        Screen.SettingProvider,
    )
    entries += searchEntry(
        R.string.setting_provider_page_import_dialog_title,
        settingPath(providers),
        Screen.SettingProvider,
    )

    // 搜索服务
    entries += searchEntry(
        R.string.setting_page_search_add_provider,
        settingPath(searchSettings),
        Screen.SettingSearch,
    )
    entries += searchEntry(
        R.string.setting_page_search_common_options,
        settingPath(searchSettings),
        Screen.SettingSearch,
    )
    entries += searchEntry(
        R.string.setting_page_search_result_size,
        settingPath(searchSettings),
        Screen.SettingSearch,
    )
    entries += searchEntry(
        R.string.setting_page_search_test,
        settingPath(searchSettings),
        Screen.SettingSearch,
    )

    // 语音服务
    entries += searchEntry(
        R.string.speech_tab_tts,
        settingPath(speechSettings),
        Screen.SettingSpeech,
    )
    entries += searchEntry(
        R.string.speech_tab_asr,
        settingPath(speechSettings),
        Screen.SettingSpeech,
    )
    entries += searchEntry(
        R.string.setting_tts_page_add_provider,
        settingPath(speechSettings),
        Screen.SettingSpeech,
    )
    entries += searchEntry(
        R.string.setting_asr_page_add_provider,
        settingPath(speechSettings),
        Screen.SettingSpeech,
    )

    // 媒体
    entries += searchEntry(
        R.string.setting_media_page_providers,
        settingPath(mediaSettings),
        Screen.SettingMedia,
    )
    entries += searchEntry(
        R.string.setting_media_page_add_provider,
        settingPath(mediaSettings),
        Screen.SettingMedia,
    )
    entries += searchEntry(
        R.string.setting_media_page_asset_storage,
        settingPath(mediaSettings),
        Screen.SettingMedia,
    )

    // MCP
    entries += searchEntry(
        R.string.setting_mcp_page_import_title,
        settingPath(mcpSettings),
        Screen.SettingMcp,
        R.string.setting_mcp_page_import_desc,
    )

    // Web 服务器
    entries += searchEntry(
        R.string.setting_page_web_server_port,
        settingPath(webSettings),
        Screen.SettingWeb,
        R.string.setting_page_web_server_port_desc,
    )
    entries += searchEntry(
        R.string.setting_page_web_server_localhost_only,
        settingPath(webSettings),
        Screen.SettingWeb,
        R.string.setting_page_web_server_localhost_only_desc,
    )
    entries += searchEntry(
        R.string.setting_page_web_server_jwt_enable,
        settingPath(webSettings),
        Screen.SettingWeb,
        R.string.setting_page_web_server_jwt_enable_desc,
    )
    entries += searchEntry(
        R.string.setting_page_web_server_password,
        settingPath(webSettings),
        Screen.SettingWeb,
        R.string.setting_page_web_server_password_desc,
    )

    // 文件管理
    entries += searchEntry(
        R.string.setting_files_page_clean_content_description,
        settingPath(storageSettings),
        Screen.SettingFiles,
    )

    // 工具
    entries += searchEntry(
        R.string.setting_tools_workspace,
        settingPath(tools, stringResource(R.string.setting_tools_workspace)),
        Screen.Workspaces,
    )
    entries += searchEntry(
        R.string.workspace_tool_read_file,
        settingPath(tools, stringResource(R.string.setting_tools_group_files)),
        Screen.SettingTools,
        R.string.workspace_tool_read_file_desc,
        toolNames = listOf("read_file"),
    )
    entries += searchEntry(
        R.string.workspace_tool_write_file,
        settingPath(tools, stringResource(R.string.setting_tools_group_files)),
        Screen.SettingTools,
        R.string.workspace_tool_write_file_desc,
        toolNames = listOf("write_file"),
    )
    entries += searchEntry(
        R.string.workspace_tool_edit_file,
        settingPath(tools, stringResource(R.string.setting_tools_group_files)),
        Screen.SettingTools,
        R.string.workspace_tool_edit_file_desc,
        toolNames = listOf("edit_file"),
    )
    entries += searchEntry(
        R.string.workspace_tool_shell,
        settingPath(tools, stringResource(R.string.setting_tools_group_files)),
        Screen.SettingTools,
        R.string.workspace_tool_shell_desc,
        toolNames = listOf("shell", "shell_output", "shell_kill"),
    )
    entries += searchEntry(
        R.string.tool_assistant_config,
        settingPath(tools, stringResource(R.string.setting_tools_group_advanced)),
        Screen.SettingTools,
        R.string.tool_assistant_config_desc,
        toolNames = listOf(
            "list_assistants",
            "get_assistant",
            "update_assistant",
            "list_assistant_memories",
            "read_assistant_memory",
            "write_assistant_memory",
            "delete_assistant_memory",
            "import_assistant_memory",
        ),
    )
    entries += searchEntry(
        R.string.setting_tools_shell_overtime_threshold,
        settingPath(tools, stringResource(R.string.setting_tools_group_files)),
        Screen.SettingTools,
        R.string.setting_tools_shell_overtime_desc,
    )
    entries += searchEntry(
        R.string.setting_tools_shell_auto_stop,
        settingPath(tools, stringResource(R.string.setting_tools_group_files)),
        Screen.SettingTools,
        R.string.setting_tools_shell_auto_stop_desc,
    )
    entries += searchEntry(
        R.string.setting_tools_shell_background_enabled,
        settingPath(tools, stringResource(R.string.setting_tools_group_files)),
        Screen.SettingTools,
        R.string.setting_tools_shell_background_enabled_desc,
    )
    entries += searchEntry(
        R.string.setting_tools_shell_background_limit,
        settingPath(tools, stringResource(R.string.setting_tools_group_files)),
        Screen.SettingTools,
        R.string.setting_tools_shell_background_limit_desc,
    )
    entries += searchEntry(
        R.string.setting_tools_shell_background_notify,
        settingPath(tools, stringResource(R.string.setting_tools_group_files)),
        Screen.SettingTools,
        R.string.setting_tools_shell_background_notify_desc,
    )
    entries += searchEntry(
        R.string.setting_tools_tool_approval,
        settingPath(tools, stringResource(R.string.setting_tools_group_files)),
        Screen.SettingTools,
        R.string.setting_tools_tool_approval_desc,
    )
    entries += searchEntry(
        R.string.setting_tools_tool_identifier_display_title,
        settingPath(tools, stringResource(R.string.setting_tools_group_display)),
        Screen.SettingTools,
        R.string.setting_tools_tool_identifier_display_desc,
    )
    entries += SettingSearchEntry(
        title = stringResource(R.string.setting_tools_subagents),
        description = stringResource(R.string.subagent_intro),
        path = settingPath(tools, stringResource(R.string.setting_tools_group_advanced)),
        target = Screen.SettingSubAgents,
    )
    entries += searchEntry(
        R.string.setting_tools_local_tools,
        settingPath(tools, stringResource(R.string.setting_tools_group_common)),
        Screen.SettingLocalTools,
        R.string.setting_tools_local_tools_desc,
        toolNames = listOf(
            "get_time_info",
            "clipboard_tool",
            "text_to_speech",
            "ask_user",
            "get_screen_time",
            "calendar_query",
            "calendar_create",
            "chart_display",
            "system_intent",
            "get_current_location",
        ),
    )
    entries += searchEntry(
        R.string.assistant_page_local_tools_time_info_title,
        settingPath(tools, stringResource(R.string.setting_tools_local_tools)),
        Screen.SettingLocalTools,
        R.string.assistant_page_local_tools_time_info_desc,
        toolNames = listOf("get_time_info"),
    )
    entries += searchEntry(
        R.string.assistant_page_local_tools_clipboard_title,
        settingPath(tools, stringResource(R.string.setting_tools_local_tools)),
        Screen.SettingLocalTools,
        R.string.assistant_page_local_tools_clipboard_desc,
        toolNames = listOf("clipboard_tool"),
    )
    entries += searchEntry(
        R.string.assistant_page_local_tools_tts_title,
        settingPath(tools, stringResource(R.string.setting_tools_local_tools)),
        Screen.SettingLocalTools,
        R.string.assistant_page_local_tools_tts_desc,
        toolNames = listOf("text_to_speech"),
    )
    entries += searchEntry(
        R.string.assistant_page_local_tools_ask_user_title,
        settingPath(tools, stringResource(R.string.setting_tools_local_tools)),
        Screen.SettingLocalTools,
        R.string.assistant_page_local_tools_ask_user_desc,
        toolNames = listOf("ask_user"),
    )
    entries += searchEntry(
        R.string.assistant_page_local_tools_screen_time_title,
        settingPath(tools, stringResource(R.string.setting_tools_local_tools)),
        Screen.SettingLocalTools,
        R.string.assistant_page_local_tools_screen_time_desc,
        toolNames = listOf("get_screen_time"),
    )
    entries += searchEntry(
        R.string.assistant_page_local_tools_calendar_title,
        settingPath(tools, stringResource(R.string.setting_tools_local_tools)),
        Screen.SettingLocalTools,
        R.string.assistant_page_local_tools_calendar_desc,
        toolNames = listOf("calendar_query", "calendar_create"),
    )
    entries += searchEntry(
        R.string.assistant_page_local_tools_chart_display_title,
        settingPath(tools, stringResource(R.string.setting_tools_local_tools)),
        Screen.SettingLocalTools,
        R.string.assistant_page_local_tools_chart_display_desc,
        toolNames = listOf("chart_display"),
    )
    entries += searchEntry(
        R.string.assistant_page_local_tools_system_intent_title,
        settingPath(tools, stringResource(R.string.setting_tools_local_tools)),
        Screen.SettingLocalTools,
        R.string.assistant_page_local_tools_system_intent_desc,
        toolNames = listOf("system_intent"),
    )
    entries += searchEntry(
        R.string.assistant_page_local_tools_location_title,
        settingPath(tools, stringResource(R.string.setting_tools_local_tools)),
        Screen.SettingLocalTools,
        R.string.assistant_page_local_tools_location_desc,
        toolNames = listOf("get_current_location"),
    )
    entries += searchEntry(
        R.string.setting_tools_terminal_dir,
        settingPath(tools, stringResource(R.string.setting_tools_workspace)),
        Screen.SettingTerminalDirectory(),
    )
    entries += searchEntry(
        R.string.setting_path_open_title,
        settingPath(tools, stringResource(R.string.setting_path_open_title)),
        Screen.SettingTools,
    )
    // 工具提示词
    entries += searchEntry(
        R.string.tool_prompts_category_skills,
        settingPath(toolPrompts),
        Screen.SettingToolPrompts,
    )
    entries += searchEntry(
        R.string.tool_prompts_category_memory,
        settingPath(toolPrompts),
        Screen.SettingToolPrompts,
    )
    entries += searchEntry(
        R.string.tool_prompts_category_search,
        settingPath(toolPrompts),
        Screen.SettingToolPrompts,
    )
    entries += searchEntry(
        R.string.tool_prompts_category_conversation,
        settingPath(toolPrompts),
        Screen.SettingToolPrompts,
    )
    entries += searchEntry(
        R.string.tool_prompts_category_local_tools,
        settingPath(toolPrompts),
        Screen.SettingToolPrompts,
    )
    entries += searchEntry(
        R.string.tool_prompts_category_workspace_tools,
        settingPath(toolPrompts),
        Screen.SettingToolPrompts,
    )
    entries += searchEntry(
        R.string.tool_prompts_category_assistant_config,
        settingPath(toolPrompts),
        Screen.SettingToolPrompts,
    )
    // 工具提示词里的每个工具：标题本身就是英文名
    TOOL_PROMPT_ENTRIES.forEach { entry ->
        entries += SettingSearchEntry(
            title = entry.label,
            description = entry.defaultDescription,
            path = settingPath(toolPrompts, promptCategories.getValue(entry.category)),
            target = Screen.SettingToolPromptEdit(entry.name),
            toolNames = listOf(entry.name),
        )
    }

    // 子代理
    entries += searchEntry(
        R.string.subagent_default_model,
        settingPath(tools, subAgents),
        Screen.SettingSubAgents,
        R.string.subagent_default_model_desc,
    )
    entries += searchEntry(
        R.string.subagent_concurrency_limit,
        settingPath(tools, subAgents),
        Screen.SettingSubAgents,
        R.string.subagent_concurrency_desc,
    )
    entries += searchEntry(
        R.string.subagent_runs,
        settingPath(tools, subAgents),
        Screen.SubAgentRuns,
    )
    entries += searchEntry(
        R.string.subagent_default_tools,
        settingPath(tools, subAgents),
        Screen.SubAgentDefaultTools,
    )
    entries += searchEntry(
        R.string.subagent_new,
        settingPath(tools, subAgents),
        Screen.SettingSubAgents,
        R.string.subagent_new_desc,
    )

    // 工作区提示词（从「工具提示词」进入）
    entries += searchEntry(
        R.string.setting_tools_workspace_prompt,
        settingPath(toolPrompts, workspacePrompt),
        Screen.SettingWorkspacePrompt,
        R.string.setting_tools_workspace_prompt_desc,
    )
    entries += searchEntry(
        R.string.setting_workspace_prompt_preview,
        settingPath(toolPrompts, workspacePrompt),
        Screen.SettingWorkspacePrompt,
    )
    entries += searchEntry(
        R.string.setting_workspace_prompt_available_variables,
        settingPath(toolPrompts, workspacePrompt),
        Screen.SettingWorkspacePrompt,
    )

    // 助手 / 记忆
    entries += searchEntry(
        R.string.assistant_page_memory,
        settingPath(assistant, stringResource(R.string.assistant_page_memory)),
        Screen.AssistantMemory(assistantId.toString()),
        R.string.assistant_page_memory_desc,
        toolNames = listOf(
            "memory_read",
            "memory_write",
            "memory_str_replace",
            "memory_append",
            "memory_delete",
            "memory_list",
            "memory_search",
            "memory_export",
            "memory_import",
        ),
    )
    entries += searchEntry(
        R.string.assistant_page_recent_chats,
        settingPath(assistant, stringResource(R.string.assistant_page_memory)),
        Screen.AssistantMemory(assistantId.toString()),
        R.string.assistant_page_recent_chats_desc,
        toolNames = listOf("recent_chats", "conversation_search", "conversation_read"),
    )

    // 扩展管理
    entries += searchEntry(
        R.string.assistant_page_quick_messages,
        settingPath(extensions),
        Screen.QuickMessages,
        R.string.extensions_page_quick_messages_desc,
    )
    entries += searchEntry(
        R.string.extensions_page_prompts,
        settingPath(extensions),
        Screen.Prompts,
        R.string.extensions_page_prompts_desc,
    )
    entries += searchEntry(
        R.string.extensions_page_agent_skills,
        settingPath(extensions),
        Screen.Skills,
        R.string.extensions_page_agent_skills_desc,
    )
    entries += searchEntry(
        R.string.extensions_page_workspace,
        settingPath(extensions),
        Screen.Workspaces,
        R.string.extensions_page_workspace_desc,
    )

    return entries
}
