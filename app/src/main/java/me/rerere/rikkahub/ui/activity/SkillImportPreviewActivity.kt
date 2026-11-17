package me.rerere.rikkahub.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.writeBooleanPreference
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillImportCandidate
import me.rerere.rikkahub.ui.theme.RikkahubTheme

/**
 * 从 GitHub 解析出的技能列表，在预览 Activity 和技能页之间传递。
 * 技能文件可能包含图片等附属文件，不适合走 Intent extra。
 */
object SkillImportSession {
    var candidates: List<SkillImportCandidate>? = null
    var selectedNames: Set<String>? = null

    fun clear() {
        candidates = null
        selectedNames = null
    }
}

/** 多技能仓库的导入预览：默认全选，取消不需要的技能后再安装。 */
class SkillImportPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val candidates = SkillImportSession.candidates
        if (candidates.isNullOrEmpty()) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                SkillImportPreviewScreen(
                    candidates = candidates,
                    onInstall = { selected ->
                        SkillImportSession.selectedNames = selected
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
            Intent(context, SkillImportPreviewActivity::class.java)
    }
}

private const val HELP_SHOWN_PREFERENCE = "skill_import_preview_help_shown"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillImportPreviewScreen(
    candidates: List<SkillImportCandidate>,
    onInstall: (Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // 默认全选，用户只需要取消不需要的技能
    var selected by remember {
        mutableStateOf<Set<String>>(candidates.map { it.name }.toSet())
    }
    val context = LocalContext.current
    var showHelp by remember {
        mutableStateOf(!context.readBooleanPreference(HELP_SHOWN_PREFERENCE, false))
    }

    val keyword = query.trim()
    val visible = remember(candidates, keyword) {
        if (keyword.isEmpty()) {
            candidates
        } else {
            candidates.filter { candidate ->
                candidate.name.contains(keyword, ignoreCase = true) ||
                    candidate.description.contains(keyword, ignoreCase = true)
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = {
                showHelp = false
                context.writeBooleanPreference(HELP_SHOWN_PREFERENCE, true)
            },
            title = { Text(stringResource(R.string.skills_page_import_help_title)) },
            text = { Text(stringResource(R.string.skills_page_import_help_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHelp = false
                        context.writeBooleanPreference(HELP_SHOWN_PREFERENCE, true)
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skills_page_import_select_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(
                            imageVector = HugeIcons.InformationCircle,
                            contentDescription = stringResource(R.string.skills_page_import_help_title),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { onInstall(selected) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    Text(stringResource(R.string.skills_page_import_install_selected))
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.skills_page_import_selected_count,
                        selected.size,
                        candidates.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                val allSelected = selected.size == candidates.size
                TextButton(
                    onClick = {
                        selected = if (allSelected) {
                            emptySet()
                        } else {
                            candidates.map { it.name }.toSet()
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (allSelected) {
                                R.string.skills_page_import_clear_all
                            } else {
                                R.string.skills_page_import_select_all
                            }
                        )
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.skills_page_import_search_hint)) },
            )

            if (visible.isEmpty()) {
                Text(
                    text = stringResource(R.string.skills_page_import_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(visible, key = { it.name }) { candidate ->
                        SkillCandidateRow(
                            candidate = candidate,
                            checked = candidate.name in selected,
                            onToggle = {
                                selected = if (candidate.name in selected) {
                                    selected - candidate.name
                                } else {
                                    selected + candidate.name
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillCandidateRow(
    candidate: SkillImportCandidate,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = candidate.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (candidate.description.isNotBlank()) {
                Text(
                    text = candidate.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (candidate.alreadyInstalled) {
                Text(
                    text = stringResource(R.string.skills_page_import_already_installed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
