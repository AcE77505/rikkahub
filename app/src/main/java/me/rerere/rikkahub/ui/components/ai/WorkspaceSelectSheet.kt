package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity

@Composable
/**
 * 选择要「声明」给 AI 的工作区。
 *
 * 本地与 SSH 各最多声明一个：同组内选一个会取消另一个，两组合并后最多两个。
 */
internal fun WorkspaceSelectSheet(
    declaredIds: Set<String>,
    workspaces: List<WorkspaceEntity>,
    onSelect: (workspaceId: String?, type: String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_select),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val local = workspaces.filterNot { it.isSsh }
                val remote = workspaces.filter { it.isSsh }
                if (local.isNotEmpty()) {
                    WorkspaceGroup(
                        title = stringResource(R.string.workspace_detail_connection_local),
                        workspaces = local,
                        declaredIds = declaredIds,
                        onSelect = onSelect,
                    )
                }
                if (remote.isNotEmpty()) {
                    WorkspaceGroup(
                        title = stringResource(R.string.workspace_detail_connection_ssh),
                        workspaces = remote,
                        declaredIds = declaredIds,
                        onSelect = onSelect,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 管理工作区
            ListItem(
                leadingContent = {
                    Icon(HugeIcons.Codesandbox, contentDescription = null)
                },
                headlineContent = {
                    Text(stringResource(R.string.workspace_manage))
                },
                trailingContent = {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onManage() },
            )
        }
    }
}

@Composable
private fun WorkspaceGroup(
    title: String,
    workspaces: List<WorkspaceEntity>,
    declaredIds: Set<String>,
    onSelect: (workspaceId: String?, type: String) -> Unit,
) {
    val type = workspaces.first().type
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    // 本组不声明
    WorkspaceSelectRow(
        title = stringResource(R.string.workspace_page_not_declared),
        selected = workspaces.none { it.id in declaredIds },
        onClick = { onSelect(null, type) },
    )
    workspaces.forEach { workspace ->
        WorkspaceSelectRow(
            title = workspace.name,
            status = if (workspace.isSsh) {
                workspace.sshTarget()?.endpoint ?: workspace.sshHost
            } else {
                workspace.directory?.takeIf { it.isNotBlank() }
            },
            selected = workspace.id in declaredIds,
            onClick = { onSelect(workspace.id, workspace.type) },
        )
    }
}

@Composable
private fun WorkspaceSelectRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    status: String? = null,
) {
    ListItem(
        leadingContent = {
            Icon(HugeIcons.Codesandbox, contentDescription = null)
        },
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = status?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = if (selected) {
            {
                Icon(
                    imageVector = HugeIcons.Tick02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            }
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable { onClick() },
    )
}
