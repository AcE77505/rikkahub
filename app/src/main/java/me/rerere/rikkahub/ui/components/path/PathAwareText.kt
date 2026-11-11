package me.rerere.rikkahub.ui.components.path

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import me.rerere.rikkahub.utils.findPathRanges

/**
 * 纯文本里的文件/目录路径变成可点击文本。
 *
 * 用于 [me.rerere.rikkahub.ui.components.message.ChatMessageTools] 的 ask_user 提问文本：
 * 那里不是 Markdown，但同样需要能点开路径；提问选项不走这里。
 */
@Composable
fun PathAwareText(
    text: String,
    onOpenPath: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, onOpenPath, linkColor) {
        if (onOpenPath == null) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                var cursor = 0
                for (range in findPathRanges(text)) {
                    append(text.substring(cursor, range.first))
                    val path = text.substring(range.first, range.last + 1)
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "rikkahub-path",
                            styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
                            linkInteractionListener = { onOpenPath(path) },
                        )
                    ) {
                        append(path)
                    }
                    cursor = range.last + 1
                }
                append(text.substring(cursor))
            }
        }
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
    )
}
