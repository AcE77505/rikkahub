package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.data.ai.subagent.SubAgentPhase
import me.rerere.rikkahub.data.ai.subagent.SubAgentProgressRegistry
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject

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
        val registry = koinInject<SubAgentProgressRegistry>()
        val progressMap by registry.progress.collectAsStateWithLifecycle()
        val progress = progressMap[context.tool.toolCallId]
        if (progress != null) {
            val phase = when (progress.phase) {
                SubAgentPhase.RUNNING -> "运行中"
                SubAgentPhase.REASONING -> "思考中"
                SubAgentPhase.RESPONDING -> "回复中"
                SubAgentPhase.TOOL -> "调用工具"
            }
            val activity = progress.toolName?.let { "工具 $it" }
                ?: progress.text?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(160)
                ?: progress.reasoning?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(160)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "$phase · ${progress.elapsedMs / 1000}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!activity.isNullOrBlank()) {
                    Text(
                        text = activity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            return
        }

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

/** 并行派发多个子代理的卡片 */
class SubAgentFleetToolUI : ToolUIRenderer {
    override val toolName: String = "fleet"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

    @Composable
    override fun title(context: ToolUIContext): String = "并行子代理"

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.loading || context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val registry = koinInject<SubAgentProgressRegistry>()
        val progressMap by registry.progress.collectAsStateWithLifecycle()
        val progress = progressMap[context.tool.toolCallId]
        if (progress != null) {
            val phase = when (progress.phase) {
                SubAgentPhase.RUNNING -> "运行中"
                SubAgentPhase.REASONING -> "思考中"
                SubAgentPhase.RESPONDING -> "回复中"
                SubAgentPhase.TOOL -> "调用工具"
            }
            val activity = progress.toolName?.let { "工具 $it" }
                ?: progress.text?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(160)
                ?: progress.reasoning?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(160)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "$phase · ${progress.elapsedMs / 1000}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!activity.isNullOrBlank()) {
                    Text(
                        text = activity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            return
        }

        val status = context.content.getStringContent("status")
        val count = context.content?.jsonObjectOrNull?.get("tasks")?.jsonArray?.size
        val text = when {
            status == null -> "运行中"
            status == "completed" -> "完成"
            status == "partial" -> "部分完成"
            status == "failed" -> "失败"
            else -> status
        }
        Text(
            text = listOfNotNull(text, count?.let { "$it 个子代理" }).joinToString(" · "),
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
