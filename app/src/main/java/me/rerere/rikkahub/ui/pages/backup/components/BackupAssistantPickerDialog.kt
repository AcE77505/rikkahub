package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import kotlin.uuid.Uuid

/**
 * 导入前选择归属助手的对话框。导入的对话会挂到选中的助手名下，
 * 当前正在使用的助手会带一个「当前使用」标识。
 *
 * 点击列表项只切换高亮，用户点「确定」后才返回结果（导入方随后才打开文件选择器）。
 */
@Composable
fun BackupAssistantPickerDialog(
    assistants: List<Assistant>,
    currentAssistantId: Uuid,
    onConfirm: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    // 默认选中当前正在使用的助手，用户点了别的行只改高亮
    var selectedId by remember(assistants) { mutableStateOf(currentAssistantId) }
    val selectedAssistant = assistants.firstOrNull { it.id == selectedId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_import_assistant_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.backup_page_import_assistant_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (assistants.isEmpty()) {
                    Text(
                        text = stringResource(R.string.backup_page_import_assistant_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(assistants, key = { it.id }) { assistant ->
                            val isCurrent = assistant.id == currentAssistantId
                            val isSelected = assistant.id == selectedId
                            Card(
                                onClick = { selectedId = assistant.id },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    UIAvatar(
                                        name = assistant.name.ifBlank { defaultAssistantName },
                                        value = assistant.avatar,
                                        modifier = Modifier.size(36.dp),
                                    )
                                    Text(
                                        text = assistant.name.ifBlank { defaultAssistantName },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isCurrent) {
                                        Text(
                                            text = stringResource(R.string.assistant_current_in_use),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedAssistant?.let(onConfirm) },
                enabled = selectedAssistant != null,
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
