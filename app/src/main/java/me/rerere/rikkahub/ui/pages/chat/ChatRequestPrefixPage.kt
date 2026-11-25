package me.rerere.rikkahub.ui.pages.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * 「请求预览」：摊开每轮请求开头那段固定内容的原文，供核对与复制。
 *
 * 内容与真实请求同源（[me.rerere.rikkahub.data.ai.ChatRequestFactory.previewRequest]），
 * 包括系统提示词（含记忆与工具提示词）、动态工具路由块与工具定义。不含对话正文与 API Key。
 */
@Composable
fun ChatRequestPrefixPage(
    id: Uuid,
) {
    val vm: ChatVM = koinViewModel(
        parameters = { parametersOf(id.toString()) }
    )
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var reloadKey by remember { mutableIntStateOf(0) }
    val state by produceState<PreviewState>(PreviewState.Loading, reloadKey) {
        value = runCatching { vm.previewRequest() }
            .fold(
                onSuccess = { request -> request?.let(PreviewState::Ready) ?: PreviewState.Empty },
                onFailure = { PreviewState.Failed(it.message ?: it.javaClass.simpleName) },
            )
    }

    val copiedText = stringResource(R.string.request_prefix_copied)
    val copyFailedText = stringResource(R.string.request_prefix_copy_failed)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.request_prefix_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    val ready = state as? PreviewState.Ready
                    if (ready != null) {
                        IconButton(
                            onClick = {
                                val ok = context.copyToClipboard(ready.request.toPlainText())
                                toaster.show(
                                    if (ok) copiedText else copyFailedText,
                                    type = if (ok) ToastType.Success else ToastType.Error,
                                )
                            }
                        ) {
                            Icon(
                                HugeIcons.Copy01,
                                contentDescription = stringResource(R.string.request_prefix_copy),
                            )
                        }
                    }
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(
                            HugeIcons.Refresh03,
                            contentDescription = stringResource(R.string.request_prefix_reload),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when (val current = state) {
            PreviewState.Loading -> PreviewHint(
                text = stringResource(R.string.request_prefix_loading),
                padding = innerPadding,
            )

            PreviewState.Empty -> PreviewHint(
                text = stringResource(R.string.request_prefix_empty),
                padding = innerPadding,
            )

            is PreviewState.Failed -> PreviewHint(
                text = stringResource(R.string.request_prefix_failed, current.message),
                padding = innerPadding,
            )

            is PreviewState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { ParamsCard(current.request) }
                itemsIndexed(current.request.messages) { index, message ->
                    MessageCard(index = index, message = message)
                }
            }
        }
    }
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data object Empty : PreviewState
    data class Failed(val message: String) : PreviewState
    data class Ready(val request: StepRequest) : PreviewState
}

@Composable
private fun PreviewHint(text: String, padding: PaddingValues) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 采样参数与工具数量概览 */
@Composable
private fun ParamsCard(request: StepRequest) {
    val params = request.params
    CardGroup(title = { Text(stringResource(R.string.request_prefix_params)) }) {
        formItem(headlineContent = { Text(stringResource(R.string.request_prefix_model)) }) {
            MonoText(params.model.modelId)
        }
        formItem(headlineContent = { Text(stringResource(R.string.request_prefix_sampling)) }) {
            MonoText(
                buildString {
                    append("temperature=").append(params.temperature ?: "-")
                    append("  top_p=").append(params.topP ?: "-")
                    append("  max_tokens=").append(params.maxTokens ?: "-")
                    append("  reasoning=").append(params.reasoningLevel)
                }
            )
        }
        formItem(headlineContent = { Text(stringResource(R.string.request_prefix_tools_count, params.tools.size)) }) {
            if (params.tools.isEmpty()) {
                MonoText("-")
            } else {
                MonoText(params.tools.joinToString("\n") { "· ${it.name}" })
            }
        }
    }
}

@Composable
private fun MessageCard(index: Int, message: UIMessage) {
    CardGroup(
        title = {
            Text(
                stringResource(
                    R.string.request_prefix_message_title,
                    index + 1,
                    roleLabel(message.role),
                )
            )
        },
    ) {
        formItem {
            MonoText(message.readableText())
        }
    }
}

@Composable
private fun roleLabel(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> stringResource(R.string.request_prefix_role_system)
    MessageRole.USER -> stringResource(R.string.request_prefix_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.request_prefix_role_assistant)
    MessageRole.TOOL -> stringResource(R.string.request_prefix_role_tool)
}

@Composable
private fun MonoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 单条消息转成可读文本：文本直出，工具调用与结果附在后面 */
private fun UIMessage.readableText(): String = buildString {
    parts.forEach { part ->
        when (part) {
            is UIMessagePart.Text -> appendLine(part.text)
            is UIMessagePart.Reasoning -> {
                appendLine("[reasoning]")
                appendLine(part.reasoning)
            }

            is UIMessagePart.Tool -> {
                appendLine("[tool_call] ${part.toolName}")
                appendLine(part.input)
                if (part.output.isNotEmpty()) {
                    appendLine("[tool_result]")
                    part.output.forEach { output ->
                        if (output is UIMessagePart.Text) appendLine(output.text)
                    }
                }
            }

            is UIMessagePart.Image -> appendLine("[image] ${part.url.take(120)}")
            is UIMessagePart.Video -> appendLine("[video] ${part.url.take(120)}")
            is UIMessagePart.Audio -> appendLine("[audio] ${part.url.take(120)}")
            is UIMessagePart.Document -> appendLine("[document] ${part.fileName} (${part.mime})")
            else -> Unit
        }
    }
}.trimEnd()

/** 整份请求转成可直接粘贴保存的文本 */
private fun StepRequest.toPlainText(): String = buildString {
    appendLine("=== params ===")
    appendLine("model: ${params.model.modelId}")
    params.temperature?.let { appendLine("temperature: $it") }
    params.topP?.let { appendLine("top_p: $it") }
    params.maxTokens?.let { appendLine("max_tokens: $it") }
    appendLine("reasoning: ${params.reasoningLevel}")
    appendLine()
    appendLine("=== tools (${params.tools.size}) ===")
    params.tools.forEach { tool ->
        appendLine("- ${tool.name}")
        appendLine("  ${tool.description}")
        runCatching { tool.parameters() }.getOrNull()?.let { schema ->
            runCatching { JsonInstantPretty.encodeToString(schema) }
                .getOrNull()
                ?.lineSequence()
                ?.joinToString("\n") { "  $it" }
                ?.let { appendLine(it) }
        }
        appendLine()
    }
    appendLine("=== messages (${messages.size}) ===")
    messages.forEachIndexed { index, message ->
        appendLine()
        appendLine("--- [${index + 1}] ${message.role} ---")
        appendLine(message.readableText())
    }
}

/** 写入剪贴板；返回是否成功，[me.rerere.rikkahub.utils.writeClipboardText] 会吞掉异常，这里需要结果 */
private fun Context.copyToClipboard(text: String): Boolean = runCatching {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("rikkahub_request_preview", text))
}.isSuccess
