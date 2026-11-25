package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.FolderCode
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.MessageQuestion
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolDefaultApprovals
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.PathOpenPreference
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ToolNameDialog
import me.rerere.rikkahub.ui.components.ui.ToolNameDisplay
import me.rerere.rikkahub.ui.components.ui.ToolNameLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.installedMtManagers
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** 命令超时提示阈值的可选值（秒）；null 表示无限制 */
private val OVERTIME_OPTIONS = listOf(30, 60, 120, 300, null)

/** 本页搜索覆盖的目标页：工具页自己能跳到的所有页面 */
private val TOOL_PAGE_TARGETS = setOf(
    Screen.SettingTools,
    Screen.SettingLocalTools,
    Screen.SettingSubAgents,
    Screen.Workspaces,
    Screen.SettingTerminalDirectory(),
)

/**
 * 「设置 → 工具」：所有工具开关集中在这里，所有助手共用一套。
 *
 * 工作区工具（读/写/编辑文件与执行命令）不再要求助手绑定工作区，
 * 目标是工作区列表里被「声明」的那一个。
 */
@Composable
fun SettingToolsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val workspaceRepository: WorkspaceRepository = koinInject()
    val declaredWorkspaces by workspaceRepository.declaredFlow()
        .collectAsStateWithLifecycle(emptyList())
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var approvalDialogVisible by rememberSaveable { mutableStateOf(false) }
    var overtimeDialogVisible by rememberSaveable { mutableStateOf(false) }
    var backgroundLimitDialogVisible by rememberSaveable { mutableStateOf(false) }
    var alwaysLoadedDialogVisible by rememberSaveable { mutableStateOf(false) }

    fun toggleTool(option: LocalToolOption, enabled: Boolean) {
        val tools = if (enabled) settings.localTools + option else settings.localTools - option
        // 关闭「读取文件」时，依赖它的写/编辑由 Settings.normalized() 一起清掉
        vm.updateSettings { it.copy(localTools = tools.distinct()) }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    val allSearchEntries = rememberSettingSearchEntries(settings.assistantId)
    val toolSearchEntries = remember(allSearchEntries) {
        allSearchEntries.filter { entry ->
            TOOL_PAGE_TARGETS.any { it == entry.target }
        }
    }
    val readEnabled = LocalToolOption.WorkspaceReadFile in settings.localTools
    val shellEnabled = LocalToolOption.WorkspaceShell in settings.localTools

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tools)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 页内搜索：工具项分散在多张卡里，只靠滚动找太慢
            item("toolSearch") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.setting_tools_search_placeholder)) },
                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(HugeIcons.Cancel01, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }

            if (searchQuery.isNotBlank()) {
                item("toolSearchResults") {
                    val results = toolSearchEntries.filterBySettingQuery(searchQuery)
                    if (results.isEmpty()) {
                        Text(
                            text = stringResource(R.string.setting_tools_search_empty),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        CardGroup {
                            results.forEach { entry ->
                                item(
                                    onClick = {
                                        // 本页自己的条目没有可跳转的位置，清空搜索即可回到页内
                                        if (entry.target != Screen.SettingTools) {
                                            navController.navigate(entry.target)
                                        }
                                        searchQuery = ""
                                    },
                                    headlineContent = { Text(entry.title) },
                                    supportingContent = { Text(entry.path) },
                                )
                            }
                        }
                    }
                }
            } else {

            // ① 动态工具：只影响发出去的提示词体积，放在最上面
            item("dynamic") {
                CardGroup(title = { Text(stringResource(R.string.setting_tools_dynamic_title)) }) {
                    switchItem(
                        checked = settings.dynamicToolsEnabled,
                        onCheckedChange = { enabled ->
                            vm.updateSettings { it.copy(dynamicToolsEnabled = enabled) }
                        },
                        supportingContent = { Text(stringResource(R.string.setting_tools_dynamic_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_dynamic_title)) },
                    )
                    if (settings.dynamicToolsEnabled) {
                        item(
                            onClick = { alwaysLoadedDialogVisible = true },
                            leadingContent = { CardGroupIcon(HugeIcons.Pin) },
                            supportingContent = {
                                val count = settings.dynamicToolsAlwaysLoaded.size
                                Text(
                                    if (count > 0) {
                                        stringResource(R.string.setting_tools_always_loaded_count, count)
                                    } else {
                                        stringResource(R.string.setting_tools_dynamic_always_loaded_desc)
                                    }
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.setting_tools_dynamic_always_loaded)) },
                        )
                    }
                }
            }

            // ② 常用工具：进这页最常见的目的是打开一个能力，先给最常用的，其余进二级页
            item("commonTools") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_tools_group_common)) },
                    toolNameDisplay = settings.toolNameDisplay,
                ) {
                    toolSwitch(
                        checked = LocalToolOption.TimeInfo in settings.localTools,
                        onCheckedChange = { toggleTool(LocalToolOption.TimeInfo, it) },
                        icon = HugeIcons.Clock01,
                        title = R.string.assistant_page_local_tools_time_info_title,
                        description = R.string.assistant_page_local_tools_time_info_desc,
                        toolNames = listOf("get_time_info"),
                    )
                    toolSwitch(
                        checked = LocalToolOption.AskUser in settings.localTools,
                        onCheckedChange = { toggleTool(LocalToolOption.AskUser, it) },
                        icon = HugeIcons.MessageQuestion,
                        title = R.string.assistant_page_local_tools_ask_user_title,
                        description = R.string.assistant_page_local_tools_ask_user_desc,
                        toolNames = listOf("ask_user"),
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingLocalTools) },
                        leadingContent = { CardGroupIcon(HugeIcons.Tools) },
                        supportingContent = { Text(stringResource(R.string.setting_tools_local_tools_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_all_local_tools)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                }
            }

            // ③ 代码与文件：工作区工具；从属配置跟随父开关置灰，不再整块消失
            item("workspaceTools") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_tools_group_files)) },
                    toolNameDisplay = settings.toolNameDisplay,
                ) {
                    toolSwitch(
                        checked = readEnabled,
                        onCheckedChange = { toggleTool(LocalToolOption.WorkspaceReadFile, it) },
                        icon = HugeIcons.FileView,
                        title = R.string.workspace_tool_read_file,
                        description = R.string.workspace_tool_read_file_desc,
                        toolNames = listOf("read_file"),
                    )
                    toolSwitch(
                        checked = readEnabled && LocalToolOption.WorkspaceWriteFile in settings.localTools,
                        enabled = readEnabled,
                        onCheckedChange = { toggleTool(LocalToolOption.WorkspaceWriteFile, it) },
                        icon = HugeIcons.FileEdit,
                        title = R.string.workspace_tool_write_file,
                        description = R.string.workspace_tool_write_file_desc,
                        toolNames = listOf("write_file"),
                        disabledHint = R.string.setting_tools_depends_on_read_file,
                    )
                    toolSwitch(
                        checked = readEnabled && LocalToolOption.WorkspaceEditFile in settings.localTools,
                        enabled = readEnabled,
                        onCheckedChange = { toggleTool(LocalToolOption.WorkspaceEditFile, it) },
                        icon = HugeIcons.PencilEdit01,
                        title = R.string.workspace_tool_edit_file,
                        description = R.string.workspace_tool_edit_file_desc,
                        toolNames = listOf("edit_file"),
                        disabledHint = R.string.setting_tools_depends_on_read_file,
                    )
                    toolSwitch(
                        checked = shellEnabled,
                        onCheckedChange = { toggleTool(LocalToolOption.WorkspaceShell, it) },
                        icon = HugeIcons.ComputerTerminal01,
                        title = R.string.workspace_tool_shell,
                        description = R.string.workspace_tool_shell_desc,
                        toolNames = listOf("shell", "shell_output", "shell_kill"),
                    )
                    // 以下都是命令执行的从属配置：父开关关掉时置灰保留，避免卡片突然消失
                    val dimmed = Modifier.alpha(0.38f)
                    item(
                        onClick = if (shellEnabled) {
                            { overtimeDialogVisible = true }
                        } else null,
                        modifier = if (shellEnabled) Modifier else dimmed,
                        supportingContent = { Text(stringResource(R.string.setting_tools_shell_overtime_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_shell_overtime_threshold)) },
                        trailingContent = {
                            if (shellEnabled) {
                                OvertimeValue(settings.shellOvertimeSeconds)
                            }
                        },
                    )
                    switchItem(
                        checked = settings.shellAutoStopOnOvertime,
                        onCheckedChange = { checked ->
                            vm.updateSettings { it.copy(shellAutoStopOnOvertime = checked) }
                        },
                        enabled = shellEnabled,
                        supportingContent = { Text(stringResource(R.string.setting_tools_shell_auto_stop_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_shell_auto_stop)) },
                    )
                    switchItem(
                        checked = settings.shellBackgroundEnabled,
                        onCheckedChange = { checked ->
                            vm.updateSettings { it.copy(shellBackgroundEnabled = checked) }
                        },
                        enabled = shellEnabled,
                        supportingContent = { Text(stringResource(R.string.setting_tools_shell_background_enabled_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_shell_background_enabled)) },
                    )
                    val backgroundEnabled = shellEnabled && settings.shellBackgroundEnabled
                    item(
                        onClick = if (backgroundEnabled) {
                            { backgroundLimitDialogVisible = true }
                        } else null,
                        modifier = if (backgroundEnabled) Modifier else dimmed,
                        supportingContent = { Text(stringResource(R.string.setting_tools_shell_background_limit_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_shell_background_limit)) },
                        trailingContent = {
                            if (backgroundEnabled) {
                                Text(settings.shellBackgroundMaxRunning.toString())
                            }
                        },
                    )
                    switchItem(
                        checked = settings.shellBackgroundNotify,
                        onCheckedChange = { checked ->
                            vm.updateSettings { it.copy(shellBackgroundNotify = checked) }
                        },
                        enabled = backgroundEnabled,
                        supportingContent = { Text(stringResource(R.string.setting_tools_shell_background_notify_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_shell_background_notify)) },
                    )
                    // 审批紧跟在文件/命令工具之后：它就是这些工具的安全兜底
                    item(
                        onClick = { approvalDialogVisible = true },
                        leadingContent = { CardGroupIcon(HugeIcons.Tick01) },
                        supportingContent = { Text(stringResource(R.string.setting_tools_tool_approval_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_tool_approval)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                }
            }

            // ④ 工作区与路径：低频的一次性配置
            item("workspace") {
                CardGroup(title = { Text(stringResource(R.string.setting_tools_workspace)) }) {
                    item(
                        onClick = { navController.navigate(Screen.Workspaces) },
                        leadingContent = { CardGroupIcon(HugeIcons.FolderCode) },
                        supportingContent = { EnabledSummary(declaredWorkspaces.size) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_workspace)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTerminalDirectory()) },
                        leadingContent = { CardGroupIcon(HugeIcons.FolderOpen) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_terminal_dir)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                }
            }

            // 路径打开方式：单选组，必须有独立标题，否则选项会看起来挂在上一张卡上
            item("pathOpen") {
                val context = LocalContext.current
                val installedPackages = remember { installedMtManagers(context) }
                val systemLabel = stringResource(R.string.setting_path_open_system)
                val askLabel = stringResource(R.string.setting_path_open_ask)
                val selectedPackage = settings.pathOpenMtPackage?.takeIf { it in installedPackages }
                    ?: installedPackages.firstOrNull()
                CardGroup(
                    title = { Text(stringResource(R.string.setting_path_open_title)) },
                    modifier = Modifier.selectableGroup(),
                ) {
                    if (installedPackages.isEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_path_open_not_installed)) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_path_open_not_installed_desc))
                            },
                        )
                    }
                    // 每个已安装的 MT 单列一项，正式版和 Canary 可以分开选
                    installedPackages.forEach { packageName ->
                        val isSelected = settings.pathOpenPreference == PathOpenPreference.MT_MANAGER &&
                            selectedPackage == packageName
                        item(
                            onClick = {
                                vm.updateSettings {
                                    it.copy(
                                        pathOpenPreference = PathOpenPreference.MT_MANAGER,
                                        pathOpenMtPackage = packageName,
                                    )
                                }
                            },
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                this.selected = isSelected
                            },
                            headlineContent = {
                                Text(stringResource(R.string.setting_path_open_mt_with_package, packageName))
                            },
                            leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                        )
                    }
                    val systemSelected = settings.pathOpenPreference == PathOpenPreference.SYSTEM
                    item(
                        onClick = {
                            vm.updateSettings { it.copy(pathOpenPreference = PathOpenPreference.SYSTEM) }
                        },
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            this.selected = systemSelected
                        },
                        headlineContent = { Text(systemLabel) },
                        leadingContent = { RadioButton(selected = systemSelected, onClick = null) },
                    )
                    val askSelected = settings.pathOpenPreference == PathOpenPreference.ASK
                    item(
                        onClick = {
                            vm.updateSettings { it.copy(pathOpenPreference = PathOpenPreference.ASK) }
                        },
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            this.selected = askSelected
                        },
                        headlineContent = { Text(askLabel) },
                        leadingContent = { RadioButton(selected = askSelected, onClick = null) },
                    )
                }
            }

            // ⑤ 高级：安全兜底与其他工具形态
            item("advanced") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_tools_group_advanced)) },
                    toolNameDisplay = settings.toolNameDisplay,
                ) {
                    toolSwitch(
                        checked = LocalToolOption.AssistantConfig in settings.localTools,
                        onCheckedChange = { toggleTool(LocalToolOption.AssistantConfig, it) },
                        icon = HugeIcons.Tools,
                        title = R.string.tool_assistant_config,
                        description = R.string.tool_assistant_config_desc,
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
                    item(
                        onClick = { navController.navigate(Screen.SettingSubAgents) },
                        leadingContent = { CardGroupIcon(HugeIcons.Tools) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_tools_subagents_desc, settings.subAgents.size))
                        },
                        headlineContent = { Text(stringResource(R.string.setting_tools_subagents)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                }
            }

            // ⑥ 显示：不影响能力，只影响设置页本身怎么展示
            item("display") {
                CardGroup(title = { Text(stringResource(R.string.setting_tools_group_display)) }) {
                    formItem(
                        headlineContent = {
                            Text(stringResource(R.string.setting_tools_tool_identifier_display_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_tools_tool_identifier_display_desc))
                        },
                    ) {
                        ConnectedChoiceButtons(
                            options = ToolNameDisplay.entries,
                            selected = settings.toolNameDisplay,
                            onSelect = { mode ->
                                vm.updateSettings { it.copy(toolNameDisplay = mode) }
                            },
                        ) { mode, selected ->
                            ConnectedChoiceLabel(
                                text = when (mode) {
                                    ToolNameDisplay.OFF -> stringResource(R.string.setting_tools_tool_identifier_display_off)
                                    ToolNameDisplay.LONG_PRESS -> stringResource(R.string.setting_tools_tool_identifier_display_long_press)
                                    ToolNameDisplay.ALWAYS -> stringResource(R.string.setting_tools_tool_identifier_display_always)
                                },
                                selected = selected,
                            )
                        }
                    }
                }
            }
            }
        }
    }

    if (overtimeDialogVisible) {
        OvertimeThresholdDialog(
            selected = settings.shellOvertimeSeconds,
            onSelect = { seconds ->
                vm.updateSettings { it.copy(shellOvertimeSeconds = seconds) }
            },
            onDismiss = { overtimeDialogVisible = false },
        )
    }

    if (backgroundLimitDialogVisible) {
        BackgroundLimitDialog(
            selected = settings.shellBackgroundMaxRunning,
            onSelect = { limit ->
                vm.updateSettings { it.copy(shellBackgroundMaxRunning = limit) }
            },
            onDismiss = { backgroundLimitDialogVisible = false },
        )
    }

    if (approvalDialogVisible) {
        ToolApprovalDialog(
            approvals = settings.toolApprovals,
            enabledLocalTools = settings.localTools,
            toolNameDisplay = settings.toolNameDisplay,
            onApprovalChange = { toolName, needsApproval ->
                vm.updateSettings {
                    it.copy(toolApprovals = it.toolApprovals + (toolName to needsApproval))
                }
            },
            onDismiss = { approvalDialogVisible = false },
        )
    }

    if (alwaysLoadedDialogVisible) {
        AlwaysLoadedDialog(
            value = settings.dynamicToolsAlwaysLoaded,
            onConfirm = { patterns ->
                vm.updateSettings { it.copy(dynamicToolsAlwaysLoaded = patterns) }
                alwaysLoadedDialogVisible = false
            },
            onDismiss = { alwaysLoadedDialogVisible = false },
        )
    }
}

