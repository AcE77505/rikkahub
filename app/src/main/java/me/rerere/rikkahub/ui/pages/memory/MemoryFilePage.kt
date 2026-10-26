package me.rerere.rikkahub.ui.pages.memory

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.MemoryBlock
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.joinMemoryBlocks
import me.rerere.rikkahub.data.model.memoryTitleOf
import me.rerere.rikkahub.data.model.parseMemoryBlocks
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.toLocalDateTime
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.Instant

/** 编辑分区：元数据和正文各是一套编辑界面，但共用同一个页面 */
private enum class MemoryEditSection {
    NONE,
    METADATA,
    BODY,
}

/**
 * 记忆文件的详情页。
 *
 * 查看时分成两张可点击的卡片：信息卡（最后更新/摘要/别名）和正文卡。
 * 点信息卡进入路径/标题/摘要/别名编辑，点正文卡进入全屏正文编辑。
 *
 * @param memoryId 记忆库的 id（助手 id 或全局记忆库）
 * @param path 文件路径，为 null 时新建
 */
@Composable
fun MemoryFilePage(memoryId: String, path: String?) {
    val vm: MemoryFileVM = koinViewModel(
        parameters = {
            parametersOf(memoryId, path)
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val isNew = path == null
    val file = (state as? MemoryFileState.Loaded)?.file
    // 新文件没有内容可看，直接进元数据编辑
    var editSection by rememberSaveable {
        mutableStateOf(if (isNew) MemoryEditSection.METADATA else MemoryEditSection.NONE)
    }
    var draftPath by rememberSaveable { mutableStateOf("") }
    var draftTitle by rememberSaveable { mutableStateOf("") }
    var draftDescription by rememberSaveable { mutableStateOf("") }
    var draftAliases by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // 别名输入框里还没加进列表的那个
    var draftAliasInput by rememberSaveable { mutableStateOf("") }
    var draftBody by rememberSaveable { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val pathConflictMessage = stringResource(R.string.memory_file_page_path_conflict)

    fun startEditing(section: MemoryEditSection) {
        draftPath = file?.path ?: ""
        draftTitle = file?.title.orEmpty()
        draftDescription = file?.description.orEmpty()
        draftAliases = file?.aliases.orEmpty()
        draftAliasInput = ""
        draftBody = file?.editableBody.orEmpty()
        editSection = section
    }

    val normalizedDraftPath = MemoryFile.normalizePath(draftPath)
    val draftPathValid = normalizedDraftPath != null
    // 新建、或路径刚被改过时，frontmatter 里的 name 要跟随新路径
    val base = remember(file, normalizedDraftPath) {
        val source = file ?: MemoryFile(path = normalizedDraftPath.orEmpty(), content = "", updatedAt = 0)
        normalizedDraftPath?.let { source.copy(path = it) } ?: source
    }
    // 输入框里打了字没点添加就保存的，也算一个别名，不然就悄悄丢了
    val draftContent = remember(
        base,
        file,
        editSection,
        draftTitle,
        draftDescription,
        draftAliases,
        draftAliasInput,
        draftBody,
    ) {
        when {
            // 只看预览时不需要拼接文件内容，省掉 YAML 重新序列化和字节复制
            editSection == MemoryEditSection.NONE -> file?.content.orEmpty()
            editSection == MemoryEditSection.BODY && file != null -> {
                // 正文编辑只替换 body，保留原始 frontmatter，避免重新序列化后变大
                file.contentWithBody(draftBody)
            }

            else -> base.contentWith(
                draftTitle,
                draftDescription,
                draftAliases.withAlias(draftAliasInput),
                draftBody,
            )
        }
    }
    // 只在编辑时需要比较是否改过；查看模式不算这份内容
    val unchangedContent = remember(file, editSection) {
        if (editSection == MemoryEditSection.NONE) {
            null
        } else {
            file?.run { contentWith(title, description, aliases, editableBody) }
        }
    }
    // 和仓库层一样只拦变大的超限：迁移来的旧记忆可能本来就超限，得允许分几次删小
    val currentSize = remember(file) { file?.content?.toByteArray()?.size ?: 0 }
    val draftSize = if (editSection == MemoryEditSection.NONE) {
        currentSize
    } else {
        draftContent.toByteArray().size
    }
    val draftContentValid = draftSize <= MemoryFile.MAX_BYTES || draftSize <= currentSize
    val pathChanged = normalizedDraftPath != null && normalizedDraftPath != file?.path
    val canSave = editSection != MemoryEditSection.NONE && draftPathValid && draftContentValid &&
        (isNew || draftContent != unchangedContent || pathChanged)

    fun onSaved(result: MemoryWriteResult, savedPath: String?) {
        when (result) {
            is MemoryWriteResult.Success -> {
                if (savedPath != null && savedPath != path) {
                    // 新建或改了路径：换成新路径的详情页，返回时不会回到旧页面
                    navController.navigate(Screen.MemoryFile(memoryId, savedPath)) {
                        popUpTo(Screen.MemoryFile(memoryId, path)) { inclusive = true }
                    }
                } else {
                    editSection = MemoryEditSection.NONE
                }
            }

            is MemoryWriteResult.Conflict -> toaster.show(
                message = pathConflictMessage,
                type = ToastType.Error,
            )

            is MemoryWriteResult.Rejected -> toaster.show(message = result.reason, type = ToastType.Error)
        }
    }

    // 编辑已有文件时，返回是放弃修改回到详情，而不是离开页面
    BackHandler(enabled = editSection != MemoryEditSection.NONE && !isNew) {
        editSection = MemoryEditSection.NONE
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = when (editSection) {
                            MemoryEditSection.METADATA -> stringResource(R.string.memory_file_page_edit_metadata)
                            MemoryEditSection.BODY -> stringResource(R.string.memory_file_page_edit_body)
                            MemoryEditSection.NONE -> (file?.title
                                ?: MemoryFile(path = path ?: draftPath, content = "", updatedAt = 0).title)
                                .ifEmpty { stringResource(R.string.memory_file_page_new) }
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                subtitle = {
                    if (path != null) {
                        Text(text = file?.path ?: path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (editSection != MemoryEditSection.NONE && !isNew) {
                        FilledTonalIconButton(
                            onClick = { editSection = MemoryEditSection.NONE },
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = CustomColors.listItemColors.containerColor
                            ),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.cancel),
                            )
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (editSection != MemoryEditSection.NONE) {
                        TextButton(
                            enabled = canSave,
                            onClick = {
                                val savedPath = normalizedDraftPath ?: return@TextButton
                                if (isNew) {
                                    vm.create(savedPath, draftContent) { result ->
                                        onSaved(result, savedPath)
                                    }
                                } else {
                                    vm.save(savedPath, draftContent, ::onSaved)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.assistant_page_save))
                        }
                    } else if (file != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.delete),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            editSection == MemoryEditSection.METADATA -> MemoryMetadataEditor(
                innerPadding = innerPadding,
                path = draftPath,
                onPathChange = { draftPath = it },
                pathValid = draftPathValid,
                title = draftTitle,
                onTitleChange = { draftTitle = it },
                titlePlaceholder = memoryTitleOf(base.name),
                description = draftDescription,
                onDescriptionChange = { draftDescription = it },
                aliases = draftAliases,
                onAliasesChange = { draftAliases = it },
                aliasInput = draftAliasInput,
                onAliasInputChange = { draftAliasInput = it },
            )

            editSection == MemoryEditSection.BODY -> MemoryBodyEditor(
                innerPadding = innerPadding,
                body = draftBody,
                onBodyChange = { draftBody = it },
                onSaveBody = { newBody ->
                    draftBody = newBody
                    val content = file?.contentWithBody(newBody)
                        ?: base.contentWith(
                            draftTitle,
                            draftDescription,
                            draftAliases.withAlias(draftAliasInput),
                            newBody,
                        )
                    // 条目编辑/删除/排序后立即写回，不需要再点保存
                    vm.save(null, content) { result, _ ->
                        when (result) {
                            is MemoryWriteResult.Success -> Unit
                            is MemoryWriteResult.Conflict -> toaster.show(
                                message = pathConflictMessage,
                                type = ToastType.Error,
                            )

                            is MemoryWriteResult.Rejected -> toaster.show(
                                message = result.reason,
                                type = ToastType.Error,
                            )
                        }
                    }
                },
                contentValid = draftContentValid,
            )

            file != null -> MemoryFileDetail(
                innerPadding = innerPadding,
                file = file,
                onEditMetadata = { startEditing(MemoryEditSection.METADATA) },
                onEditBody = { startEditing(MemoryEditSection.BODY) },
            )

            state is MemoryFileState.Missing -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.memory_file_page_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            vm.delete { navController.popBackStack() }
        },
        onDismiss = { showDeleteDialog = false },
        text = {
            Text(text = file?.path.orEmpty())
        }
    )
}

@Composable
private fun MemoryFileDetail(
    innerPadding: PaddingValues,
    file: MemoryFile,
    onEditMetadata: () -> Unit,
    onEditBody: () -> Unit,
) {
    // 条目多时不要一次性解析/渲染整段 Markdown：按块懒加载，只渲染可见部分。
    val blocks = remember(file) { parseMemoryBlocks(file.displayBody) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        item(key = "metadata") {
            CardGroup {
                item(
                    onClick = onEditMetadata,
                    overlineContent = { Text(stringResource(R.string.memory_file_page_last_updated)) },
                    headlineContent = { Text(Instant.ofEpochMilli(file.updatedAt).toLocalDateTime()) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (file.description.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.memory_file_page_summary),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(text = file.description)
                            }
                            if (file.aliases.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.memory_file_page_aliases),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(text = file.aliases.joinToString(", "))
                            }
                        }
                    },
                )
            }
        }

        if (blocks.isNotEmpty()) {
            item(key = "body-title") {
                Text(
                    text = stringResource(R.string.memory_file_page_details),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            // 每个条目单独懒加载；点击任意块进入正文编辑
            items(blocks, key = { it.id }) { block ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEditBody)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    when (block) {
                        is MemoryBlock.Entry -> Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = block.prefix.trim().ifEmpty { "-" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        is MemoryBlock.Raw -> block.lines.forEach { line ->
                            if (line.isBlank()) {
                                Spacer(Modifier.height(6.dp))
                            } else {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 元数据编辑：路径、标题、摘要、别名。
 */
@Composable
private fun MemoryMetadataEditor(
    innerPadding: PaddingValues,
    path: String,
    onPathChange: (String) -> Unit,
    pathValid: Boolean,
    title: String,
    onTitleChange: (String) -> Unit,
    titlePlaceholder: String,
    description: String,
    onDescriptionChange: (String) -> Unit,
    aliases: List<String>,
    onAliasesChange: (List<String>) -> Unit,
    aliasInput: String,
    onAliasInputChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.memory_file_page_edit_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_path)) },
            ) {
                OutlinedTextField(
                    value = path,
                    onValueChange = onPathChange,
                    placeholder = { Text("/topics/food.md") },
                    isError = path.isNotEmpty() && !pathValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_title)) },
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    placeholder = { Text(titlePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_summary)) },
            ) {
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_aliases)) },
            ) {
                val addAlias = {
                    onAliasesChange(aliases.withAlias(aliasInput))
                    onAliasInputChange("")
                }
                if (aliases.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        aliases.forEach { alias ->
                            InputChip(
                                selected = false,
                                onClick = { onAliasesChange(aliases - alias) },
                                label = { Text(text = alias, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = HugeIcons.Cancel01,
                                        contentDescription = stringResource(R.string.delete),
                                        modifier = Modifier.size(InputChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = onAliasInputChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addAlias() }),
                    trailingIcon = {
                        IconButton(onClick = addAlias, enabled = aliasInput.isNotBlank()) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.add),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 正文编辑：列表项可以单独编辑、删除和拖动排序；标题、段落作为只读块保留。
 * 右上角可切到原始文本模式，用原生 EditText 处理复杂 Markdown。
 */
@Composable
private fun MemoryBodyEditor(
    innerPadding: PaddingValues,
    body: String,
    onBodyChange: (String) -> Unit,
    onSaveBody: (String) -> Unit,
    contentValid: Boolean,
) {
    var blocks by remember { mutableStateOf(parseMemoryBlocks(body)) }
    var rawMode by rememberSaveable { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var editingEntry by remember { mutableStateOf<MemoryBlock.Entry?>(null) }
    var creatingEntry by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<MemoryBlock.Entry?>(null) }
    var confirmBulkDelete by remember { mutableStateOf(false) }

    // 外部 body 变化（切换文件、原始文本保存）时重新解析；
    // 自己保存后 body == join(blocks)，不会把列表和 id 重置掉。
    LaunchedEffect(body) {
        if (joinMemoryBlocks(blocks) != body) blocks = parseMemoryBlocks(body)
    }

    fun save(next: List<MemoryBlock>) {
        blocks = next
        val text = joinMemoryBlocks(next)
        onBodyChange(text)
        onSaveBody(text)
    }

    if (rawMode) {
        MemoryRawBodyEditor(
            innerPadding = innerPadding,
            body = body,
            onCancel = { rawMode = false },
            onSave = { text ->
                rawMode = false
                blocks = parseMemoryBlocks(text)
                onBodyChange(text)
                onSaveBody(text)
            },
        )
        return
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        blocks = blocks.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .imePadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!contentValid) {
            Text(
                text = stringResource(R.string.memory_file_page_too_large),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Text(
                    text = stringResource(R.string.memory_entry_selected, selected.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    selected = blocks.filterIsInstance<MemoryBlock.Entry>()
                        .mapTo(mutableSetOf()) { it.id }
                }) { Text(stringResource(R.string.memory_entry_select_all)) }
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { confirmBulkDelete = true },
                ) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = {
                    selected = emptySet()
                    selectionMode = false
                }) { Text(stringResource(R.string.cancel)) }
            } else {
                Text(
                    text = stringResource(R.string.memory_entry_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { rawMode = true }) {
                    Text(stringResource(R.string.memory_entry_raw))
                }
                IconButton(onClick = {
                    editText = ""
                    creatingEntry = true
                }) {
                    Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.add))
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(blocks, key = { _, block -> block.id }) { _, block ->
                    ReorderableItem(state = reorderState, key = block.id) {
                        when (block) {
                            is MemoryBlock.Entry -> MemoryEntryRow(
                                entry = block,
                                selectionMode = selectionMode,
                                selected = block.id in selected,
                                onToggleSelected = {
                                    selected = if (block.id in selected) {
                                        selected - block.id
                                    } else {
                                        selected + block.id
                                    }
                                },
                                onEdit = {
                                    if (selectionMode) {
                                        selected = if (block.id in selected) {
                                            selected - block.id
                                        } else {
                                            selected + block.id
                                        }
                                    } else {
                                        editText = block.text
                                        editingEntry = block
                                    }
                                },
                                onLongClick = {
                                    selectionMode = true
                                    selected = selected + block.id
                                },
                                onDelete = { pendingDelete = block },
                                dragHandle = {
                                    Box(
                                        modifier = Modifier
                                            .longPressDraggableHandle(
                                                onDragStarted = {
                                                    haptic.performHapticFeedback(
                                                        HapticFeedbackType.GestureThresholdActivate
                                                    )
                                                },
                                                onDragStopped = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                    val text = joinMemoryBlocks(blocks)
                                                    onBodyChange(text)
                                                    onSaveBody(text)
                                                },
                                            )
                                            .size(40.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.DragDropHorizontal,
                                            contentDescription = stringResource(R.string.memory_entry_drag),
                                        )
                                    }
                                },
                            )

                            is MemoryBlock.Raw -> MemoryRawRow(block)
                        }
                    }
                }
            }
        }
    }

    if (editingEntry != null || creatingEntry) {
        val entry = editingEntry
        ModalBottomSheet(
            onDismissRequest = {
                editingEntry = null
                creatingEntry = false
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        if (entry != null) R.string.memory_entry_edit else R.string.memory_entry_new,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 360.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        editingEntry = null
                        creatingEntry = false
                    }) { Text(stringResource(R.string.common_cancel)) }
                    Button(onClick = {
                        val text = editText.trimEnd()
                        if (entry != null) {
                            save(blocks.map { if (it.id == entry.id) entry.copy(text = text) else it })
                        } else {
                            save(blocks + MemoryBlock.Entry(prefix = "- ", text = text, stated = true))
                        }
                        editingEntry = null
                        creatingEntry = false
                    }) { Text(stringResource(R.string.assistant_page_save)) }
                }
            }
        }
    }

    // 删除确认：单条删除和多选删除共用同一套逻辑
    val deleteIds = pendingDelete?.let { setOf(it.id) }
        ?: if (confirmBulkDelete) selected else emptySet()
    if (deleteIds.isNotEmpty()) {
        RikkaConfirmDialog(
            show = true,
            title = if (pendingDelete != null) {
                stringResource(R.string.memory_entry_delete_title)
            } else {
                stringResource(R.string.memory_entry_delete_selected_title, selected.size)
            },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                save(blocks.filterNot { it.id in deleteIds })
                pendingDelete = null
                confirmBulkDelete = false
                selected = emptySet()
                selectionMode = false
            },
            onDismiss = {
                pendingDelete = null
                confirmBulkDelete = false
            },
        ) {
            Text(stringResource(R.string.memory_entry_delete_message))
        }
    }
}

