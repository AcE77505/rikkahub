package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import me.rerere.rikkahub.data.model.SubAgentToolDefaults
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 默认工具模板编辑页。
 *
 * 未自定义 `allowedTools` 的子代理共享这里的列表；只读子代理仍会自动过滤写文件和 shell。
 */
@Composable
fun SubAgentDefaultToolsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var tools by remember { mutableStateOf(settings.subAgentDefaultTools) }
    var extraToolsText by remember {
        val known = SubAgentToolDefaults.ALL_TOOLS.toSet()
        mutableStateOf(
            settings.subAgentDefaultTools
                .filter { it !in known }
                .joinToString(", ")
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("默认工具模板") },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(
                        onClick = {
                            tools = SubAgentToolDefaults.DEFAULT_ALLOWED_TOOLS
                            extraToolsText = ""
                        },
                    ) {
                        Text("恢复默认")
                    }
                    TextButton(
                        onClick = {
                            vm.updateSettings {
                                it.copy(
                                    subAgentDefaultTools = tools +
                                        SubAgentToolDefaults.parseExtraTools(extraToolsText)
                                )
                            }
                            navController.popBackStack()
                        },
                    ) {
                        Text("保存")
                    }
                },
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
            item("tools") {
                CardGroup(title = { Text("允许的工具") }) {
                    SubAgentToolDefaults.TOOL_GROUPS.forEach { group ->
                        item(
                            headlineContent = { Text(group.title) },
                            supportingContent = { Text("${group.tools.size} 个工具") },
                        )
                        group.tools.forEach { toolName ->
                            switchItem(
                                checked = toolName in tools,
                                onCheckedChange = { checked ->
                                    tools = if (checked) tools + toolName else tools - toolName
                                },
                                supportingContent = if (toolName in SubAgentToolDefaults.WRITE_TOOLS) {
                                    { Text("只读子代理会自动过滤这个工具") }
                                } else {
                                    null
                                },
                                headlineContent = { Text(toolName) },
                            )
                        }
                    }
                    formItem(
                        headlineContent = { Text("其他工具名") },
                        supportingContent = { Text("MCP 或其他动态工具名，用逗号分隔") },
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
            item("hint") {
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        text = "这里维护的是共享模板。新建子代理默认使用它；" +
                            "在子代理编辑页关闭「使用默认工具模板」后，会复制一份到该子代理自己的列表。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