/** 「已开启 n 个」/「未开启」：入口行的数量摘要 */
@Composable
private fun EnabledSummary(count: Int) {
    Text(
        if (count > 0) {
            stringResource(R.string.setting_tools_workspace_desc, count)
        } else {
            stringResource(R.string.setting_tools_workspace_desc_none)
        }
    )
}

private fun CardGroupScope.toolSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    title: Int,
    description: Int,
    toolNames: List<String>,
    enabled: Boolean = true,
    // 不可用的原因：置灰时替代默认描述，让用户知道为什么点不了
    disabledHint: Int? = null,
) {
    switchItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        toolNames = toolNames,
        // 图标底色跟着开关状态变化
        leadingContent = {
            CardGroupIcon(
                icon = icon,
                containerColor = if (checked) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (checked) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        supportingContent = {
            Text(stringResource(if (!enabled && disabledHint != null) disabledHint else description))
        },
        headlineContent = { Text(stringResource(title)) },
    )
}

/** 工具审批：全局一份，覆盖项写在 `Settings.toolApprovals` */
@Composable
private fun ToolApprovalDialog(
    approvals: Map<String, Boolean>,
    enabledLocalTools: List<LocalToolOption>,
    toolNameDisplay: ToolNameDisplay,
    onApprovalChange: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var previewToolNames by remember { mutableStateOf<List<String>?>(null) }
    if (previewToolNames != null) {
        ToolNameDialog(toolNames = previewToolNames.orEmpty()) { previewToolNames = null }
    }
    // 只列出真正启用的工具：给关掉的工具配审批没有意义
    val visibleTools = APPROVAL_TOOLS.filter { (_, _, option) ->
        option in enabledLocalTools
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_tools_tool_approval)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_tools_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (visibleTools.isEmpty()) {
                    Text(
                        text = stringResource(R.string.setting_tools_tool_approval_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                visibleTools.forEach { (toolName, labelRes, _) ->
                    // 走兼容函数：升级前存在旧工具名下的审批设置也要能读出来
                    val needsApproval = resolveWorkspaceToolApproval(toolName, approvals)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .then(
                                if (toolNameDisplay == ToolNameDisplay.LONG_PRESS) {
                                    Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = { previewToolNames = listOf(toolName) },
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (toolNameDisplay == ToolNameDisplay.ALWAYS) {
                                ToolNameLabel(listOf(toolName))
                            }
                        }
                        Switch(
                            checked = needsApproval,
                            onCheckedChange = { onApprovalChange(toolName, it) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_confirm))
            }
        },
    )
}

private val APPROVAL_TOOLS = listOf(
    Triple("read_file", R.string.workspace_tool_read_file, LocalToolOption.WorkspaceReadFile),
    Triple("write_file", R.string.workspace_tool_write_file, LocalToolOption.WorkspaceWriteFile),
    Triple("edit_file", R.string.workspace_tool_edit_file, LocalToolOption.WorkspaceEditFile),
    Triple("shell", R.string.workspace_tool_shell, LocalToolOption.WorkspaceShell),
)

/** 行尾展示当前阈值 */
@Composable
private fun OvertimeValue(seconds: Int?) {
    Text(
        text = seconds
            ?.let { stringResource(R.string.setting_tools_overtime_seconds, it) }
            ?: stringResource(R.string.setting_tools_overtime_unlimited),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 阈值选择弹窗：单选，复用 CardGroup + RadioButton 的既有写法 */
@Composable
private fun OvertimeThresholdDialog(
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_tools_shell_overtime_threshold)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_tools_shell_overtime_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardGroup(modifier = Modifier.selectableGroup()) {
                    OVERTIME_OPTIONS.forEach { seconds ->
                        val isSelected = selected == seconds
                        item(
                            onClick = {
                                onSelect(seconds)
                                onDismiss()
                            },
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                this.selected = isSelected
                            },
                            headlineContent = {
                                Text(
                                    seconds
                                        ?.let { stringResource(R.string.setting_tools_overtime_seconds, it) }
                                        ?: stringResource(R.string.setting_tools_overtime_unlimited)
                                )
                            },
                            // 点整行选中，单选圈自己不接收点击
                            leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** 每个对话同时运行的后台命令数上限；与设置页的行尾值保持一致 */
private val BACKGROUND_LIMIT_OPTIONS = listOf(1, 2, 3, 5, 10)

@Composable
private fun BackgroundLimitDialog(
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_tools_shell_background_limit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_tools_shell_background_limit_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardGroup(modifier = Modifier.selectableGroup()) {
                    BACKGROUND_LIMIT_OPTIONS.forEach { limit ->
                        val isSelected = selected == limit
                        item(
                            onClick = {
                                onSelect(limit)
                                onDismiss()
                            },
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                this.selected = isSelected
                            },
                            headlineContent = { Text(limit.toString()) },
                            leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** 「始终加载」白名单：每行一条通配符，空行忽略 */
@Composable
private fun AlwaysLoadedDialog(
    value: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(value) { mutableStateOf(value.joinToString("\n")) }
    val patterns = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_tools_dynamic_always_loaded)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.setting_tools_dynamic_always_loaded_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 4,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(patterns) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