@Composable
private fun MemoryEntryRow(
    entry: MemoryBlock.Entry,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onEdit: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 可点击区域覆盖 checkbox + 文本 + 上下内边距，相邻条目之间不再有不可点的缝
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelected() else onEdit() },
                    onLongClick = onLongClick,
                )
                .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
            }
            Text(
                text = entry.text.ifBlank { " " },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selectionMode) {
            dragHandle()
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun MemoryRawRow(block: MemoryBlock.Raw) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        block.lines.forEach { line ->
            if (line.isBlank()) {
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 原始文本模式：保留原生 EditText，处理条目列表覆盖不到的 Markdown */
@Composable
private fun MemoryRawBodyEditor(
    innerPadding: PaddingValues,
    body: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(body) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.memory_entry_raw),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.assistant_page_save))
            }
        }
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val syncedBody = remember { mutableStateOf(body) }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            AndroidView(
                factory = { ctx ->
                    EditText(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        typeface = Typeface.MONOSPACE
                        setTextColor(textColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        val pad = (12 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        gravity = Gravity.TOP or Gravity.START
                        includeFontPadding = false
                        setText(body)
                        setSelection(text.length)
                        doAfterTextChanged { editable ->
                            val value = editable?.toString().orEmpty()
                            syncedBody.value = value
                            text = value
                        }
                    }
                },
                update = { editText ->
                    editText.setTextColor(textColor)
                    if (syncedBody.value != text) {
                        syncedBody.value = text
                        editText.setText(text)
                        editText.setSelection(editText.text.length)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// 把输入框里的 [input] 加进别名；空的或者已经有了就不加
private fun List<String>.withAlias(input: String): List<String> {
    val alias = input.trim()
    return if (alias.isEmpty() || alias in this) this else this + alias
}
