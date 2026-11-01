package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/** 「设置 → 工具 → 子代理」：管理全局子代理定义 */
@Composable
fun SettingSubAgentsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val modelListState = rememberModelListState(
        modelId = settings.subAgentModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    var showConcurrencyDialog by remember { mutableStateOf(false) }
    ModelListSheet(
        state = modelListState,
        onSelect = { model ->
            vm.updateSettings { it.copy(subAgentModelId = model.id) }
        },
    )

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.subagent_page_title)) },
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
            item("global") {
                CardGroup(title = { Text(stringResource(R.string.subagent_global_settings)) }) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.subagent_default_model)) },
                        supportingContent = { Text(stringResource(R.string.subagent_default_model_desc)) },
                    ) {
                        ModelField(
                            model = modelListState.currentModel,
                            onClick = { modelListState.open() },
                            placeholder = stringResource(R.string.subagent_follow_parent_model),
                            onClear = {
                                vm.updateSettings { it.copy(subAgentModelId = null) }
                            },
                        )
                    }
                    item(
                        onClick = { showConcurrencyDialog = true },
                        supportingContent = { Text(stringResource(R.string.subagent_concurrency_desc)) },
                        headlineContent = { Text(stringResource(R.string.subagent_concurrency_limit)) },
                        trailingContent = { Text("${settings.subAgentMaxConcurrency}") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SubAgentRuns) },
                        leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
                        supportingContent = { Text(stringResource(R.string.subagent_runs_desc)) },
                        headlineContent = { Text(stringResource(R.string.subagent_runs)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                }
            }

            item("defaultTools") {
                CardGroup(title = { Text(stringResource(R.string.subagent_default_tools)) }) {
                    item(
                        onClick = { navController.navigate(Screen.SubAgentDefaultTools) },
                        leadingContent = { CardGroupIcon(HugeIcons.Tools) },
                        supportingContent = {
                            Text(stringResource(R.string.subagent_default_tools_shared_desc, settings.subAgentDefaultTools.size))
                        },
                        headlineContent = { Text(stringResource(R.string.subagent_default_tools)) },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                }
            }

            item("subagents") {
                CardGroup(title = { Text(stringResource(R.string.subagent_definitions)) }) {
                    settings.subAgents.forEach { agent ->
                        item(
                            onClick = {
                                navController.navigate(Screen.SubAgentDetail(agent.id.toString()))
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.PencilEdit01) },
                            supportingContent = {
                                val access = stringResource(
                                    if (agent.readOnly) R.string.subagent_badge_readonly
                                    else R.string.subagent_badge_writable
                                )
                                val limits = stringResource(
                                    R.string.subagent_badge_steps_timeout,
                                    agent.maxSteps,
                                    agent.timeoutSeconds,
                                )
                                val extras = buildList {
                                    if (agent.requiresApproval) {
                                        add(stringResource(R.string.subagent_badge_requires_approval))
                                    }
                                    if (agent.allowedTools == null) {
                                        add(stringResource(R.string.subagent_badge_default_tools))
                                    }
                                }
                                Text((listOf(access, limits) + extras).joinToString(" · "))
                            },
                            headlineContent = { Text(agent.name) },
                        )
                    }
                    item(
                        onClick = { navController.navigate(Screen.SubAgentDetail("")) },
                        leadingContent = { CardGroupIcon(HugeIcons.Add01) },
                        supportingContent = { Text(stringResource(R.string.subagent_new_desc)) },
                        headlineContent = { Text(stringResource(R.string.subagent_new)) },
                    )
                }
            }

            item("hint") {
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        text = stringResource(R.string.subagent_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.subagent_manage_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    if (showConcurrencyDialog) {
        AlertDialog(
            onDismissRequest = { showConcurrencyDialog = false },
            title = { Text(stringResource(R.string.subagent_concurrency_limit)) },
            text = {
                Column {
                    (1..8).forEach { value ->
                        TextButton(
                            onClick = {
                                vm.updateSettings {
                                    it.copy(subAgentMaxConcurrency = value)
                                }
                                showConcurrencyDialog = false
                            },
                        ) {
                            Text("$value")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConcurrencyDialog = false }) {
                    Text(stringResource(R.string.subagent_cancel))
                }
            },
        )
    }
}
