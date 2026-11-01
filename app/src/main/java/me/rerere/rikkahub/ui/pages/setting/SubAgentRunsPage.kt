package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunMeta
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunStatus
import me.rerere.rikkahub.data.ai.subagent.SubAgentStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val runTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 子代理运行记录：列出这台设备上的所有运行，可读取已完成/已取消的完整结果 */
@Composable
fun SubAgentRunsPage(store: SubAgentStore = koinInject()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var runs by remember { mutableStateOf<List<SubAgentRunMeta>>(emptyList()) }
    var selected by remember { mutableStateOf<SubAgentRunMeta?>(null) }

    LaunchedEffect(Unit) {
        runs = runCatching { store.listAll() }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("子代理运行记录") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (runs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有子代理运行记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("runs") {
                CardGroup(title = { Text("全部运行") }) {
                    runs.forEach { run ->
                        item(
                            onClick = { selected = run },
                            leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(statusLabel(run.status))
                                        append(" · ")
                                        append(run.startedAt.toTimeText())
                                        append(" · ")
                                        append("${run.steps} 步")
                                        append(" · 对话 ${run.parentConversationId.take(8)}")
                                    }
                                )
                            },
                            headlineContent = {
                                Text(run.label.ifBlank { "子代理" })
                            },
                        )
                    }
                }
            }
        }
    }

    selected?.let { meta ->
        SubAgentRunDialog(
            meta = meta,
            store = store,
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun SubAgentRunDialog(
    meta: SubAgentRunMeta,
    store: SubAgentStore,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember(meta.ref) { mutableStateOf("") }
    var nextOffset by remember(meta.ref) { mutableStateOf<Int?>(null) }
    var error by remember(meta.ref) { mutableStateOf<String?>(null) }
    var loading by remember(meta.ref) { mutableStateOf(true) }

    suspend fun load(offset: Int) {
        loading = true
        runCatching {
            store.readResult(
                ref = meta.ref,
                parentConversationId = meta.parentConversationId,
                offsetBytes = offset,
                limitBytes = SubAgentStore.DEFAULT_RESULT_BYTES,
            )
        }.onSuccess { page ->
            text += page.text
            nextOffset = page.nextOffsetBytes
        }.onFailure {
            error = it.message ?: it::class.simpleName.orEmpty()
        }
        loading = false
    }

    LaunchedEffect(meta.ref) {
        when (meta.status) {
            SubAgentRunStatus.COMPLETED, SubAgentRunStatus.CANCELLED -> load(0)
            else -> {
                error = meta.error ?: statusLabel(meta.status)
                loading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(meta.label.ifBlank { "子代理" }) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${statusLabel(meta.status)} · ${meta.startedAt.toTimeText()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    loading -> Text("读取中…")
                    error != null -> Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {
                        SelectionContainer {
                            Text(text = text)
                        }
                        val next = nextOffset
                        if (next != null) {
                            TextButton(onClick = { scope.launch { load(next) } }) {
                                Text("加载更多")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

private fun statusLabel(status: SubAgentRunStatus): String = when (status) {
    SubAgentRunStatus.RUNNING -> "运行中"
    SubAgentRunStatus.COMPLETED -> "已完成"
    SubAgentRunStatus.CANCELLED -> "已取消"
    SubAgentRunStatus.FAILED -> "失败"
    SubAgentRunStatus.INTERRUPTED -> "已中断"
}

private fun Long.toTimeText(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(runTimeFormatter)
