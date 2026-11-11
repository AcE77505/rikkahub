package me.rerere.rikkahub.ui.components.path

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.PathOpenPreference
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.LocalWorkspaceId
import me.rerere.rikkahub.utils.installedMtManagers
import me.rerere.rikkahub.utils.mtManagerLabel
import me.rerere.rikkahub.utils.openFileWith
import me.rerere.rikkahub.utils.openFileWithSystem
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import java.io.File

/** 一次打开路径时用户可选的入口。 */
private sealed interface PathOpenChoice {
    data class Mt(val packageName: String) : PathOpenChoice

    data object System : PathOpenChoice
}

private data class PendingChoice(
    val file: File,
    val choices: List<PathOpenChoice>,
    val isDirectory: Boolean,
)

/**
 * 返回一个「打开路径」回调，并负责在需要时渲染选择框。
 *
 * - 设置里选了具体 MT / 系统：直接打开，失败时回退另一个
 * - 设置里选「每次询问」：每次点击都弹框，可勾选「下次不再提示」
 *
 * 不检查路径是否存在：交给对方应用判断。
 *
 * @param enabled 为 false（对话生成中）时不响应点击
 */
@Composable
fun rememberPathOpenHandler(enabled: Boolean): (String) -> Unit {
    val context = LocalContext.current
    val workspaceId = LocalWorkspaceId.current
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val settingsStore: SettingsStore = koinInject()
    val workspaceRepository: WorkspaceRepository = koinInject()

    val installed = remember { installedMtManagers(context) }
    var pending by remember { mutableStateOf<PendingChoice?>(null) }

    fun failure(file: File) {
        toaster.show(
            context.getString(R.string.path_open_not_found, file.path),
            type = ToastType.Error,
        )
    }

    fun open(file: File, choice: PathOpenChoice, isDirectory: Boolean): Boolean = when (choice) {
        is PathOpenChoice.Mt ->
            openFileWith(context, file, choice.packageName, isDirectory) ||
                openFileWithSystem(context, file, isDirectory)

        PathOpenChoice.System -> openFileWithSystem(context, file, isDirectory)
    }

    val openPath: (String) -> Unit = remember(
        enabled,
        installed,
        workspaceId,
        settings.pathOpenPreference,
        settings.pathOpenMtPackage,
    ) {
        handler@{ path ->
            if (!enabled) return@handler
            scope.launch {
                val file = resolveOpenableFile(path, workspaceId, workspaceRepository)
                // 结尾带斜杠或本身是目录：MT 只有 application/x-directory 能定位目录
                val isDirectory = path.trim().endsWith("/") || file.isDirectory
                val opened = when (settings.pathOpenPreference) {
                    PathOpenPreference.SYSTEM -> openFileWithSystem(context, file, isDirectory)

                    PathOpenPreference.ASK -> {
                        pending = PendingChoice(
                            file = file,
                            choices = installed.map { PathOpenChoice.Mt(it) } + PathOpenChoice.System,
                            isDirectory = isDirectory,
                        )
                        true
                    }

                    PathOpenPreference.MT_MANAGER -> {
                        val packageName = settings.pathOpenMtPackage?.takeIf { it in installed }
                            ?: installed.firstOrNull()
                        if (packageName != null) {
                            openFileWith(context, file, packageName, isDirectory) ||
                                openFileWithSystem(context, file, isDirectory)
                        } else {
                            openFileWithSystem(context, file, isDirectory)
                        }
                    }
                }
                if (!opened) failure(file)
            }
        }
    }

    pending?.let { request ->
        PathOpenChoiceDialog(
            choices = request.choices,
            onDismiss = { pending = null },
            onConfirm = { choice, rememberChoice ->
                pending = null
                if (rememberChoice) {
                    scope.launch {
                        settingsStore.update { current ->
                            when (choice) {
                                is PathOpenChoice.Mt -> current.copy(
                                    pathOpenPreference = PathOpenPreference.MT_MANAGER,
                                    pathOpenMtPackage = choice.packageName,
                                )

                                PathOpenChoice.System -> current.copy(
                                    pathOpenPreference = PathOpenPreference.SYSTEM,
                                )
                            }
                        }
                    }
                }
                if (!open(request.file, choice, request.isDirectory)) failure(request.file)
            },
        )
    }

    return openPath
}

@Composable
private fun PathOpenChoiceDialog(
    choices: List<PathOpenChoice>,
    onDismiss: () -> Unit,
    onConfirm: (choice: PathOpenChoice, rememberChoice: Boolean) -> Unit,
) {
    var selected by remember(choices) { mutableStateOf(choices.first()) }
    var rememberChoice by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.path_open_choose_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { choice ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = choice }
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(
                            selected = selected == choice,
                            onClick = { selected = choice },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = when (choice) {
                                    is PathOpenChoice.Mt -> mtManagerLabel(choice.packageName)
                                    PathOpenChoice.System -> stringResource(R.string.setting_path_open_system)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (choice is PathOpenChoice.Mt) {
                                Text(
                                    text = choice.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rememberChoice = !rememberChoice }
                        .padding(top = 8.dp),
                ) {
                    Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                    Text(
                        text = stringResource(R.string.path_open_choose_remember),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected, rememberChoice) }) {
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

/**
 * 把路径转成要交给目标应用的文件。
 *
 * 不检查路径是否存在：只把工作区相对路径映射到宿主机，其余原样交给 MT / 系统判断。
 */
private suspend fun resolveOpenableFile(
    path: String,
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
): File {
    val trimmed = path.trim()
    val direct = File(trimmed)
    // 只对明显属于工作区的路径做映射，避免把 /storage/... 之类当成工作区相对路径
    val workspaceRelative = trimmed.startsWith("./") || trimmed.startsWith("../") ||
        trimmed.startsWith("~/") || trimmed.startsWith("/workspace/")
    if (workspaceId == null || !workspaceRelative) return direct
    return runCatching { workspaceRepository.resolveFile(workspaceId, trimmed) }.getOrNull() ?: direct
}
