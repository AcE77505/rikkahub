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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Tools
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
                title = { Text("子代理") },
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
                CardGroup(title = { Text("全局设置") }) {
                    formItem(
                        headlineContent = { Text("默认子代理模型") },
                        supportingContent = { Text("未单独设置模型的子代理使用；清空后跟随父模型") },
                    ) {
                        ModelField(
                            model = modelListState.currentModel,
                            onClick = { modelListState.open() },
                            placeholder = "跟随父模型",
                            onClear = {
                                vm.updateSettings { it.copy(subAgentModelId = null) }
                            },
                        )
                    }
                    item(
                        onClick = { showConcurrencyDialog = true },
                        supportingContent = { Text("同一会话同时运行的子代理数量") },
                        headlineContent = { Text("并发上限") },
                        trailingContent = { Text("${settings.subAgentMaxConcurrency}") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SubAgentRuns) },
                        leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
                        supportingContent = { Text("查看这台设备上的子代理运行记录") },
                        headlineContent = { Text("运行记录") },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                }
            }

            item("defaultTools") {
                CardGroup(title = { Text("默认工具模板") }) {
                    item(
                        onClick = { navController.navigate(Screen.SubAgentDefaultTools) },
                        leadingContent = { CardGroupIcon(HugeIcons.Tools) },
                        supportingContent = {
                            Text("未自定义的子代理共享这一份工具列表（${settings.subAgentDefaultTools.size} 个）")
                        },
                        headlineContent = { Text("默认工具模板") },
                        trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                    )
                }
            }

            item("subagents") {
                CardGroup(title = { Text("子代理定义") }) {
                    settings.subAgents.forEach { agent ->
                        item(
                            onClick = {
                                navController.navigate(Screen.SubAgentDetail(agent.id.toString()))
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.PencilEdit01) },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(if (agent.readOnly) "只读" else "可写")
                                        append(" · ${agent.maxSteps} 步 · ${agent.timeoutSeconds}s")
                                        if (agent.requiresApproval) append(" · 派发需审批")
                                        if (agent.allowedTools == null) append(" · 默认工具模板")
                                    }
                                )
                            },
                            headlineContent = { Text(agent.name) },
                        )
                    }
                    item(
                        onClick = { navController.navigate(Screen.SubAgentDetail("")) },
                        leadingContent = { CardGroupIcon(HugeIcons.Add01) },
                        supportingContent = { Text("新建一个可被助手派发的子代理") },
                        headlineContent = { Text("新建子代理") },
                    )
                }
            }

            item("hint") {
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        text = "子代理在独立上下文里运行，不会继承主助手的角色设定和对话历史。" +
                            "派发时只把任务描述发给它，完成后只回传最终报告。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "这里只管理定义；哪个助手能派发哪些子代理，请在助手详情页里勾选。",
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
            title = { Text("并发上限") },
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
                    Text("取消")
                }
            },
        )
    }
}
