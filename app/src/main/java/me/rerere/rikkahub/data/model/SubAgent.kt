package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

/**
 * 子代理（Subagent）定义。
 *
 * 子代理不是 [Assistant]：它不出现在助手列表，也不能单独开聊天。
 * 它由主助手通过 `task` 工具派发，在独立上下文里完成一个任务，只把最终报告交回主助手。
 *
 * 定义是全局共享的，哪个助手能派发哪些子代理由 [Assistant.enabledSubAgents] 决定。
 */
@Serializable
data class SubAgent(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    /** 子代理自己的系统提示；不会继承主助手的角色设定 */
    val systemPrompt: String = "",
    /** null = 继承全局子代理模型，再回落到父轮模型 */
    val modelId: Uuid? = null,
    /** 思考深度；null = 继承父轮 */
    val reasoningLevel: ReasoningLevel? = null,
    /** 默认只读；写文件和执行命令需要在配置里显式放开 */
    val readOnly: Boolean = true,
    /** 允许的工具名；null 表示共享 [SubAgentToolDefaults.DEFAULT_ALLOWED_TOOLS] 模板 */
    val allowedTools: Set<String>? = null,
    /** 最大工具循环步数 */
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    /** 超时（秒） */
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** 派发时是否需要用户审批 */
    val requiresApproval: Boolean = true,
    /** 审批通过一次后，当前对话内是否记住；关闭则每次都询问 */
    val rememberApproval: Boolean = true,
    /** 自定义温度；null 表示跟随子代理对应助手的温度 */
    val temperature: Float? = null,
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 12
        const val MAX_MAX_STEPS = 64
        const val DEFAULT_TIMEOUT_SECONDS = 120
        const val MAX_TIMEOUT_SECONDS = 1800
        const val MAX_NAME_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 200
    }
}

/** 子代理默认工具模板；未自定义的 SubAgent 共享这一份，修改时从它复制 */
object SubAgentToolDefaults {
    data class ToolGroup(val title: String, val tools: List<String>)

    /** 默认只读模板：所有子代理未自定义 allowedTools 时共享 */
    val DEFAULT_ALLOWED_TOOLS: Set<String> = setOf(
        "read_file",
        "search_web",
        "scrape_web",
        "get_time_info",
        "use_skill",
        "chart_display",
    )

    /** 编辑页分组展示的工具名 */
    val TOOL_GROUPS: List<ToolGroup> = listOf(
        ToolGroup(
            title = "读取与搜索",
            tools = listOf("read_file", "search_web", "scrape_web", "get_time_info", "use_skill", "chart_display"),
        ),
        ToolGroup(
            title = "写入",
            tools = listOf("write_file", "edit_file"),
        ),
        ToolGroup(
            title = "执行",
            tools = listOf("shell"),
        ),
        ToolGroup(
            title = "设备与交互",
            tools = listOf("clipboard_tool", "text_to_speech", "get_screen_time", "eval_javascript", "calendar_query"),
        ),
    )

    /** 编辑页展示的全部工具名，顺序就是界面上的顺序 */
    val ALL_TOOLS: List<String> = TOOL_GROUPS.flatMap { it.tools }

    /** 写文件/执行命令类工具；只读子代理会自动过滤掉 */
    val WRITE_TOOLS: Set<String> = setOf("write_file", "edit_file", "shell")

    /** 把用户输入的其他工具名（MCP 等）拆成集合 */
    fun parseExtraTools(text: String): Set<String> = text
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}

/** 子代理默认的系统提示：配置为空时使用，保证子代理知道要返回可读的最终报告 */
val DEFAULT_SUBAGENT_SYSTEM_PROMPT = """    You are a focused sub-agent dispatched by a parent assistant to complete a single task.

    Rules:
    - Stay tightly scoped to the task you were given. Do not expand the scope.
    - Use tools to gather facts before answering when accuracy matters.
    - Return a clear, structured final report as your last message. That report is the only
      thing the parent will see.
    - Do not ask the parent for clarification; make the best judgment call you can and proceed.
""".trimIndent()
