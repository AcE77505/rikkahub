package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderCode
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Tag01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.workspace.WorkspaceFileEntry
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.File

private enum class WorkspaceDetailTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    BASIC(HugeIcons.Settings03, R.string.workspace_detail_tab_basic),
    FILES(HugeIcons.File02, R.string.workspace_detail_tab_files);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val settingsError by vm.settingsError.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { WorkspaceDetailTab.entries.size }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(inputStream, fileName)
    }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(entry, outputStream)
    }
    val directoryExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        vm.exportFilesToDirectory(uri, context.contentResolver)
    }

    val onFilesTab = pagerState.currentPage == WorkspaceDetailTab.FILES.ordinal

    BackHandler(enabled = onFilesTab && state.path.isNotBlank()) {
        vm.goUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (onFilesTab) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    // 命令环境始终可用（原生 shell），不再有「装了 rootfs 才能进终端」的限制
                    IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                        Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = WorkspaceDetailTab.entries,
                modifier = Modifier.padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (WorkspaceDetailTab.entries[page]) {
                    WorkspaceDetailTab.BASIC -> WorkspaceBasicPage(
                        state = state,
                        onDirectoryChange = vm::setDirectory,
                        onToolchainChange = vm::setToolchainEnabled,
                        onOpenTerminalHome = {
                            navController.navigate(Screen.SettingTerminalDirectory(id))
                        },
                        onOpenTerminal = { navController.navigate(Screen.WorkspaceTerminal(id)) },
                    )

                    WorkspaceDetailTab.FILES -> WorkspaceFilesPage(
                        state = state,
                        isActive = onFilesTab,
                        onGoUp = vm::goUp,
                        onResolveImage = { entry -> vm.resolveImageFile(entry) },
                        onOpen = { entry ->
                            when {
                                entry.isDirectory -> vm.open(entry)

                                entry.name.substringAfterLast('.').equals("svg", ignoreCase = true) ->
                                    navController.navigate(
                                        Screen.WorkspaceFileEditor(id, entry.path)
                                    )

                                else -> when (entry.detectFileType()) {
                                    WorkspaceFileType.TEXT -> navController.navigate(
                                        Screen.WorkspaceFileEditor(id, entry.path)
                                    )

                                    WorkspaceFileType.IMAGE -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                        // 传绝对路径 (而非 content:// URI): Coil 可直接加载,
                                        // 预览弹窗的保存按钮 saveMessageImage 只认 "/" 开头路径, content URI 会报错
                                        previewImageUri = file.absolutePath
                                    }

                                    WorkspaceFileType.OTHER -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file,
                                        )
                                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                            file.extension.lowercase()
                                        ) ?: "*/*"
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, mime)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching {
                                            context.startActivity(Intent.createChooser(intent, null))
                                        }
                                    }
                                }
                            }
                        },
                        onDelete = { deleteTarget = it },
                        onBatchExport = { entries ->
                            if (vm.prepareBatchExport(entries)) directoryExportLauncher.launch(null)
                        },
                        onExport = { entry ->
                            exportTarget = entry
                            exportLauncher.launch(entry.name)
                        },
                        onShare = { entry ->
                            vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        },
                    )
                }
            }
        }
    }

    state.exportResult?.let { result ->
        AlertDialog(
            onDismissRequest = vm::dismissExportResult,
            title = { Text("导出结果") },
            text = { Text(result, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = vm::dismissExportResult) { Text(stringResource(R.string.common_confirm)) }
            },
        )
    }

    settingsError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissSettingsError,
            title = { Text(stringResource(R.string.workspace_detail_settings_save_failed)) },
            text = { Text(message.ifBlank { stringResource(R.string.workspace_detail_settings_save_failed) }) },
            confirmButton = {
                TextButton(onClick = vm::dismissSettingsError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }
}

/**
 * 基本栏：只剩「目录」与「Linux 工具链」两个可配项。
 *
 * 启 shell、rootfs 安装、工具审批都搬走了：命令默认就能用，审批在「设置 → 工具」里。
 */
