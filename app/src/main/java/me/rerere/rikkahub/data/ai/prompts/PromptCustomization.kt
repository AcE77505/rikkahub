package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.ai.transformers.DEFAULT_WORKSPACE_PROMPT
import me.rerere.rikkahub.data.datastore.Settings

/** 工作区提示词模板与当前内置默认不同（旧版本保存下来的旧模板也算）。 */
internal val Settings.hasCustomizedWorkspacePrompt: Boolean
    get() = workspacePrompt.trim() != DEFAULT_WORKSPACE_PROMPT.trim()

/** 该工具的描述或系统提示词是否有用户覆盖；改回默认时保存会自动移除覆盖。 */
internal fun Settings.hasCustomizedToolPrompt(toolName: String): Boolean =
    toolName in toolDescriptionOverrides || toolName in toolSystemPromptOverrides
