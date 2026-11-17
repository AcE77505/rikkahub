package me.rerere.rikkahub.ui.components.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.writeClipboardText

/**
 * 设置项标题和描述之间显示 AI 可见工具的英文名。
 *
 * 一个开关可能对应多个工具（例如「执行命令」对应 shell / shell_output / shell_kill），
 * 这时用逗号连成一行。
 */
@Composable
fun ToolNameLabel(
    toolNames: List<String>,
    modifier: Modifier = Modifier,
) {
    if (toolNames.isEmpty()) return
    Text(
        text = toolNames.joinToString(", "),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 长按工具设置项时展示的英文名，可复制。 */
@Composable
fun ToolNameDialog(
    toolNames: List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val copiedText = stringResource(R.string.setting_tools_tool_identifier_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_tools_tool_identifier_dialog_title)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    toolNames.forEach { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    context.writeClipboardText(toolNames.joinToString(", "))
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.setting_tools_tool_identifier_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_tools_tool_identifier_close))
            }
        },
    )
}
