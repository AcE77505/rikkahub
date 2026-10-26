package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.workspace.SshKeyBackup
import me.rerere.workspace.SshRunner
import org.koin.compose.koinInject

/**
 * SSH 密钥管理页。
 *
 * 所有 SSH 工作区共用 app 私有目录里的一对密钥；这里只提供公钥查看/复制，
 * 以及包含私钥的文本备份导出、导入恢复（导入只接受无口令的 OpenSSH 私钥）。
 */
@Composable
fun SshKeyPage() {
    val context = LocalContext.current
    val sshRunner: SshRunner = koinInject()
    val scope = rememberCoroutineScope()
    var backup by remember { mutableStateOf<SshKeyBackup?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var confirmingImport by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching { withContext(Dispatchers.IO) { sshRunner.exportKeyPair() } }
                .onSuccess { backup = it }
                .onFailure { error = it.message ?: it::class.simpleName.orEmpty() }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val key = backup
        if (uri == null || key == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = buildString {
                        appendLine("# RikkaHub SSH key backup")
                        appendLine("# This file contains a private key. Keep it secret.")
                        appendLine()
                        appendLine(key.privateKey)
                        appendLine(key.publicKey)
                    }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: error("无法写入文件")
                }
            }.onSuccess {
                Toast.makeText(context, R.string.ssh_key_export_success, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(
                    context,
                    it.message ?: context.getString(R.string.ssh_key_export_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取文件")
                }
            }.onSuccess {
                pendingImport = it
                confirmingImport = true
            }.onFailure {
                Toast.makeText(
                    context,
                    it.message ?: context.getString(R.string.ssh_key_import_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.ssh_key_page_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.ssh_key_page_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                backup?.let { key ->
                    CardGroup(title = { Text(stringResource(R.string.ssh_key_public)) }) {
                        formItem {
                            SelectionContainer {
                                Text(
                                    text = key.publicKey,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                            }
                            TextButton(
                                onClick = {
                                    context.writeClipboardText(key.publicKey)
                                    Toast.makeText(
                                        context,
                                        R.string.ssh_key_copied,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) {
                                Text(stringResource(R.string.ssh_key_copy_public))
                            }
                        }
                    }
                }

                CardGroup(title = { Text(stringResource(R.string.ssh_key_backup)) }) {
                    item(
                        onClick = { exportLauncher.launch("rikkahub-ssh-key.txt") },
                        headlineContent = { Text(stringResource(R.string.ssh_key_export)) },
                        supportingContent = { Text(stringResource(R.string.ssh_key_export_desc)) },
                        leadingContent = { CardGroupIcon(HugeIcons.FileExport) },
                    )
                    item(
                        onClick = {
                            importLauncher.launch(
                                arrayOf("text/plain", "application/octet-stream", "*/*")
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.ssh_key_import)) },
                        supportingContent = { Text(stringResource(R.string.ssh_key_import_desc)) },
                        leadingContent = { CardGroupIcon(HugeIcons.FileImport) },
                    )
                }
            }
        }
    }

    if (confirmingImport) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.ssh_key_import_confirm_title),
            confirmText = stringResource(R.string.common_confirm),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                confirmingImport = false
                val text = pendingImport ?: return@RikkaConfirmDialog
                pendingImport = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { sshRunner.importKeyPair(text) } }
                        .onSuccess {
                            Toast.makeText(
                                context,
                                R.string.ssh_key_import_success,
                                Toast.LENGTH_SHORT,
                            ).show()
                            reload()
                        }
                        .onFailure {
                            Toast.makeText(
                                context,
                                it.message ?: context.getString(R.string.ssh_key_import_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                }
            },
            onDismiss = {
                confirmingImport = false
                pendingImport = null
            },
        ) {
            Text(stringResource(R.string.ssh_key_import_confirm_message))
        }
    }
}
