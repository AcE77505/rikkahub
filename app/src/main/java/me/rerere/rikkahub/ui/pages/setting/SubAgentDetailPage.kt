package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentToolDefaults
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/**
 * 子代理编辑页（全屏），复用助手的聊天模型选择与思考预算组件。
 *
 * [id] 为空表示新建；非空时编辑对应定义。
 */
@Composable
fun SubAgentDetailPage(id: String, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val existing = remember(settings.subAgents, id) {
        settings.subAgents.firstOrNull { it.id.toString() == id }
    }
    val isNew = id.isBlank()
    val initial = existing ?: SubAgent()

    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var description by remember(initial.id) { mutableStateOf(initial.description) }
    var systemPrompt by remember(initial.id) { mutableStateOf(initial.systemPrompt) }
    var readOnly by remember(initial.id) { mutableStateOf(initial.readOnly) }
    var requiresApproval by remember(initial.id) { mutableStateOf(initial.requiresApproval) }
    var rememberApproval by remember(initial.id) { mutableStateOf(initial.rememberApproval) }
    var temperature by remember(initial.id) { mutableStateOf(initial.temperature) }
    var maxSteps by remember(initial.id) { mutableStateOf(initial.maxSteps.toString()) }
    var timeoutSeconds by remember(initial.id) { mutableStateOf(initial.timeoutSeconds.toString()) }
    var modelId by remember(initial.id) { mutableStateOf(initial.modelId) }
    var reasoningLevel by remember(initial.id) {
        mutableStateOf(initial.reasoningLevel ?: ReasoningLevel.AUTO)
    }
    var useDefaultTools by remember(initial.id) {
        mutableStateOf(initial.allowedTools == null)
    }
    var customTools by remember(initial.id) {
        mutableStateOf(initial.allowedTools ?: settings.subAgentDefaultTools)
    }
    var extraToolsText by remember(initial.id) {
        val known = SubAgentToolDefaults.ALL_TOOLS.toSet()
        mutableStateOf(
            (initial.allowedTools ?: emptySet())
                .filter { it !in known }
                .joinToString(", ")
        )
    }
    val modelListState = rememberModelListState(
        modelId = modelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    ModelListSheet(
        state = modelListState,
        onSelect = { modelId = it.id },
    )

    fun save() {
        val agent = SubAgent(
            id = existing?.id ?: Uuid.random(),
            name = name.trim(),
            description = description.trim(),
            systemPrompt = systemPrompt.trim(),
            modelId = modelId,
            reasoningLevel = reasoningLevel,
            readOnly = readOnly,
            allowedTools = if (useDefaultTools) {
                null
            } else {
                customTools + SubAgentToolDefaults.parseExtraTools(extraToolsText)
            },
            maxSteps = maxSteps.toIntOrNull() ?: initial.maxSteps,
            timeoutSeconds = timeoutSeconds.toIntOrNull() ?: initial.timeoutSeconds,
            requiresApproval = requiresApproval,
            rememberApproval = rememberApproval,
            temperature = temperature,
        )
        vm.updateSettings { current ->
            val exists = current.subAgents.any { it.id == agent.id }
            current.copy(
                subAgents = if (exists) {
                    current.subAgents.map { if (it.id == agent.id) agent else it }
                } else {
                    current.subAgents + agent
                },
            )
        }
        navController.popBackStack()
    }

    fun delete() {
        val idToRemove = existing?.id ?: return
        vm.updateSettings { current ->
            current.copy(
                subAgents = current.subAgents.filterNot { it.id == idToRemove },
                assistants = current.assistants.map { assistant ->
                    if (idToRemove in assistant.enabledSubAgents) {
                        assistant.copy(enabledSubAgents = assistant.enabledSubAgents - idToRemove)
                    } else {
                        assistant
                    }
                },
            )
        }
        navController.popBackStack()
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(if (isNew) R.string.subagent_new_title else R.string.subagent_edit_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (!isNew) {
                        TextButton(onClick = ::delete) {
                            Text(stringResource(R.string.subagent_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = ::save,
                        enabled = name.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.subagent_save))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (settings.init) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (!isNew && existing == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.subagent_missing))
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("basic") {
                CardGroup(title = { Text(stringResource(R.string.subagent_basic)) }) {
                    formItem(headlineContent = { Text(stringResource(R.string.subagent_name)) }) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    formItem(headlineContent = { Text(stringResource(R.string.subagent_description)) }) {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.subagent_system_prompt)) },
                        supportingContent = { Text(stringResource(R.string.subagent_system_prompt_desc)) },
                    ) {
                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            minLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item("model") {
                CardGroup(title = { Text(stringResource(R.string.subagent_model_section)) }) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.subagent_chat_model)) },
                        supportingContent = { Text(stringResource(R.string.subagent_chat_model_desc)) },
                    ) {
                        ModelField(
                            model = modelListState.currentModel,
                            onClick = { modelListState.open() },
                            placeholder = stringResource(R.string.subagent_follow_parent_model),
                            onClear = { modelId = null },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.subagent_thinking_budget)) },
                        supportingContent = { Text(stringResource(R.string.subagent_thinking_budget_desc)) },
                        trailingContent = {
                            ReasoningButton(
                                reasoningLevel = reasoningLevel,
                                onUpdateReasoningLevel = { reasoningLevel = it },
                            )
                        },
                    )
                    formItem(
                        headlineContent = { Text(stringResource(R.string.subagent_temperature)) },
                        supportingContent = { Text(stringResource(R.string.subagent_temperature_desc)) },
                        trailingContent = {
                            Switch(
                                checked = temperature != null,
                                onCheckedChange = { enabled ->
                                    temperature = if (enabled) 1.0f else null
                                },
                            )
                        },
                    ) {
                        AnimatedVisibility(visible = temperature != null) {
                            var temperatureInput by remember(initial.id) {
                                mutableStateOf((temperature ?: 1.0f).toString())
                            }
                            val temperatureValue = temperatureInput.toFloatOrNull()
                            OutlinedTextField(
                                value = temperatureInput,
                                onValueChange = { input ->
                                    temperatureInput = input
                                    input.toFloatOrNull()
                                        ?.takeIf { it in 0f..2f }
                                        ?.let { temperature = it }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                isError = temperatureValue == null || temperatureValue !in 0f..2f,
                                supportingText = { Text("0 - 2") },
                            )
                        }
                    }
                }
            }

            item("permissions") {
                CardGroup(title = { Text(stringResource(R.string.subagent_permissions)) }) {
                    switchItem(
                        checked = readOnly,
                        onCheckedChange = { readOnly = it },
                        supportingContent = { Text(stringResource(R.string.subagent_readonly_desc)) },
                        headlineContent = { Text(stringResource(R.string.subagent_readonly)) },
                    )
                    switchItem(
                        checked = requiresApproval,
                        onCheckedChange = { requiresApproval = it },
                        supportingContent = { Text(stringResource(R.string.subagent_requires_approval_desc)) },
                        headlineContent = { Text(stringResource(R.string.subagent_requires_approval)) },
                    )
                    switchItem(
                        checked = rememberApproval,
                        onCheckedChange = { rememberApproval = it },
                        enabled = requiresApproval,
                        supportingContent = { Text(stringResource(R.string.subagent_remember_approval_desc)) },
                        headlineContent = { Text(stringResource(R.string.subagent_remember_approval)) },
                    )
                }
            }

            item("tools") {
                CardGroup(title = { Text(stringResource(R.string.subagent_allowed_tools)) }) {
                    switchItem(
                        checked = useDefaultTools,
                        onCheckedChange = { enabled ->
                            useDefaultTools = enabled
                            if (!enabled) {
                                customTools = settings.subAgentDefaultTools
                            }
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.subagent_use_default_tools_desc,
                                    settings.subAgentDefaultTools.size,
                                )
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.subagent_use_default_tools)) },
                    )
                    if (!useDefaultTools) {
                        SubAgentToolDefaults.TOOL_GROUPS.forEach { group ->
                            item(
                                headlineContent = { Text(group.title) },
                                supportingContent = { Text(stringResource(R.string.subagent_tools_count, group.tools.size)) },
                            )
                            group.tools.forEach { toolName ->
                                switchItem(
                                    checked = toolName in customTools,
                                    onCheckedChange = { checked ->
                                        customTools = if (checked) {
                                            customTools + toolName
                                        } else {
                                            customTools - toolName
                                        }
                                    },
                                    supportingContent = if (readOnly && toolName in SubAgentToolDefaults.WRITE_TOOLS) {
                                        { Text(stringResource(R.string.subagent_readonly_tool_note)) }
                                    } else {
                                        null
                                    },
                                    headlineContent = { Text(toolName) },
                                )
                            }
                        }
                        formItem(
                            headlineContent = { Text(stringResource(R.string.subagent_extra_tools)) },
                            supportingContent = { Text(stringResource(R.string.subagent_extra_tools_desc)) },
                        ) {
                            OutlinedTextField(
                                value = extraToolsText,
                                onValueChange = { extraToolsText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                }
            }

            item("limits") {
                CardGroup(title = { Text(stringResource(R.string.subagent_limits)) }) {
                    formItem(headlineContent = { Text(stringResource(R.string.subagent_max_steps)) }) {
                        OutlinedTextField(
                            value = maxSteps,
                            onValueChange = { maxSteps = it.filter(Char::isDigit) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    formItem(headlineContent = { Text(stringResource(R.string.subagent_timeout_seconds)) }) {
                        OutlinedTextField(
                            value = timeoutSeconds,
                            onValueChange = { timeoutSeconds = it.filter(Char::isDigit) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