@Composable
private fun WorkspaceBasicPage(
    state: WorkspaceDetailState,
    onDirectoryChange: (String) -> Unit,
    onToolchainChange: (Boolean) -> Unit,
    onOpenTerminalHome: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val workspace = state.workspace
    var directoryDraft by remember(workspace?.id, workspace?.directory) {
        mutableStateOf(workspace?.directory.orEmpty())
    }
    val trimmedDirectory = directoryDraft.trim()
    val directoryChanged = trimmedDirectory != workspace?.directory.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_workspace_info)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_name)) },
                    supportingContent = {
                        Text(workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Tag01) },
                )
                formItem(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_directory)) },
                    supportingContent = { Text(stringResource(R.string.workspace_detail_directory_desc)) },
                    leadingContent = { CardGroupIcon(HugeIcons.FolderCode) },
                ) {
                    val directoryError = trimmedDirectory.isNotEmpty() && !trimmedDirectory.startsWith("/")
                    OutlinedTextField(
                        value = directoryDraft,
                        onValueChange = { directoryDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.workspace_detail_directory_placeholder)) },
                        isError = directoryError,
                        // supportingText 只在出错时出现：这块区域点击会把焦点交给输入框，
                        // 把默认路径放在这里会导致「点一下路径就进了编辑态」
                        supportingText = if (directoryError) {
                            { Text(stringResource(R.string.workspace_detail_directory_invalid)) }
                        } else {
                            null
                        },
                    )
                    // 当前生效目录单独成行：可长按选中复制，点它不会聚焦上面的输入框
                    DirectoryPathRow(state.workingDir?.absolutePath.orEmpty())
                    Button(
                        onClick = { onDirectoryChange(trimmedDirectory) },
                        enabled = directoryChanged,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        }

        item {
            val termexInstalled = state.termex?.installed == true
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_command_environment)) },
            ) {
                switchItem(
                    checked = workspace?.toolchainEnabled == true,
                    onCheckedChange = onToolchainChange,
                    enabled = workspace != null && termexInstalled,
                    supportingContent = {
                        Text(
                            if (termexInstalled) {
                                stringResource(R.string.workspace_detail_toolchain_desc)
                            } else {
                                stringResource(R.string.workspace_detail_toolchain_missing)
                            }
                        )
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Bash) },
                    headlineContent = { Text(stringResource(R.string.workspace_detail_toolchain)) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_command_environment)) },
                    supportingContent = {
                        Text(
                            if (workspace?.toolchainEnabled == true) {
                                stringResource(R.string.workspace_detail_command_environment_toolchain)
                            } else {
                                stringResource(R.string.workspace_detail_command_environment_native)
                            }
                        )
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.ComputerTerminal01) },
                )
                item(
                    onClick = onOpenTerminal,
                    headlineContent = { Text(stringResource(R.string.workspace_terminal)) },
                    leadingContent = { CardGroupIcon(HugeIcons.ComputerTerminal01) },
                )
                item(
                    onClick = onOpenTerminalHome,
                    headlineContent = { Text(stringResource(R.string.workspace_detail_terminal_home)) },
                    leadingContent = { CardGroupIcon(HugeIcons.FolderOpen) },
                    trailingContent = { CardGroupIcon(HugeIcons.ArrowRight01) },
                )
            }
        }
    }
}

