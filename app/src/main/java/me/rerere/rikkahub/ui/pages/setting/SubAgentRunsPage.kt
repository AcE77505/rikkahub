package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.rikkahub.R
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
                title = { Text(stringResource(R.string.subagent_runs_title)) },
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
                    text = stringResource(R.string.subagent_runs_empty),
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
                CardGroup(title = { Text(stringResource(R.string.subagent_all_runs)) }) {
                    runs.forEach { run ->
                        item(
                            onClick = { selected = run },
                            leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
                            supportingContent = {
                                Text(
                                    listOf(
                                        stringResource(statusRes(run.status)),
                                        run.startedAt.toTimeText(),
                                        stringResource(R.string.subagent_run_steps, run.steps),
                                        stringResource(
                                            R.string.subagent_run_conversation,
                                            run.parentConversationId.take(8),
                                        ),
                                    ).joinToString(" · ")
                                )
                            },
                            headlineContent = {
                                Text(run.label.ifBlank { stringResource(R.string.subagent_label) })
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
                error = meta.error ?: meta.status.name.lowercase()
                loading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(meta.label.ifBlank { stringResource(R.string.subagent_label) }) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = listOf(
                        stringResource(statusRes(meta.status)),
                        meta.startedAt.toTimeText(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    loading -> Text(stringResource(R.string.subagent_loading))
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
                                Text(stringResource(R.string.subagent_load_more))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.subagent_close))
            }
        },
    )
}

@StringRes
private fun statusRes(status: SubAgentRunStatus): Int = when (status) {
    SubAgentRunStatus.RUNNING -> R.string.subagent_status_running
    SubAgentRunStatus.COMPLETED -> R.string.subagent_status_completed
    SubAgentRunStatus.CANCELLED -> R.string.subagent_status_cancelled
    SubAgentRunStatus.FAILED -> R.string.subagent_status_failed
    SubAgentRunStatus.INTERRUPTED -> R.string.subagent_status_interrupted
}

private fun Long.toTimeText(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(runTimeFormatter)
