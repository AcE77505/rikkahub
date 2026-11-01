package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
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
        return if (agent.isNullOrBlank()) {
            stringResource(R.string.subagent_tool_task_title)
        } else {
            stringResource(R.string.subagent_tool_task_title_agent, agent)
        }
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
                SubAgentPhase.RUNNING -> stringResource(R.string.subagent_phase_running)
                SubAgentPhase.REASONING -> stringResource(R.string.subagent_phase_reasoning)
                SubAgentPhase.RESPONDING -> stringResource(R.string.subagent_phase_responding)
                SubAgentPhase.TOOL -> stringResource(R.string.subagent_phase_tool)
            }
            val activity = progress.toolName?.let { stringResource(R.string.subagent_tool_activity, it) }
                ?: progress.text?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(160)
                ?: progress.reasoning?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(160)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.subagent_tool_elapsed, phase, (progress.elapsedMs / 1000).toInt()),
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
            status == null -> stringResource(R.string.subagent_phase_running)
            status == "completed" -> stringResource(R.string.subagent_tool_status_completed)
            status == "timed_out" -> stringResource(R.string.subagent_tool_status_timed_out)
            status == "failed" -> stringResource(R.string.subagent_tool_status_failed)
            else -> status
        }
        val detail = listOfNotNull(
            text,
            steps?.toIntOrNull()?.let { stringResource(R.string.subagent_tool_steps, it) },
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
    override fun title(context: ToolUIContext): String = stringResource(R.string.subagent_fleet_title)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.loading || context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val registry = koinInject<SubAgentProgressRegistry>()
        val progressMap by registry.progress.collectAsStateWithLifecycle()
        val progress = progressMap[context.tool.toolCallId]
        if (progress != null) {
            val phase = when (progress.phase) {
                SubAgentPhase.RUNNING -> stringResource(R.string.subagent_phase_running)
                SubAgentPhase.REASONING -> stringResource(R.string.subagent_phase_reasoning)
                SubAgentPhase.RESPONDING -> stringResource(R.string.subagent_phase_responding)
                SubAgentPhase.TOOL -> stringResource(R.string.subagent_phase_tool)
            }
            val activity = progress.toolName?.let { stringResource(R.string.subagent_tool_activity, it) }
                ?: progress.text?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(160)
                ?: progress.reasoning?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(160)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.subagent_tool_elapsed, phase, (progress.elapsedMs / 1000).toInt()),
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
            status == null -> stringResource(R.string.subagent_phase_running)
            status == "completed" -> stringResource(R.string.subagent_tool_status_completed)
            status == "partial" -> stringResource(R.string.subagent_tool_status_partial)
            status == "failed" -> stringResource(R.string.subagent_tool_status_failed)
            else -> status
        }
        Text(
            text = listOfNotNull(text, count?.let { stringResource(R.string.subagent_fleet_count, it) }).joinToString(" · "),
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
        return if (ref.isNullOrBlank()) {
            stringResource(R.string.subagent_read_title)
        } else {
            stringResource(R.string.subagent_read_title_ref, ref)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null || context.loading

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = context.content?.jsonPrimitiveOrNull?.contentOrNull
            ?: if (context.loading) stringResource(R.string.subagent_reading) else null
        if (text != null) {
            Text(
                text = text.lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