/** 当前生效目录：只读展示，可长按选中复制 */
@Composable
private fun DirectoryPathRow(path: String) {
    if (path.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.workspace_detail_directory_current),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    isActive: Boolean,
    onGoUp: () -> Unit,
    onResolveImage: suspend (WorkspaceFileEntry) -> File?,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onBatchExport: (List<WorkspaceFileEntry>) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    var selecting by remember(state.path) { mutableStateOf(false) }
    var selectedPaths by remember(state.path) { mutableStateOf(emptySet<String>()) }
    val files = state.entries.filterNot { it.isDirectory }
    val selectedFiles = files.filter { it.path in selectedPaths }
    val allSelected = files.isNotEmpty() && selectedFiles.size == files.size
    fun toggleSelection(entry: WorkspaceFileEntry) {
        selectedPaths = if (entry.path in selectedPaths) selectedPaths - entry.path else selectedPaths + entry.path
    }
    fun exitSelection() {
        selecting = false
        selectedPaths = emptySet()
    }
    BackHandler(enabled = selecting && isActive) {
        exitSelection()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                // 多选时底部留出悬浮工具栏的位置
                bottom = if (selecting) 128.dp else 16.dp,
            ),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WorkspacePathBar(
                        path = state.path,
                        canGoUp = state.path.isNotBlank(),
                        onGoUp = onGoUp,
                    )
                    if (state.exporting) {
                        ExportProgressCard(
                            completed = state.exportCompleted,
                            total = state.exportTotal,
                        )
                    }
                    state.error?.let { ErrorCard(it) }
                }
            }

            if (!state.loading && state.entries.isEmpty() && state.error == null) {
                item {
                    ExtensionEmptyState(
                        message = stringResource(R.string.workspace_detail_empty_directory),
                        icon = HugeIcons.FolderOpen,
                    )
                }
            }

            // 文件列表排成一个分组：首尾大圆角，项与项之间留 2dp 的缝
            itemsIndexed(state.entries, key = { _, entry -> entry.path }) { index, entry ->
                WorkspaceFileItem(
                    entry = entry,
                    shape = cardGroupItemShape(index, state.entries.size),
                    modifier = Modifier.padding(bottom = if (index == state.entries.lastIndex) 0.dp else 2.dp),
                    onResolveImage = { onResolveImage(entry) },
                    selecting = selecting,
                    selected = entry.path in selectedPaths,
                    onLongClick = {
                        selecting = true
                        selectedPaths = selectedPaths + entry.path
                    },
                    onOpen = {
                        if (selecting && !entry.isDirectory) toggleSelection(entry) else onOpen(entry)
                    },
                    onDelete = { onDelete(entry) },
                    onExport = { onExport(entry) },
                    onShare = { onShare(entry) },
                )
            }
        }

        AnimatedVisibility(
            visible = selecting,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = CustomColors.floatingToolbarColors,
                leadingContent = {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(HugeIcons.Cancel01, contentDescription = "取消多选")
                    }
                },
                trailingContent = {
                    IconButton(
                        onClick = {
                            selectedPaths = if (allSelected) emptySet() else files.map { it.path }.toSet()
                        },
                    ) {
                        Icon(HugeIcons.CheckList, contentDescription = if (allSelected) "取消全选" else "全选")
                    }
                },
            ) {
                Button(
                    onClick = { onBatchExport(selectedFiles) },
                    enabled = selectedFiles.isNotEmpty() && !state.exporting,
                ) {
                    Icon(
                        imageVector = HugeIcons.FileExport,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("导出 (${selectedFiles.size})")
                }
            }
        }
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(
                enabled = canGoUp,
                onClick = onGoUp,
            ) {
                Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
            }
            Text(
                text = path.ifBlank { "/" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // 路径太长时省略开头，当前所在的目录始终看得到
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

@Composable
private fun ExportProgressCard(
    completed: Int,
    total: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "正在导出 $completed/$total",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearWavyProgressIndicator(
                progress = { if (total > 0) completed.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WorkspaceFileItem(
    entry: WorkspaceFileEntry,
    shape: Shape,
    onResolveImage: suspend () -> File?,
    selecting: Boolean,
    selected: Boolean,
    onLongClick: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isImage = !entry.isDirectory && entry.detectFileType() == WorkspaceFileType.IMAGE
    val imageFile by produceState<File?>(
        initialValue = null,
        key1 = if (isImage) entry.path else null,
        key2 = if (isImage) "${entry.updatedAt}:${entry.sizeBytes}" else null,
    ) {
        if (isImage) {
            value = try {
                onResolveImage()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            CustomColors.listItemColors.containerColor
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = if (entry.isDirectory) null else onLongClick,
                onLongClickLabel = if (entry.isDirectory) null else "选择文件",
            ),
        shape = shape,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isImage -> {
                    val context = LocalContext.current
                    val imageRequest = remember(imageFile, entry.updatedAt, entry.sizeBytes) {
                        imageFile?.let {
                            ImageRequest.Builder(context)
                                .data(it)
                                .memoryCacheKey("workspace:${it.absolutePath}:${entry.updatedAt}:${entry.sizeBytes}")
                                .build()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = HugeIcons.File02,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        imageRequest?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }

                entry.isDirectory -> CardGroupIcon(
                    icon = HugeIcons.Folder01,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                // 选中时整行换成 secondaryContainer，图标底色跟着换一档，不然会融进背景
                else -> CardGroupIcon(
                    icon = HugeIcons.File02,
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.surfaceBright
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) entry.path else "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selecting) {
                // 和菜单按钮占一样大的位置，进出多选时行高不跳；点整行切换，勾选框自己不接收点击
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!entry.isDirectory) {
                        Checkbox(checked = selected, onCheckedChange = null)
                    }
                }
            } else {
                val context = LocalContext.current
                ItemActionMenu(
                    actions = buildList {
                        add(
                            ItemAction(
                                text = stringResource(R.string.workspace_detail_copy_path),
                                icon = HugeIcons.Copy01,
                                onClick = {
                                    context.writeClipboardText(entry.path)
                                    // Android 13 起系统会自己弹复制提示
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                        Toast.makeText(context, R.string.workspace_detail_path_copied, Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        )
                        if (!entry.isDirectory) {
                            add(
                                ItemAction(
                                    text = stringResource(R.string.common_export),
                                    icon = HugeIcons.FileExport,
                                    onClick = onExport,
                                )
                            )
                            add(
                                ItemAction(
                                    text = stringResource(R.string.common_share),
                                    icon = HugeIcons.Share08,
                                    onClick = onShare,
                                )
                            )
                        }
                        add(
                            ItemAction(
                                text = stringResource(R.string.common_delete),
                                icon = HugeIcons.Delete01,
                                destructive = true,
                                onClick = onDelete,
                            )
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Alert02,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
