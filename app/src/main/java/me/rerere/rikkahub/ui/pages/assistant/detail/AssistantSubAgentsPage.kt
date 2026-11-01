package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 「助手详情 → 子代理」：选择这个助手可以派发哪些全局子代理。
 *
 * 子代理定义本身在「设置 → 工具 → 子代理管理」里维护。
 */
@Composable
fun AssistantSubAgentsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
        if (settings.subAgents.isEmpty()) {
            Box(modifier = Modifier.padding(innerPadding)) {
                ExtensionEmptyState(
                    message = "还没有配置子代理。先在设置 → 工具里创建一个，再回来启用。",
                    buttonText = "去创建子代理",
                    onAction = { navController.navigate(Screen.SettingSubAgents) },
                    icon = HugeIcons.Tools,
                )
            }
            return@Scaffold
        }

        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding(),
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("subagents") {
                CardGroup(title = { Text("可派发的子代理") }) {
                    settings.subAgents.forEach { agent ->
                        switchItem(
                            checked = agent.id in assistant.enabledSubAgents,
                            onCheckedChange = { enabled ->
                                val ids = if (enabled) {
                                    assistant.enabledSubAgents + agent.id
                                } else {
                                    assistant.enabledSubAgents - agent.id
                                }
                                vm.update(assistant.copy(enabledSubAgents = ids))
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.Tools) },
                            supportingContent = {
                                Text(
                                    buildString {
                                        if (agent.description.isNotBlank()) {
                                            append(agent.description)
                                            append("\n")
                                        }
                                        append(if (agent.readOnly) "只读" else "可写")
                                        append(" · ${agent.maxSteps} 步 · ${agent.timeoutSeconds}s")
                                    }
                                )
                            },
                            headlineContent = { Text(agent.name) },
                        )
                    }
                }
            }
        }
    }
}
