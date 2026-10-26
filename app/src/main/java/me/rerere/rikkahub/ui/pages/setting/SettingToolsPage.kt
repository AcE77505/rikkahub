package me.rerere.rikkahub.ui.pages.setting

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
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.FolderCode
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolDefaultApprovals
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/** 命令超时提示阈值的可选值（秒）；null 表示无限制 */
private val OVERTIME_OPTIONS = listOf(30, 60, 120, 300, null)

/**
 * 「设置 → 工具」：所有工具开关集中在这里，所有助手共用一套。
 *
 * 工作区工具（读/写/编辑文件与执行命令）不再要求助手绑定工作区，
 * 目标是工作区列表里被「声明」的那一个。
 */
@Composable
fun SettingToolsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var approvalDialogVisible by rememberSaveable { mutableStateOf(false) }
    var overtimeDialogVisible by rememberSaveable { mutableStateOf(false) }

    fun toggleTool(option: LocalToolOption, enabled: Boolean) {
        val tools = if (enabled) settings.localTools + option else settings.localTools - option
        // 关闭「读取文件」时，依赖它的写/编辑由 Settings.normalized() 一起清掉
        vm.updateSettings { it.copy(localTools = tools.distinct()) }
    }

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
            item("workspace") {
                CardGroup(title = { Text(stringResource(R.string.setting_tools_workspace)) }) {
                    item(
                        onClick = { navController.navigate(Screen.Workspaces) },
                        leadingContent = { CardGroupIcon(HugeIcons.FolderCode) },
                        supportingContent = { Text(stringResource(R.string.setting_tools_workspace_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_workspace)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWorkspacePrompt) },
                        leadingContent = { CardGroupIcon(HugeIcons.QuillWrite01) },
                        supportingContent = { Text(stringResource(R.string.setting_tools_workspace_prompt_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_workspace_prompt)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                }
            }

            item("switches") {
                CardGroup(title = { Text(stringResource(R.string.setting_tools_switches)) }) {
                    val readEnabled = LocalToolOption.WorkspaceReadFile in settings.localTools
                    toolSwitch(
                        checked = readEnabled,
                        onCheckedChange = { toggleTool(LocalToolOption.WorkspaceReadFile, it) },
                        icon = HugeIcons.FileView,
                        title = R.string.workspace_tool_read_file,
                        description = R.string.workspace_tool_read_file_desc,
                    )
                    toolSwitch(
                        checked = readEnabled && LocalToolOption.WorkspaceWriteFile in settings.localTools,
                        enabled = readEnabled,
                        onCheckedChange = { toggleTool(LocalToolOption.WorkspaceWriteFile, it) },
                        icon = HugeIcons.FileEdit,
                        title = R.string.workspace_tool_write_file,
                        description = R.string.workspace_tool_write_file_desc,
                    )
                    toolSwitch(
                        checked = readEnabled && LocalToolOption.WorkspaceEditFile in settings.localTools,
                        enabled = readEnabled,
                        onCheckedChange = { toggleTool(LocalToolOption.WorkspaceEditFile, it) },
                        icon = HugeIcons.PencilEdit01,
                        title = R.string.workspace_tool_edit_file,
                        description = R.string.workspace_tool_edit_file_desc,
                    )
                    toolSwitch(
                        checked = LocalToolOption.WorkspaceShell in settings.localTools,
                        onCheckedChange = { toggleTool(LocalToolOption.WorkspaceShell, it) },
                        icon = HugeIcons.ComputerTerminal01,
                        title = R.string.workspace_tool_shell,
                        description = R.string.workspace_tool_shell_desc,
                    )
                }
            }

            if (LocalToolOption.WorkspaceShell in settings.localTools) {
                item("timeout") {
                    CardGroup(title = { Text(stringResource(R.string.setting_tools_shell_timeout)) }) {
                        // 阈值只在行尾显示当前值，点开弹窗再选，避免把每个选项都铺在页面上
                        item(
                            onClick = { overtimeDialogVisible = true },
                            leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
                            supportingContent = { Text(stringResource(R.string.setting_tools_shell_overtime_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_tools_shell_overtime_threshold)) },
                            trailingContent = { OvertimeValue(settings.shellOvertimeSeconds) },
                        )
                        switchItem(
                            checked = settings.shellAutoStopOnOvertime,
                            onCheckedChange = { checked ->
                                vm.updateSettings { it.copy(shellAutoStopOnOvertime = checked) }
                            },
                            supportingContent = { Text(stringResource(R.string.setting_tools_shell_auto_stop_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_tools_shell_auto_stop)) },
                        )
                    }
                }
            }

            item("approval") {
                CardGroup {
                    item(
                        onClick = { approvalDialogVisible = true },
                        leadingContent = { CardGroupIcon(HugeIcons.Tick01) },
                        supportingContent = { Text(stringResource(R.string.setting_tools_tool_approval_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_tool_approval)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                }
            }

            item("localTools") {
                CardGroup {
                    item(
                        onClick = { navController.navigate(Screen.SettingLocalTools) },
                        leadingContent = { CardGroupIcon(HugeIcons.Tools) },
                        supportingContent = { Text(stringResource(R.string.setting_tools_local_tools_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_local_tools)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTerminalDirectory()) },
                        leadingContent = { CardGroupIcon(HugeIcons.FolderOpen) },
                        supportingContent = { Text(stringResource(R.string.setting_tools_terminal_dir_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_terminal_dir)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
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

    if (approvalDialogVisible) {
        ToolApprovalDialog(
            approvals = settings.toolApprovals,
            onApprovalChange = { toolName, needsApproval ->
                vm.updateSettings {
                    it.copy(toolApprovals = it.toolApprovals + (toolName to needsApproval))
                }
            },
            onDismiss = { approvalDialogVisible = false },
        )
    }
}

private fun CardGroupScope.toolSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    title: Int,
    description: Int,
    enabled: Boolean = true,
) {
    switchItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
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
        supportingContent = { Text(stringResource(description)) },
        headlineContent = { Text(stringResource(title)) },
    )
}

/** 工具审批：全局一份，覆盖项写在 `Settings.toolApprovals` */
@Composable
private fun ToolApprovalDialog(
    approvals: Map<String, Boolean>,
    onApprovalChange: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
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
                APPROVAL_TOOLS.forEach { (toolName, labelRes) ->
                    // 走兼容函数：升级前存在旧工具名下的审批设置也要能读出来
                    val needsApproval = resolveWorkspaceToolApproval(toolName, approvals)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            modifier = Modifier.padding(end = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
    "read_file" to R.string.workspace_tool_read_file,
    "write_file" to R.string.workspace_tool_write_file,
    "edit_file" to R.string.workspace_tool_edit_file,
    "shell" to R.string.workspace_tool_shell,
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
