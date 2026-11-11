package me.rerere.rikkahub.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.importer.DeepSeekImportDecision
import me.rerere.rikkahub.ui.pages.backup.DeepSeekChangedConversation
import me.rerere.rikkahub.ui.pages.backup.DeepSeekImportPreview
import me.rerere.rikkahub.ui.pages.backup.DeepSeekPreviewItem
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.writeBooleanPreference
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * 导入预览页与备份页之间的临时数据。预览数据可能包含上千条标题，不适合走 Intent extra。
 */
object DeepSeekImportSession {
    var file: File? = null
    var preview: DeepSeekImportPreview? = null
    var assistantId: Uuid? = null
    var decisions: Map<Uuid, DeepSeekImportDecision>? = null

    fun clear() {
        file = null
        preview = null
        assistantId = null
        decisions = null
    }
}

/**
 * 导入前的独立预览页：按新增 / 有变化 / 无变化分类，有变化的对话必须显式选择处理方式。
 * 用户点「开始导入」后把决定写回 [DeepSeekImportSession] 并以 RESULT_OK 结束。
 */
class DeepSeekImportPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = DeepSeekImportSession.preview
        if (preview == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                DeepSeekImportPreviewScreen(
                    preview = preview,
                    onStart = { decisions ->
                        DeepSeekImportSession.decisions = decisions
                        setResult(RESULT_OK)
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, DeepSeekImportPreviewActivity::class.java)
    }
}

