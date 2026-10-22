package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolContext
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val chartDisplayTool by lazy { buildChartDisplayTool() }

    /**
     * 按全局工具开关组装本次请求的工具列表。
     *
     * 工作区工具的目标是「被声明的工作区」（[WorkspaceRepository.getDeclared]）；没有工作区被声明时
     * 即使开关开着也不会挂载，避免模型拿到一个没有目录可用的工具。
     */
    suspend fun getTools(options: List<LocalToolOption>, workspaceCwd: String? = null): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.ChartDisplay)) {
            tools.add(chartDisplayTool)
        }
        tools.addAll(buildWorkspaceTools(options, workspaceCwd))
        return tools
    }

    private suspend fun buildWorkspaceTools(
        options: List<LocalToolOption>,
        workspaceCwd: String?,
    ): List<Tool> {
        // 「写入 / 编辑文件」依赖「读取文件」，即使数据脏了也不会单独挂载
        val readEnabled = LocalToolOption.WorkspaceReadFile in options
        val enabledTools = buildSet {
            if (readEnabled) {
                add("workspace_read_file")
                if (LocalToolOption.WorkspaceWriteFile in options) add("workspace_write_file")
                if (LocalToolOption.WorkspaceEditFile in options) add("workspace_edit_file")
            }
            if (LocalToolOption.WorkspaceShell in options) add("workspace_shell")
        }
        if (enabledTools.isEmpty()) return emptyList()

        // 没有声明工作区时不再整体跳过：文件工具退回沙箱目录继续可用，
        // shell 由 createWorkspaceTools 判断 workspaceId 为空后自动不挂载
        val workspace = workspaceRepository.getDeclared()
        val workingDir = workspace
            ?.let { runCatching { workspaceRepository.workingDirectory(it.id) }.getOrNull() }
            ?: workspaceRepository.sandboxDir()
        val settings = settingsStore.awaitLoaded()
        return createWorkspaceTools(
            context = WorkspaceToolContext(
                workspaceId = workspace?.id,
                workspaceName = workspace?.name.orEmpty(),
                workingDir = workingDir,
                defaultCwd = workspaceCwd?.removePrefix("/workspace/")?.removePrefix("/workspace"),
                approvals = settings.toolApprovals,
                overtimeSeconds = settings.shellOvertimeSeconds,
                autoStopOnOvertime = settings.shellAutoStopOnOvertime,
                enabledTools = enabledTools,
            ),
            workspaceRepository = workspaceRepository,
        )
    }
}
