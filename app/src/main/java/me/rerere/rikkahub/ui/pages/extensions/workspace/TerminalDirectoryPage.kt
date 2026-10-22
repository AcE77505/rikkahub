package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.File02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/** 终端目录里的一个可浏览根：工具链（只读）或某工作区的 home / tmp（可写） */
private data class TerminalNode(
    val label: String,
    val dir: java.io.File,
    val readOnly: Boolean,
)

private const val MAX_PREVIEW_BYTES = 256L * 1024

/**
 * 终端目录浏览：`files/toolchain/`（只读）与每个工作区的 `home/`、`tmp/`（可读写）。
 *
 * 这些目录不暴露给 AI 文件工具，只在应用内查看/编辑（例如 `.bashrc`、启动脚本）。
 * 若要在系统文件管理器里访问，用「设置 → 工具 → 在文件管理器中打开」的说明。
 */
@Composable
fun TerminalDirectoryPage(workspaceId: String? = null) {
    val context = LocalContext.current
    val repository: WorkspaceRepository = koinInject()
    val workspaces by repository.listFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    val nodes = remember(workspaces) {
        buildList {
            add(TerminalNode("toolchain", java.io.File(context.filesDir, "toolchain"), true))
            val ordered = workspaces.sortedBy { it.name.lowercase() }
            val preferred = ordered.firstOrNull { it.id == workspaceId } ?: ordered.firstOrNull()
            ordered.forEach { workspace ->
                val base = java.io.File(java.io.File(context.filesDir, "workspaces"), workspace.root)
                add(TerminalNode("${workspace.name}/home", java.io.File(base, "home"), false))
                add(TerminalNode("${workspace.name}/tmp", java.io.File(base, "tmp"), false))
            }
            if (workspaceId != null && preferred != null) {
                // 从工作区详情进来时，把它排在最前面
                val preferredLabel = preferred.name
                val head = filter { it.label.startsWith("$preferredLabel/") }
                removeAll(head)
                addAll(1, head)
            }
        }
    }

    var current by remember { mutableStateOf<TerminalNode?>(null) }
    var path by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<java.io.File?>(null) }
    var editContent by remember { mutableStateOf("") }

    val entries by produceState(
        initialValue = emptyList<java.io.File>(),
        current,
        path,
    ) {
        value = withContext(Dispatchers.IO) {
            val dir = current?.dir ?: return@withContext emptyList()
            val target = if (path.isBlank()) dir else java.io.File(dir, path)
            target.listFiles()
                .orEmpty()
                .sortedWith(compareBy<java.io.File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = current?.let { node ->
                            if (path.isBlank()) node.label else "${node.label}/$path"
                        } ?: stringResource(R.string.setting_tools_terminal_dir),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (current == null) {
                item {
                    Text(
                        text = stringResource(R.string.setting_tools_open_in_file_manager_steps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                itemsIndexed(nodes) { index, node ->
                    DirectoryRow(
                        name = node.label + if (node.readOnly) " · readonly" else "",
                        subtitle = node.dir.absolutePath,
                        isDirectory = true,
                        shape = cardGroupItemShape(index, nodes.size),
                        onClick = {
                            current = node
                            path = ""
                        },
                    )
                }
            } else {
                val node = current!!
                itemsIndexed(entries) { index, file ->
                    val relative = if (path.isBlank()) file.name else "$path/${file.name}"
                    DirectoryRow(
                        name = file.name,
                        subtitle = if (file.isDirectory) "" else file.length().fileSizeToString(),
                        isDirectory = file.isDirectory,
                        shape = cardGroupItemShape(index, entries.size),
                        onClick = {
                            if (file.isDirectory) {
                                path = relative
                            } else if (!node.readOnly || file.canRead()) {
                                scope.launch {
                                    val content = withContext(Dispatchers.IO) {
                                        if (file.length() > MAX_PREVIEW_BYTES) {
                                            "文件过大，无法预览"
                                        } else {
                                            runCatching { file.readText() }.getOrDefault("无法读取该文件")
                                        }
                                    }
                                    editContent = content
                                    editTarget = file
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    val target = editTarget
    if (target != null) {
        val readOnly = current?.readOnly == true
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                OutlinedTextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 420.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    readOnly = readOnly,
                )
            },
            confirmButton = {
                if (!readOnly) {
                    TextButton(
                        onClick = {
                            val file = target
                            val content = editContent
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { file.writeText(content) }
                                }
                                editTarget = null
                            }
                        },
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                } else {
                    TextButton(onClick = { editTarget = null }) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            },
            dismissButton = {
                if (!readOnly) {
                    TextButton(onClick = { editTarget = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            },
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun DirectoryRow(
    name: String,
    subtitle: String,
    isDirectory: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        shape = shape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroupIcon(if (isDirectory) HugeIcons.Folder01 else HugeIcons.File02)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
    }
}
