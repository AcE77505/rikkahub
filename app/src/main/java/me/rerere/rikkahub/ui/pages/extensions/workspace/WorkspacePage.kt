package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FolderCode
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasStorageAccess
import me.rerere.rikkahub.utils.openAllFilesAccessSettings
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.WorkspaceManager
import me.rerere.rikkahub.utils.usesAllFilesAccessSettings
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkspacePage(vm: WorkspaceVM = koinViewModel()) {
    val navController = LocalNavController.current
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val declared by vm.declared.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    val cancelledMessage = stringResource(R.string.workspace_page_declared_cancelled, "%s")

    val context = LocalContext.current
    var storageDialogVisible by rememberSaveable { mutableStateOf(false) }

    // Android 10 及以下走运行时读写权限；11+ 只能去系统设置页开「所有文件访问」
    val storagePermission = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
                displayName = { Text(stringResource(R.string.workspace_page_storage_title)) },
                usage = { Text(stringResource(R.string.workspace_page_storage_message)) },
                required = true,
            ),
        ),
    )
    PermissionManager(permissionState = storagePermission)

    // 进入工作区列表时检查一次：没有权限就提示（默认目录其实不需要授权，所以允许跳过）
    LaunchedEffect(Unit) {
        if (!context.hasStorageAccess()) storageDialogVisible = true
    }

    // 从系统设置页回来时重新判断，授权成功就收起提示
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && context.hasStorageAccess()) {
                storageDialogVisible = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 低版本运行时授权成功后同样收起提示
    val storageGranted = storagePermission.allPermissionsGranted
    LaunchedEffect(storageGranted) {
        if (storageGranted) storageDialogVisible = false
    }

    fun onDeclaredChange(workspace: WorkspaceEntity, checked: Boolean) {
        // 声明是互斥的：打开 B 时 A 自动关闭，这里把这件事告诉用户
        val previous = declared
        vm.setDeclared(workspace, checked)
        if (checked && previous != null && previous.id != workspace.id) {
            scope.launch {
                snackbarHostState.showSnackbar(cancelledMessage.format(previous.name))
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.workspace_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (workspaces.isEmpty()) {
                item {
                    ExtensionEmptyState(
                        message = stringResource(R.string.workspace_page_empty),
                        description = stringResource(R.string.workspace_page_empty_desc),
                        icon = HugeIcons.FolderCode,
                    )
                }
            }

            items(workspaces, key = { it.id }) { workspace ->
                WorkspaceItem(
                    workspace = workspace,
                    declared = declared?.id == workspace.id,
                    onDeclaredChange = { onDeclaredChange(workspace, it) },
                    onRename = { editTarget = workspace },
                    onDelete = { deleteTarget = workspace },
                    onOpen = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                )
            }
        }
    }

    if (showAddDialog) {
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_create),
            initialName = "",
            showPathPreview = true,
            existingNames = workspaces.map { it.name.trim() }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                vm.create(name)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { workspace ->
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_rename),
            initialName = workspace.name,
            existingNames = workspaces.filter { it.id != workspace.id }.map { it.name.trim() }.toSet(),
            // 重命名不改目录：把当前目录摆出来，并说明不会移动文件
            existingPath = workspace.directory?.takeIf { it.isNotBlank() }
                ?: context.filesDir.resolve("workspaces").resolve(workspace.root).resolve("files").absolutePath,
            onDismiss = { editTarget = null },
            onConfirm = { name ->
                vm.rename(workspace, name)
                editTarget = null
            },
        )
    }

    if (storageDialogVisible) {
        AlertDialog(
            onDismissRequest = { storageDialogVisible = false },
            title = { Text(stringResource(R.string.workspace_page_storage_title)) },
            text = { Text(stringResource(R.string.workspace_page_storage_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (usesAllFilesAccessSettings) {
                            storageDialogVisible = false
                            context.openAllFilesAccessSettings()
                        } else {
                            storagePermission.requestPermissions()
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (usesAllFilesAccessSettings) {
                                R.string.workspace_page_storage_grant_settings
                            } else {
                                R.string.workspace_page_storage_grant
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { storageDialogVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.workspace_page_delete),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleteTarget?.let { vm.delete(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.workspace_page_delete_confirm))
    }
}

@Composable
private fun WorkspaceItem(
    workspace: WorkspaceEntity,
    declared: Boolean,
    onDeclaredChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardGroupIcon(HugeIcons.FolderCode)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (declared) {
                        stringResource(R.string.workspace_page_declared)
                    } else {
                        stringResource(R.string.workspace_page_not_declared)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 声明开关：同一时刻只有一个工作区被声明，切换由数据层事务保证
            Switch(
                checked = declared,
                onCheckedChange = onDeclaredChange,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.common_rename),
                        icon = HugeIcons.PencilEdit01,
                        onClick = onRename,
                    ),
                    ItemAction(
                        text = stringResource(R.string.common_delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}

/** 重命名时展示当前目录，并说明重命名不会移动文件 */
@Composable
private fun WorkspaceCurrentFolder(path: String) {
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
        Text(
            text = stringResource(R.string.workspace_page_rename_path_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 新建时预览工作区目录的真实路径。
 *
 * 目录名取自工作区名，长按可选中复制，方便用户确认文件会落在哪里。
 */
@Composable
private fun WorkspaceFolderPreview(name: String, rootName: String?) {
    val workspacesDir = LocalContext.current.filesDir.resolve("workspaces")
    // 还没输入名称时只是「目录会建在这里」，不算错误
    val invalid = name.isNotBlank() && rootName == null
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.workspace_page_path_preview),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = when {
                    rootName != null -> File(File(workspacesDir, rootName), "files").absolutePath
                    name.isBlank() -> workspacesDir.absolutePath
                    else -> stringResource(R.string.workspace_page_path_invalid)
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = when {
                    invalid -> MaterialTheme.colorScheme.error
                    rootName == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun EditWorkspaceDialog(
    title: String,
    initialName: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    // 新建：预览即将创建的目录；重命名：展示当前目录（目录不随重命名变化）
    showPathPreview: Boolean = false,
    existingPath: String? = null,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()
    val isDuplicate = trimmedName.isNotEmpty() && trimmedName in existingNames
    val rootName = if (showPathPreview) WorkspaceManager.toRootName(trimmedName) else null
    // 空名称不算「名称非法」：保存按钮已经由 isNotBlank 管住，这里只拦真正无法转成目录名的情况
    val nameUsable = !showPathPreview || trimmedName.isEmpty() || rootName != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_page_name)) },
                    singleLine = true,
                    isError = isDuplicate || !nameUsable,
                    supportingText = if (isDuplicate) {
                        { Text(stringResource(R.string.workspace_page_name_duplicate)) }
                    } else {
                        null
                    },
                )
                if (showPathPreview) {
                    WorkspaceFolderPreview(trimmedName, rootName)
                } else if (existingPath != null) {
                    WorkspaceCurrentFolder(existingPath)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = name.isNotBlank() && !isDuplicate && nameUsable,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
