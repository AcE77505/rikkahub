package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.BackupItem
import me.rerere.rikkahub.data.datastore.RestoreMode
import me.rerere.rikkahub.data.datastore.BackupSelection
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.DeepSeekRestoreResult
import me.rerere.rikkahub.ui.pages.backup.components.BackupActionIcon
import me.rerere.rikkahub.ui.pages.backup.components.BackupAssistantPickerDialog
import me.rerere.rikkahub.ui.activity.DeepSeekImportPreviewActivity
import me.rerere.rikkahub.ui.activity.DeepSeekImportSession
import me.rerere.rikkahub.ui.pages.backup.components.DeepSeekImportResultDialog
import me.rerere.rikkahub.ui.pages.backup.components.DeepSeekKeptConversationsDialog
import me.rerere.rikkahub.ui.pages.backup.components.backupContentItem
import me.rerere.rikkahub.ui.pages.backup.components.backupItemLabels
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val itemLabels = backupItemLabels(BackupItem.entries)
    val restoreModeLabels = restoreModeLabels()
    val restoreModeTitle = stringResource(R.string.backup_page_restore_mode)
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selection by vm.localBackupSelection.collectAsStateWithLifecycle()
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    // 勾选了含密钥的项时，导出/导入前先提醒一次
    var showSecretsPrompt by remember { mutableStateOf(false) }
    var secretsPromptIsExport by remember { mutableStateOf(true) }
    // DeepSeek 导入结束后展示新增/重复统计，包含重复对话名列表
    var deepSeekImportResult by remember { mutableStateOf<DeepSeekRestoreResult?>(null) }
    var showDeepSeekKept by remember { mutableStateOf(false) }
    // DeepSeek 导入前选择归属助手
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showDeepSeekAssistantPicker by remember { mutableStateOf(false) }
    var pendingDeepSeekAssistantId by remember { mutableStateOf<Uuid?>(null) }

    // 导入类型：local 为本地备份，chatbox 为 Chatbox 导入，cherry 为 Cherry Studio 导入
    var importType by remember { mutableStateOf("local") }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    // 导出文件
                    val exportFile = vm.exportToFile()

                    // 复制到用户选择的位置
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(exportFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    // 清理临时文件
                    exportFile.delete()

                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_backup_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isExporting = false
            }
        }
    }

    // 有变化的对话在独立预览页里决定，回到这里再执行导入
    val deepSeekPreviewLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val session = DeepSeekImportSession
        val preview = session.preview
        val file = session.file
        val assistantId = session.assistantId
        val decisions = session.decisions
        session.clear()

        if (activityResult.resultCode == Activity.RESULT_OK && preview != null && file != null && assistantId != null) {
            scope.launch {
                isRestoring = true
                runCatching {
                    deepSeekImportResult = vm.restoreFromDeepSeek(
                        file = file,
                        assistantId = assistantId,
                        preview = preview,
                        decisions = decisions ?: emptyMap(),
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                file.delete()
                isRestoring = false
            }
        } else {
            file?.delete()
        }
    }

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                var importedDeepSeek: DeepSeekRestoreResult? = null
                // 有变化的对话要等用户在预览弹窗里决定，此时不能走“导入成功 → 重启”这条路径
                var deferredDeepSeek = false
                runCatching {
                    when (importType) {
                        "local" -> {
                            // 本地备份导入：处理zip文件
                            val tempFile =
                                File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // 从临时文件恢复
                            vm.restoreFromLocalFile(tempFile)

                            // 清理临时文件
                            tempFile.delete()
                        }

                        "chatbox" -> {
                            // Chatbox Backup v2：处理 ZIP 文件
                            val tempFile =
                                File(context.cacheDir, "temp_chatbox_${System.currentTimeMillis()}.zip")

                            try {
                                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                    FileOutputStream(tempFile).use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }

                                vm.restoreFromChatBox(tempFile)
                            } finally {
                                tempFile.delete()
                            }
                        }

                        "cherry" -> {
                            // Cherry Studio导入：处理zip文件
                            val tempFile =
                                File(context.cacheDir, "temp_cherry_${System.currentTimeMillis()}.zip")

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // 从Cherry Studio备份恢复
                            vm.restoreFromCherryStudio(tempFile)

                            // 清理临时文件
                            tempFile.delete()
                        }

                        "deepseek" -> {
                            // DeepSeek 官方数据导出：ZIP 内只有 conversations.json 与 user.json
                            val tempFile =
                                File(context.cacheDir, "temp_deepseek_${System.currentTimeMillis()}.zip")
                            var keepTempFile = false

                            try {
                                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                    FileOutputStream(tempFile).use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }

                                val assistantId = pendingDeepSeekAssistantId ?: vm.settings.value.assistantId
                                val preview = vm.previewDeepSeek(tempFile)
                                if (preview.changed.isEmpty()) {
                                    importedDeepSeek = vm.restoreFromDeepSeek(
                                        file = tempFile,
                                        assistantId = assistantId,
                                        preview = preview,
                                        decisions = emptyMap(),
                                    )
                                } else {
                                    // 有变化的对话交给独立预览页决定；临时文件留到结果回来再删
                                    DeepSeekImportSession.file = tempFile
                                    DeepSeekImportSession.preview = preview
                                    DeepSeekImportSession.assistantId = assistantId
                                    DeepSeekImportSession.decisions = null
                                    keepTempFile = true
                                    deferredDeepSeek = true
                                    deepSeekPreviewLauncher.launch(DeepSeekImportPreviewActivity.intent(context))
                                }
                            } finally {
                                if (!keepTempFile) tempFile.delete()
                            }
                        }
                    }

                    val deepSeekResult = importedDeepSeek
                    if (deferredDeepSeek) {
                        // 等待用户在预览弹窗里选择，提示与重启留到导入真正完成后
                    } else if (deepSeekResult == null) {
                        toaster.show(
                            context.getString(R.string.backup_page_restore_success),
                            type = ToastType.Success
                        )
                        onShowRestartDialog()
                    } else {
                        // 用明细对话框代替 toast：重复导入时用户需要知道跳过了哪些对话
                        deepSeekImportResult = deepSeekResult
                    }
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isRestoring = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardGroup(
            title = { Text(stringResource(R.string.backup_page_local_backup_export)) },
        ) {
            backupContentItem(
                selected = selection.items,
                onChange = { vm.updateLocalBackupItems(it) },
                label = { itemLabels.getValue(it) },
            )
            // 恢复方式只影响导入；对话和子代理默认合并不替换
            RestoreModeItems(
                title = restoreModeTitle,
                selection = selection,
                itemLabels = itemLabels,
                modeLabels = restoreModeLabels,
                onSelect = { item, mode -> vm.updateLocalRestoreMode(item, mode) },
            )
            item(
                onClick = if (!isExporting) {
                    {
                        if (selection.effectiveItems.any { it.containsSecrets }) {
                            secretsPromptIsExport = true
                            showSecretsPrompt = true
                        } else {
                            createDocumentLauncher.launch(backupFileName())
                        }
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                supportingContent = {
                    Text(
                        if (isExporting) {
                            stringResource(R.string.backup_page_exporting)
                        } else {
                            stringResource(R.string.backup_page_export_desc)
                        }
                    )
                },
                leadingContent = { BackupActionIcon(HugeIcons.FileExport, loading = isExporting) },
            )

            item(
                onClick = if (!isRestoring) {
                    {
                        if (selection.effectiveItems.any { it.containsSecrets }) {
                            secretsPromptIsExport = false
                            showSecretsPrompt = true
                        } else {
                            showImportConfirmDialog = true
                        }
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                supportingContent = {
                    Text(
                        if (isRestoring && importType == "local") {
                            stringResource(R.string.backup_page_importing)
                        } else {
                            stringResource(R.string.backup_page_import_desc)
                        }
                    )
                },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "local")
                },
            )
        }

        CardGroup(
            title = { Text(stringResource(R.string.backup_page_import_from_other_app)) },
        ) {
            item(
                onClick = if (!isRestoring) {
                    {
                        pendingDeepSeekAssistantId = null
                        showDeepSeekAssistantPicker = true
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_import_from_deepseek)) },
                supportingContent = { Text(stringResource(R.string.backup_page_import_deepseek_desc)) },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "deepseek")
                },
            )

            item(
                onClick = if (!isRestoring) {
                    {
                        importType = "chatbox"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "chatbox")
                },
            )

            item(
                onClick = if (!isRestoring) {
                    {
                        importType = "cherry"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && importType == "cherry")
                },
            )

        }
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = { Text(stringResource(R.string.backup_page_import_overwrite_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        importType = "local"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showSecretsPrompt) {
        AlertDialog(
            onDismissRequest = { showSecretsPrompt = false },
            title = { Text(stringResource(R.string.backup_page_secrets_title)) },
            text = { Text(stringResource(R.string.backup_page_secrets_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSecretsPrompt = false
                        if (secretsPromptIsExport) {
                            createDocumentLauncher.launch(backupFileName())
                        } else {
                            showImportConfirmDialog = true
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSecretsPrompt = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDeepSeekAssistantPicker) {
        BackupAssistantPickerDialog(
            assistants = settings.assistants,
            currentAssistantId = settings.assistantId,
            onConfirm = { assistant ->
                pendingDeepSeekAssistantId = assistant.id
                showDeepSeekAssistantPicker = false
                importType = "deepseek"
                // 部分设备/文件管理器会把 .zip 报成通用压缩类型
                openDocumentLauncher.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream",
                    )
                )
            },
            onDismiss = { showDeepSeekAssistantPicker = false },
        )
    }

    deepSeekImportResult?.let { result ->
        if (showDeepSeekKept) {
            DeepSeekKeptConversationsDialog(
                titles = result.keptTitles,
                onDismiss = { showDeepSeekKept = false },
            )
        } else {
            DeepSeekImportResultDialog(
                result = result,
                onShowKept = { showDeepSeekKept = true },
                onDismiss = {
                    deepSeekImportResult = null
                    // 只有真正写入了新会话才需要刷新会话列表
                    if (result.importedConversations > 0) onShowRestartDialog()
                },
            )
        }
    }
}

private fun backupFileName(): String =
    "rikkahub_backup_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip"

private val RestoreModeOptions = listOf(RestoreMode.MERGE, RestoreMode.OVERWRITE)

/** 恢复方式的名称，在 Composable 上下文里解析好再交给列表使用。 */
@Composable
private fun restoreModeLabels(): Map<RestoreMode, String> = RestoreModeOptions.associateWith {
    stringResource(
        when (it) {
            RestoreMode.MERGE -> R.string.backup_page_restore_mode_merge
            RestoreMode.OVERWRITE -> R.string.backup_page_restore_mode_overwrite
        }
    )
}

/** CardGroup 的 content 不是 @Composable 上下文，标题和各项名称都由调用方解析好传进来。 */
private fun CardGroupScope.RestoreModeItems(
    title: String,
    selection: BackupSelection,
    itemLabels: Map<BackupItem, String>,
    modeLabels: Map<RestoreMode, String>,
    onSelect: (BackupItem, RestoreMode) -> Unit,
) {
    val items = selection.effectiveItems.sortedBy { it.ordinal }
    if (items.isEmpty()) return
    formItem(headlineContent = { Text(title) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in items) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = itemLabels.getValue(item),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 用 FilterChip：ConnectedChoiceButtons 内部 fillMaxWidth，会把左侧名称挤没
                    for (mode in RestoreModeOptions) {
                        FilterChip(
                            selected = selection.modeOf(item) == mode,
                            onClick = { onSelect(item, mode) },
                            label = { Text(modeLabels.getValue(mode)) },
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
            }
        }
    }
}
