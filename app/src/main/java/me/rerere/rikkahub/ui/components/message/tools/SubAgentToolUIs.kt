package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.json.contentOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/** 主模型派发子代理的卡片：标题显示子代理名，摘要显示运行状态与步数 */
class SubAgentTaskToolUI : ToolUIRenderer {
    override val toolName: String = "task"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

    @Composable
    override fun title(context: ToolUIContext): String {
        val agent = context.arguments.getStringContent("agent")
        return if (agent.isNullOrBlank()) "子代理" else "子代理 · $agent"
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.loading || context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val status = context.content.getStringContent("status")
        val steps = context.content.getStringContent("steps")
        val ref = context.content.getStringContent("ref")
        val text = when {
            status == null -> "运行中"
            status == "completed" -> "完成"
            status == "timed_out" -> "超时"
            status == "failed" -> "失败"
            else -> status
        }
        val detail = listOfNotNull(
            text,
            steps?.let { "$it 步" },
            ref,
        ).joinToString(" · ")
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 读取子代理完整结果的分页卡片 */
class SubAgentReadToolUI : ToolUIRenderer {
    override val toolName: String = "read_subagent_result"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

    @Composable
    override fun title(context: ToolUIContext): String {
        val ref = context.arguments.getStringContent("ref")
        return if (ref.isNullOrBlank()) "读取子代理结果" else "读取子代理结果 · $ref"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null || context.loading

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = context.content?.jsonPrimitiveOrNull?.contentOrNull
            ?: if (context.loading) "读取中" else null
        if (text != null) {
            Text(
                text = text.lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
