package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.DEFAULT_WORKSPACE_PROMPT
import me.rerere.rikkahub.data.ai.transformers.WorkspacePromptPlaceholders
import me.rerere.rikkahub.data.ai.transformers.buildWorkspacePromptSections
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.insertAtCursor
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 工作区提示词编辑页。
 *
 * 布局照搬「助手 → 提示词」：一个可全屏编辑的 TextArea + 下方可点击插入的占位符 chip，
 * 右上角可以把模板恢复成内置默认。这段提示词只有在一个工作区被声明给 AI 时才会发送。
 */
@Composable
fun SettingWorkspacePromptPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    val promptState = rememberTextFieldState(initialText = settings.workspacePrompt)

    val context = LocalContext.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val declared by workspaceRepository.declaredFlow().collectAsState(initial = emptyList())
    var promptPreview by remember { mutableStateOf("") }

    // 预览与真正注入共用 buildWorkspacePromptSections，避免两套逻辑走偏
    LaunchedEffect(declared, settings.workspacePrompt, settings.localTools) {
        promptPreview = buildWorkspacePromptSections(
            context = context,
            settings = settings,
            workspaceCwd = null,
            workspaceRepository = workspaceRepository,
        )
    }

    // 设置是启动时异步读盘的：拿到真实值后同步一次，避免首次打开显示占位值
    LaunchedEffect(settings.init) {
        if (!settings.init) {
            val stored = settings.workspacePrompt
            if (promptState.text.toString() != stored) {
                promptState.setTextAndPlaceCursorAtEnd(stored)
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { promptState.text }.collect { text ->
            vm.updateSettings { it.copy(workspacePrompt = text.toString()) }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_tools_workspace_prompt)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(
                            imageVector = HugeIcons.Refresh03,
                            contentDescription = stringResource(R.string.setting_workspace_prompt_reset),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup {
                formItem {
                    TextArea(
                        state = promptState,
                        label = stringResource(R.string.setting_tools_workspace_prompt),
                        minLines = 12,
                        maxLines = 24,
                    )
                    Text(
                        text = stringResource(R.string.setting_workspace_prompt_render_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.setting_workspace_prompt_not_declared_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            CardGroup(
                title = { Text(stringResource(R.string.setting_workspace_prompt_preview)) },
            ) {
                formItem {
                    val previewText = if (promptPreview.isBlank()) {
                        stringResource(R.string.setting_workspace_prompt_preview_empty)
                    } else {
                        promptPreview
                    }
                    SelectionContainer {
                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.setting_workspace_prompt_preview_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            CardGroup(
                title = { Text(stringResource(R.string.setting_workspace_prompt_available_variables)) },
            ) {
                formItem {
                    Text(
                        text = stringResource(R.string.setting_workspace_prompt_vars_common),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WorkspacePromptPlaceholders.ordered
                            .filterNot { it in LOCAL_ONLY_PLACEHOLDERS }
                            .forEach { key ->
                                Tag(onClick = { promptState.insertAtCursor("{{$key}}") }) {
                                    Text(placeholderLabel(key))
                                }
                            }
                    }
                    Text(
                        text = stringResource(R.string.setting_workspace_prompt_vars_local_only),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = stringResource(R.string.setting_workspace_prompt_vars_local_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WorkspacePromptPlaceholders.ordered
                            .filter { it in LOCAL_ONLY_PLACEHOLDERS }
                            .forEach { key ->
                                Tag(onClick = { promptState.insertAtCursor("{{$key}}") }) {
                                    Text(placeholderLabel(key))
                                }
                            }
                    }
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = showResetDialog,
        title = stringResource(R.string.setting_workspace_prompt_reset),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            promptState.setTextAndPlaceCursorAtEnd(DEFAULT_WORKSPACE_PROMPT)
            showResetDialog = false
        },
        onDismiss = { showResetDialog = false },
    ) {
        Text(stringResource(R.string.setting_workspace_prompt_reset_confirm))
    }
}

/** 这些目录在手机上，SSH 段渲染时会被整行删除 */
private val LOCAL_ONLY_PLACEHOLDERS = setOf(
    WorkspacePromptPlaceholders.SKILLS_DIR,
    WorkspacePromptPlaceholders.BUILTIN_SKILLS_DIR,
    WorkspacePromptPlaceholders.UPLOAD_DIR,
)

@Composable
private fun placeholderLabel(key: String): String = stringResource(
    when (key) {
        WorkspacePromptPlaceholders.WORKSPACE_NAME -> R.string.workspace_prompt_var_workspace_name
        WorkspacePromptPlaceholders.WORKSPACE_DIR -> R.string.workspace_prompt_var_workspace_dir
        WorkspacePromptPlaceholders.SHELL_ENV -> R.string.workspace_prompt_var_shell_env
        WorkspacePromptPlaceholders.TOOL_LIST -> R.string.workspace_prompt_var_tool_list
        WorkspacePromptPlaceholders.SKILLS_DIR -> R.string.workspace_prompt_var_skills_dir
        WorkspacePromptPlaceholders.BUILTIN_SKILLS_DIR -> R.string.workspace_prompt_var_builtin_skills_dir
        WorkspacePromptPlaceholders.UPLOAD_DIR -> R.string.workspace_prompt_var_upload_dir
        else -> R.string.workspace_prompt_var_cwd_line
    }
)
