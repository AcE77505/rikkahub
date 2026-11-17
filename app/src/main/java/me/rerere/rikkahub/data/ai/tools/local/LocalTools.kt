package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.SSH_TOOL_PREFIX
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolContext
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.phoneuse.PhoneUseManager
import me.rerere.rikkahub.phoneuse.isFloatingChatWindowOn
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.tts.provider.TTSManager
import me.rerere.workspace.SshRunner
import kotlin.uuid.Uuid

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val sshRunner: SshRunner,
    private val backgroundShellManager: BackgroundShellManager,
    private val phoneUseManager: PhoneUseManager,
    private val filesManager: FilesManager,
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

    val phoneUseTool by lazy {
        buildPhoneUseTool(
            ownPackageName = context.packageName,
            manager = phoneUseManager,
            isFloatingWindowOn = { isFloatingChatWindowOn(context, settingsStore.settingsFlow.value) },
            saveScreenshot = { bytes ->
                filesManager.createChatFilesByByteArrays(
                    byteArrays = listOf(bytes),
                    displayName = "screenshot.jpg",
                    mimeType = "image/jpeg",
                ).first().toString()
            },
        )
    }

    /**
     * 按全局工具开关组装本次请求的工具列表。
     *
     * 工作区工具的目标是「被声明的工作区」（[WorkspaceRepository.getDeclared]）；没有工作区被声明时
     * 即使开关开着也不会挂载，避免模型拿到一个没有目录可用的工具。
     */
    suspend fun getTools(
        options: List<LocalToolOption>,
        workspaceCwd: String? = null,
        currentAssistantId: Uuid? = null,
        conversationId: Uuid? = null,
    ): List<Tool> {
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
        // 不支持的渠道和系统版本上，之前在别处开过（比如导入的备份）也不提供
        if (options.contains(LocalToolOption.PhoneUse) && PhoneUseManager.isSupported) {
            tools.add(phoneUseTool)
        }
        if (options.contains(LocalToolOption.AssistantConfig) && currentAssistantId != null) {
            tools.addAll(buildAssistantConfigTools(settingsStore, memoryRepository, currentAssistantId))
        }
        tools.addAll(buildWorkspaceTools(options, workspaceCwd, conversationId))
        return tools
    }

    private suspend fun buildWorkspaceTools(
        options: List<LocalToolOption>,
        workspaceCwd: String?,
        conversationId: Uuid?,
    ): List<Tool> {
        val settings = settingsStore.awaitLoaded()
        // 「写入 / 编辑文件」依赖「读取文件」，即使数据脏了也不会单独挂载
        val readEnabled = LocalToolOption.WorkspaceReadFile in options
        val enabledTools = buildSet {
            if (readEnabled) {
                add("read_file")
                if (LocalToolOption.WorkspaceWriteFile in options) add("write_file")
                if (LocalToolOption.WorkspaceEditFile in options) add("edit_file")
            }
            if (LocalToolOption.WorkspaceShell in options) {
                add("shell")
                if (settings.shellBackgroundEnabled) {
                    add("shell_output")
                    add("shell_kill")
                }
            }
        }
        if (enabledTools.isEmpty()) return emptyList()

        val sandbox = workspaceRepository.sandboxDir()
        val defaultCwd = workspaceCwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

        // 本地组常驻：声明了本地工作区就用它的目录，否则用沙箱
        val local = workspaceRepository.getDeclaredLocal()
        val localWorkingDir = local
            ?.let { runCatching { workspaceRepository.workingDirectory(it.id) }.getOrNull() }
            ?: sandbox

        // 远程组只在声明了 SSH 工作区且连接参数完整时挂载；工具名带 ssh_ 前缀
        val remote = workspaceRepository.getDeclaredSsh()?.let { workspace ->
            workspace.sshTarget()?.let { workspace to it }
        }

        return buildList {
            addAll(
                createWorkspaceTools(
                    context = WorkspaceToolContext(
                        workspaceId = local?.id,
                        workspaceName = local?.name.orEmpty(),
                        workingDir = localWorkingDir,
                        defaultCwd = defaultCwd,
                        approvals = settings.toolApprovals,
                        overtimeSeconds = settings.shellOvertimeSeconds,
                        autoStopOnOvertime = settings.shellAutoStopOnOvertime,
                        enabledTools = enabledTools,
                        backgroundShellManager = backgroundShellManager.takeIf { settings.shellBackgroundEnabled },
                        conversationId = conversationId,
                    ),
                    workspaceRepository = workspaceRepository,
                )
            )
            remote?.let { (workspace, target) ->
                addAll(
                    createWorkspaceTools(
                        context = WorkspaceToolContext(
                            workspaceId = workspace.id,
                            workspaceName = workspace.name,
                            workingDir = sandbox,
                            defaultCwd = defaultCwd,
                            approvals = settings.toolApprovals,
                            overtimeSeconds = settings.shellOvertimeSeconds,
                            autoStopOnOvertime = settings.shellAutoStopOnOvertime,
                            enabledTools = enabledTools,
                            sshRunner = sshRunner,
                            sshTarget = target,
                            toolPrefix = SSH_TOOL_PREFIX,
                        ),
                        workspaceRepository = workspaceRepository,
                    )
                )
            }
        }
    }
}
