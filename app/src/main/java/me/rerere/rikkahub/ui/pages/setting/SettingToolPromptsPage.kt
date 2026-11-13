package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.TOOL_PROMPT_ENTRIES
import me.rerere.rikkahub.data.ai.tools.ToolPromptCategory
import me.rerere.rikkahub.data.ai.tools.ToolPromptEntry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel

/** 「设置 → 工具提示词」：按分类进入，具体工具在分类页里再展开。 */
@Composable
fun SettingToolPromptsPage() {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tool_prompts)) },
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
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup {
                    // 工作区提示词是模板，不是单个工具的 description，复用已有的编辑页
                    item(
                        onClick = { navController.navigate(Screen.SettingWorkspacePrompt) },
                        headlineContent = { Text(stringResource(R.string.setting_tools_workspace_prompt)) },
                        supportingContent = { Text(stringResource(R.string.tool_prompts_workspace_desc)) },
                    )
                    ToolPromptCategory.entries.forEach { category ->
                        item(
                            onClick = { navController.navigate(Screen.SettingToolPromptCategory(category.name)) },
                            headlineContent = { Text(categoryTitle(category)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.tool_prompts_tools_count,
                                        TOOL_PROMPT_ENTRIES.count { it.category == category },
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 某个分类下的工具列表。 */
@Composable
fun SettingToolPromptCategoryPage(
    categoryName: String,
) {
    val category = ToolPromptCategory.entries.firstOrNull { it.name == categoryName } ?: return
    val entries = TOOL_PROMPT_ENTRIES.filter { it.category == category }
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(categoryTitle(category)) },
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
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup {
                    entries.forEach { entry ->
                        item(
                            onClick = { navController.navigate(Screen.SettingToolPromptEdit(entry.name)) },
                            headlineContent = { Text(entry.label) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 编辑单个工具的描述和系统提示。
 *
 * 初始文本就是当前生效值（覆盖或内置默认），所以默认值本身可以直接编辑。
 * 修改后需要点保存才写入设置；未保存退出会先询问保存还是丢弃。
 */
@Composable
fun SettingToolPromptEditPage(
    toolName: String,
    vm: SettingVM = koinViewModel(),
) {
    val entry: ToolPromptEntry = TOOL_PROMPT_ENTRIES.firstOrNull { it.name == toolName } ?: return
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val defaultDescription = entry.defaultDescription
    val defaultSystemPrompt = entry.defaultSystemPrompt

    var description by rememberSaveable(toolName) {
        mutableStateOf(settings.toolDescriptionOverrides[toolName] ?: defaultDescription)
    }
    var systemPrompt by rememberSaveable(toolName) {
        mutableStateOf(settings.toolSystemPromptOverrides[toolName] ?: defaultSystemPrompt)
    }
    var dirty by rememberSaveable(toolName) { mutableStateOf(false) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }

    // 设置是异步读盘的，拿到真实值后同步一次；用户已经改过就不覆盖
    LaunchedEffect(settings.init, toolName) {
        if (settings.init && !dirty) {
            description = settings.toolDescriptionOverrides[toolName] ?: defaultDescription
            systemPrompt = settings.toolSystemPromptOverrides[toolName] ?: defaultSystemPrompt
        }
    }

    fun save() {
        vm.updateSettings { current ->
            current.copy(
                toolDescriptionOverrides = current.toolDescriptionOverrides
                    .withOverride(toolName, description, defaultDescription),
                toolSystemPromptOverrides = current.toolSystemPromptOverrides
                    .withOverride(toolName, systemPrompt, defaultSystemPrompt),
            )
        }
        dirty = false
    }

    fun reset() {
        description = defaultDescription
        systemPrompt = defaultSystemPrompt
        vm.updateSettings { current ->
            current.copy(
                toolDescriptionOverrides = current.toolDescriptionOverrides - toolName,
                toolSystemPromptOverrides = current.toolSystemPromptOverrides - toolName,
            )
        }
        dirty = false
    }

    // 系统返回键也要拦住未保存的修改
    BackHandler(enabled = dirty) { showExitDialog = true }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(entry.label) },
                navigationIcon = {
                    BackButton(
                        onClick = {
                            if (dirty) showExitDialog = true else navController.popBackStack()
                        }
                    )
                },
                actions = {
                    IconButton(onClick = { save() }) {
                        Icon(
                            imageVector = HugeIcons.FloppyDisk,
                            contentDescription = stringResource(R.string.common_save),
                        )
                    }
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(
                            imageVector = HugeIcons.Refresh03,
                            contentDescription = stringResource(R.string.tool_prompts_reset),
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
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup {
                formItem {
                    OutlinedTextField(
                        value = description,
                        onValueChange = {
                            description = it
                            dirty = true
                        },
                        label = { Text(stringResource(R.string.tool_prompts_description_label)) },
                        minLines = 5,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.tool_prompts_default_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            CardGroup {
                formItem {
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = {
                            systemPrompt = it
                            dirty = true
                        },
                        label = { Text(stringResource(R.string.tool_prompts_system_prompt_label)) },
                        minLines = 5,
                        maxLines = 16,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.tool_prompts_default_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = showResetDialog,
        title = stringResource(R.string.tool_prompts_reset),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showResetDialog = false
            reset()
        },
        onDismiss = { showResetDialog = false },
    ) {
        Text(stringResource(R.string.tool_prompts_reset_confirm))
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.tool_prompts_unsaved_title)) },
            text = { Text(stringResource(R.string.tool_prompts_unsaved_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        save()
                        showExitDialog = false
                        navController.popBackStack()
                    }
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            navController.popBackStack()
                        }
                    ) {
                        Text(stringResource(R.string.tool_prompts_discard))
                    }
                }
            },
        )
    }
}

@Composable
private fun categoryTitle(category: ToolPromptCategory): String = stringResource(
    when (category) {
        ToolPromptCategory.SKILLS -> R.string.tool_prompts_category_skills
        ToolPromptCategory.MEMORY -> R.string.tool_prompts_category_memory
        ToolPromptCategory.SEARCH -> R.string.tool_prompts_category_search
        ToolPromptCategory.CONVERSATION -> R.string.tool_prompts_category_conversation
        ToolPromptCategory.LOCAL_TOOLS -> R.string.tool_prompts_category_local_tools
        ToolPromptCategory.WORKSPACE_TOOLS -> R.string.tool_prompts_category_workspace_tools
        ToolPromptCategory.ASSISTANT_CONFIG -> R.string.tool_prompts_category_assistant_config
    }
)

/** 与默认值相同就删掉覆盖，让工具继续使用内置默认。 */
private fun Map<String, String>.withOverride(
    key: String,
    value: String,
    default: String,
): Map<String, String> = if (value == default) this - key else this + (key to value)
