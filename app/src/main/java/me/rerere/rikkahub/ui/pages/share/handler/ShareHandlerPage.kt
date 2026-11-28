package me.rerere.rikkahub.ui.pages.share.handler

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Files02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 分享内容（文本 + 文件）的一份预览；根据是否为空决定整块是否显示。 */
private data class SharePreview(
    val text: String,
    val images: List<Uri>,
    val otherFiles: List<Uri>,
)

/** 用 ContentResolver 的 MIME 判断是不是图片；退回到扩展名。相册的 content:// 常常不带扩展名。 */
private fun Uri.isImage(context: android.content.Context): Boolean {
    val mime = runCatching { context.contentResolver.getType(this) }.getOrNull()
    if (mime != null) return mime.startsWith("image/")
    val ext = toString().substringBefore('?').substringAfterLast('.', "").lowercase()
    return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif")
}

@Composable
fun ShareHandlerPage(
    text: String,
    files: List<Uri>,
    onSelect: (Assistant) -> Unit,
) {
    val vm: ShareHandlerVM = koinViewModel(parameters = { parametersOf(text) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val (images, otherFiles) = files.partition { it.isImage(context) }
    val preview = SharePreview(text = text.trim(), images = images, otherFiles = otherFiles)
    val hasPreview = preview.text.isNotEmpty() || preview.images.isNotEmpty() || preview.otherFiles.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.share_handler_page_title))
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 分享内容预览。纯文本分享时只是一张文本卡片；只有图片时会显示缩略图；
            // 没有内容（例如只带了文件却解析失败）时整块不显示，避免出现空白的「深色假按钮」。
            if (hasPreview) {
                item {
                    Card {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (preview.text.isNotEmpty()) {
                                Text(
                                    text = preview.text,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            preview.images.forEach { image ->
                                AsyncImage(
                                    model = image,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium),
                                )
                            }

                            preview.otherFiles.forEach { file ->
                                SharedFileRow(file)
                            }
                        }
                    }
                }
            }

            items(settings.assistants, key = { it.id }) { assistant ->
                val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
                Surface(
                    onClick = { onSelect(assistant) },
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    ListItem(
                        leadingContent = {
                            UIAvatar(
                                name = assistant.name.ifEmpty { defaultAssistantName },
                                value = assistant.avatar,
                                modifier = Modifier.size(32.dp),
                            )
                        },
                        headlineContent = {
                            Text(
                                text = assistant.name.ifEmpty { defaultAssistantName },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedFileRow(uri: Uri) {
    val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { uri.toString() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Files02,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
