package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.pages.backup.DeepSeekRestoreResult

/**
 * DeepSeek 导入结束后的汇总：新增 / 覆盖 / 并存 / 保留 / 无变化。
 * 用户选择保留的有变化对话可以在详情里查看。
 */
@Composable
fun DeepSeekImportResultDialog(
    result: DeepSeekRestoreResult,
    onShowKept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_deepseek_import_done_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.backup_page_deepseek_import_summary_new, result.result.newConversations))
                if (result.result.overwrittenConversations > 0) {
                    Text(
                        stringResource(
                            R.string.backup_page_deepseek_import_summary_overwritten,
                            result.result.overwrittenConversations,
                        )
                    )
                }
                if (result.result.copiedConversations > 0) {
                    Text(
                        stringResource(
                            R.string.backup_page_deepseek_import_summary_copied,
                            result.result.copiedConversations,
                        )
                    )
                }
                if (result.keptTitles.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.backup_page_deepseek_import_summary_kept,
                            result.keptTitles.size,
                        )
                    )
                }
                if (result.unchangedConversations > 0) {
                    Text(
                        stringResource(
                            R.string.backup_page_deepseek_import_summary_unchanged,
                            result.unchangedConversations,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result.result.skippedConversations > 0) {
                    Text(
                        stringResource(
                            R.string.backup_page_deepseek_import_summary_unreadable,
                            result.result.skippedConversations,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_page_deepseek_import_done))
            }
        },
        dismissButton = if (result.keptTitles.isEmpty()) {
            null
        } else {
            {
                TextButton(onClick = onShowKept) {
                    Text(stringResource(R.string.backup_page_deepseek_import_show_kept))
                }
            }
        },
    )
}

/** 有变化但用户选择保留的对话标题列表，可按标题搜索。 */
@Composable
fun DeepSeekKeptConversationsDialog(
    titles: List<String>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(titles, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) titles else titles.filter { it.contains(keyword, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_deepseek_kept_title, titles.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.backup_page_deepseek_kept_search_hint)) },
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.backup_page_deepseek_kept_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered) { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_page_deepseek_import_done))
            }
        },
    )
}