private enum class PreviewTab { NEW, CHANGED, UNCHANGED }

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 只在第一次进入预览页时自动展示一次说明。 */
private const val HELP_SHOWN_PREFERENCE = "deepseek_import_preview_help_shown"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeepSeekImportPreviewScreen(
    preview: DeepSeekImportPreview,
    onStart: (Map<Uuid, DeepSeekImportDecision>) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var tab by remember {
        mutableStateOf(
            when {
                preview.changed.isNotEmpty() -> PreviewTab.CHANGED
                preview.newConversations.isNotEmpty() -> PreviewTab.NEW
                else -> PreviewTab.UNCHANGED
            }
        )
    }
    var decisions by remember {
        mutableStateOf(
            preview.changed.associate { it.conversationId to (null as DeepSeekImportDecision?) }
        )
    }
    var showMissingDecision by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var showHelp by remember {
        mutableStateOf(!context.readBooleanPreference(HELP_SHOWN_PREFERENCE, false))
    }

    // 所有变化项选了同一处理方式时才回显到全局设置；未选或混选都保持未选中
    val unifiedDecision: DeepSeekImportDecision? = decisions.values.distinct().singleOrNull()
    val keyword = query.trim()
    val hasItems = when (tab) {
        PreviewTab.NEW -> preview.newConversations.any { it.matches(keyword) }
        PreviewTab.CHANGED -> preview.changed.any { it.matches(keyword) }
        PreviewTab.UNCHANGED -> preview.unchangedConversations.any { it.matches(keyword) }
    }

    if (showHelp) {
        ImportHelpDialog(
            onDismiss = {
                showHelp = false
                context.writeBooleanPreference(HELP_SHOWN_PREFERENCE, true)
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_page_deepseek_preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(
                            imageVector = HugeIcons.InformationCircle,
                            contentDescription = stringResource(R.string.backup_page_deepseek_help_title),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        if (decisions.values.any { it == null }) {
                            tab = PreviewTab.CHANGED
                            showMissingDecision = true
                        } else {
                            onStart(decisions.mapValues { requireNotNull(it.value) })
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    Text(stringResource(R.string.backup_page_deepseek_start_import))
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PreviewTab.entries.forEach { candidate ->
                    CategoryButton(
                        title = stringResource(candidate.labelRes()),
                        count = candidate.count(preview),
                        selected = tab == candidate,
                        onClick = {
                            tab = candidate
                            query = ""
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.backup_page_deepseek_search_hint)) },
            )

            // 只有「有变化」需要统一处理；新增和无变化默认全部导入
            if (tab == PreviewTab.CHANGED && preview.changed.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.backup_page_deepseek_apply_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DecisionChips(
                        selected = unifiedDecision,
                        onSelect = { decision ->
                            decisions = decisions.mapValues { decision }
                            showMissingDecision = false
                        },
                    )
                }
            }

            if (showMissingDecision) {
                Text(
                    text = stringResource(R.string.backup_page_deepseek_preview_need_decision),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (!hasItems) {
                Text(
                    text = stringResource(
                        if (keyword.isEmpty()) {
                            R.string.backup_page_deepseek_empty_list
                        } else {
                            R.string.backup_page_deepseek_no_match
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when (tab) {
                        PreviewTab.NEW -> items(
                            preview.newConversations.filter { it.matches(keyword) },
                            key = { it.conversationId },
                        ) { item ->
                            TitleRow(item)
                            HorizontalDivider()
                        }

                        PreviewTab.UNCHANGED -> items(
                            preview.unchangedConversations.filter { it.matches(keyword) },
                            key = { it.conversationId },
                        ) { item ->
                            TitleRow(item)
                            HorizontalDivider()
                        }

                        PreviewTab.CHANGED -> items(
                            preview.changed.filter { it.matches(keyword) },
                            key = { it.conversationId },
                        ) { conversation ->
                            ChangedConversationRow(
                                conversation = conversation,
                                decision = decisions[conversation.conversationId],
                                onDecision = { decision ->
                                    decisions = decisions + (conversation.conversationId to decision)
                                    showMissingDecision = false
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryButton(
    title: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            // 未选中时与页面背景同色，只靠边框勾出轮廓
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        shadowElevation = if (selected) 3.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ImportHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_deepseek_help_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_page_deepseek_help_original))
                Text(stringResource(R.string.backup_page_deepseek_help_incoming))
                Text(stringResource(R.string.backup_page_deepseek_help_keep))
                Text(stringResource(R.string.backup_page_deepseek_help_overwrite))
                Text(stringResource(R.string.backup_page_deepseek_help_copy))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_page_deepseek_help_close))
            }
        },
    )
}

@Composable
private fun TitleRow(item: DeepSeekPreviewItem) {
    Text(
        text = item.title,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun ChangedConversationRow(
    conversation: DeepSeekChangedConversation,
    decision: DeepSeekImportDecision?,
    onDecision: (DeepSeekImportDecision) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = conversation.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.backup_page_deepseek_time_range,
                formatTime(conversation.previousUpdatedAt),
                formatTime(conversation.updatedAt),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DecisionChips(selected = decision, onSelect = onDecision)
    }
}

@Composable
private fun DecisionChips(
    selected: DeepSeekImportDecision?,
    onSelect: (DeepSeekImportDecision) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeepSeekImportDecision.entries.forEach { decision ->
            FilterChip(
                selected = selected == decision,
                onClick = { onSelect(decision) },
                label = { Text(stringResource(decision.labelRes())) },
                shape = RoundedCornerShape(50),
            )
        }
    }
}

private fun PreviewTab.count(preview: DeepSeekImportPreview): Int = when (this) {
    PreviewTab.NEW -> preview.newCount
    PreviewTab.CHANGED -> preview.changed.size
    PreviewTab.UNCHANGED -> preview.unchangedCount
}

private fun PreviewTab.labelRes(): Int = when (this) {
    PreviewTab.NEW -> R.string.backup_page_deepseek_tab_new
    PreviewTab.CHANGED -> R.string.backup_page_deepseek_tab_changed
    PreviewTab.UNCHANGED -> R.string.backup_page_deepseek_tab_unchanged
}

private fun DeepSeekImportDecision.labelRes(): Int = when (this) {
    DeepSeekImportDecision.KEEP -> R.string.backup_page_deepseek_decision_keep
    DeepSeekImportDecision.OVERWRITE -> R.string.backup_page_deepseek_decision_overwrite
    DeepSeekImportDecision.KEEP_BOTH -> R.string.backup_page_deepseek_decision_copy
}

private fun DeepSeekPreviewItem.matches(keyword: String): Boolean =
    keyword.isEmpty() || title.contains(keyword, ignoreCase = true)

private fun DeepSeekChangedConversation.matches(keyword: String): Boolean =
    keyword.isEmpty() || title.contains(keyword, ignoreCase = true)

@Composable
private fun formatTime(millis: Long?): String = millis?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)
} ?: stringResource(R.string.backup_page_deepseek_time_unknown)
