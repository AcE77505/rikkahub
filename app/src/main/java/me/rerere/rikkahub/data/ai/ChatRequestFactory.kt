package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.expandNotices
import me.rerere.ai.agent.extractSteers
import me.rerere.ai.agent.withRetry
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.common.android.Logging
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.LEGACY_WORKSPACE_TOOL_ALIASES
import me.rerere.rikkahub.data.ai.tools.SKILL_TOOL_ALIASES
import me.rerere.rikkahub.data.ai.tools.dynamic.CapabilityRouteRenderer
import me.rerere.rikkahub.data.ai.tools.dynamic.DynamicTools
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.effectiveSystemPrompt
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ChatRequestFactory"

/**
 * 一轮对话的请求上下文，按会话固定的配置解析一次。
 *
 * 正常对话和压缩用同一份上下文组装请求，系统提示词、工具定义、参数和 sessionId 都相同，
 * 压缩请求的前缀才能和上一轮对话一致，命中提示词缓存。
 */
class ChatTurnContext(
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
    val providerSetting: ProviderSetting,
    val conversationId: Uuid,
    val conversationSystemPrompt: String?,
    val workspaceCwd: String?,
    val memories: List<MemoryFile>,
    val tools: List<Tool>,
    /** 动态工具状态；关闭时为 [DynamicTools.DISABLED] */
    val dynamicTools: DynamicTools = DynamicTools.DISABLED,
)

/** 把会话变成发给模型的请求：系统提示词、上下文裁剪、输入输出转换都在这里 */
class ChatRequestFactory(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val chatToolFactory: ChatToolFactory,
    private val memoryRepository: MemoryRepository,
    templateTransformer: TemplateTransformer,
    workspaceRepository: WorkspaceRepository,
) {
    private val inputTransformers: List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        templateTransformer,
        WorkspaceReminderTransformer(workspaceRepository),
    )

    private val outputTransformers: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )

    /** 解析本轮的助手、模型、记忆和工具；MCP 服务名不合法时抛出 InvalidMcpServerNamesException */
    suspend fun createContext(settings: Settings, conversation: Conversation): ChatTurnContext {
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准
        val assistant = settings.getAssistantOf(conversation)
        val model = settings.getChatModelOf(conversation)
            ?: throw IllegalStateException("No chat model selected")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val dynamic = chatToolFactory.createDynamicTools(
            settings = settings,
            assistant = assistant,
            model = model,
            conversationId = conversation.id,
            workspaceCwd = conversation.workspaceCwd,
        )
        return ChatTurnContext(
            settings = settings,
            assistant = assistant,
            model = model,
            providerSetting = provider,
            conversationId = conversation.id,
            conversationSystemPrompt = conversation.customSystemPrompt,
            workspaceCwd = conversation.workspaceCwd,
            memories = if (assistant.enableMemory) memoryRepository.getFiles(assistant.memoryId) else emptyList(),
            tools = dynamic.tools,
            dynamicTools = dynamic,
        )
    }

    /**
     * 本轮交给 runAgent 的请求和钩子。[messages] 的最后一条是助手消息时从它继续，否则新建一条助手消息。
     * [processingStatus] 用来显示 OCR 这类输入转换的进度；[takePendingParts] 取出要在请求模型前写进助手消息的通知。
     * [pauseAfterTools] 在一批工具执行完之后调用，返回 true 时这次运行以 Paused 结束。
     */
    fun createAgent(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?>,
        takePendingParts: suspend () -> List<UIMessagePart> = { emptyList() },
        // 有没有用户插话在等；为 true 时模型本要结束也会再请求一次
        pendingSteers: () -> Boolean = { false },
        pauseAfterTools: (UIMessage) -> Boolean = { false },
    ): Pair<AgentRequest, AgentHooks> {
        val activeSteerDiagnostics = linkedSetOf<Uuid>()
        var agentRunFinished = false
        val request = AgentRequest(
            provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.CHAT, turn.assistant.id, turn.conversationId),
            ),
            providerSetting = turn.providerSetting,
            messages = messages,
            tools = turn.tools,
            // 旧对话里记的是 workspace_*，改名后靠这张表仍能找到对应工具
            toolAliases = LEGACY_WORKSPACE_TOOL_ALIASES + SKILL_TOOL_ALIASES,
            stream = turn.assistant.streamOutput,
            retry = turn.settings.retryPolicy(),
        )
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                Log.i(TAG, "prepareStep: ${turn.model.id}")
                return buildStep(
                    turn = turn,
                    messages = messages,
                    processingStatus = processingStatus,
                    diagnosticSteerIds = activeSteerDiagnostics,
                )
            }

            override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }

            override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.transforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .onGenerationFinish(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }

            override suspend fun pendingParts(): List<UIMessagePart> {
                val parts = takePendingParts()
                parts.filterIsInstance<UIMessagePart.Steer>().forEach { steer ->
                    val id = steer.diagnosticId ?: return@forEach
                    activeSteerDiagnostics += id
                    Logging.log("Interjection", "id=$id event=attached_to_agent_message")
                }
                return parts
            }

            override suspend fun hasPendingSteers() = pendingSteers()

            override suspend fun shouldPause(message: UIMessage) = pauseAfterTools(message)

            override suspend fun onModelRequestStarted(step: StepRequest) {
                step.steerDiagnostics.forEach { (id, included) ->
                    Logging.log(
                        "Interjection",
                        "id=$id event=provider_request_started in_prompt=$included model=${turn.model.modelId} stream=${request.stream}",
                    )
                }
            }

            override suspend fun onModelRequestCompleted(step: StepRequest, message: UIMessage) {
                step.steerDiagnostics.keys.forEach { id ->
                    val textChars = message.parts.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
                    val reasoningChars =
                        message.parts.filterIsInstance<UIMessagePart.Reasoning>().sumOf { it.reasoning.length }
                    val toolCalls = message.getTools().size
                    Logging.log(
                        "Interjection",
                        "id=$id event=response_received text_chars=$textChars reasoning_chars=$reasoningChars tool_calls=$toolCalls",
                    )
                }
            }

            override suspend fun onModelRequestFailed(step: StepRequest, error: Throwable) {
                step.steerDiagnostics.keys.forEach { id ->
                    Logging.log(
                        "Interjection",
                        "id=$id event=request_failed error=${error::class.simpleName ?: "unknown"}",
                    )
                }
            }

            override suspend fun onRunFinished(reason: AgentFinishReason) {
                agentRunFinished = true
                activeSteerDiagnostics.forEach { id ->
                    Logging.log(
                        "Interjection",
                        "id=$id event=agent_run_finished reason=${reason::class.simpleName ?: "unknown"}",
                    )
                }
            }

            override suspend fun onRunFailed(error: Throwable) {
                if (agentRunFinished) return
                val event = if (error is CancellationException) "agent_run_cancelled" else "agent_run_failed"
                activeSteerDiagnostics.forEach { id ->
                    Logging.log(
                        "Interjection",
                        "id=$id event=$event error=${error::class.simpleName ?: "unknown"}",
                    )
                }
            }
        }
        return request to hooks
    }

    /**
     * 生成压缩摘要。
     *
     * 请求按正常对话组装（系统提示词、工具定义、参数和 sessionId 都相同），只在末尾追加压缩指令，
     * 前缀与上一轮对话一致，可以命中提示词缓存，模型也能看到工具调用和结果。
     * 工具定义必须保留：它属于请求前缀，去掉或改动 tool_choice 都会让缓存失效。
     */
    suspend fun generateCompressionSummary(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
    ): String {
        // 组装请求要把全部历史过一遍输入转换器，长对话要算好几秒：不能放在调用方的主线程上，
        // 这段纯计算里也检查不到取消。放进独立的协程，调用方取消时 await 立刻返回、不用等它算完；
        // 没算完的那份同时被取消，在下一个挂起点停下，最迟在发出请求之前
        val work = CoroutineScope(Dispatchers.IO).async { requestCompressionSummary(turn, messages, compressPrompt) }
        return try {
            work.await()
        } finally {
            work.cancel()
        }
    }

    private suspend fun requestCompressionSummary(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
    ): String {
        val (requestMessages, params) = buildStep(turn, messages)
        // 压缩指令不经过输入转换器，模板转换器会把其中的花括号当成模板语法
        val result = withRetry(
            policy = turn.settings.retryPolicy(),
            onRetry = { Log.w(TAG, "Compression request failed, retrying in ${it.delay}", it.cause) },
        ) {
            usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.COMPRESSION, turn.assistant.id, turn.conversationId),
            ).generateText(
                providerSetting = turn.providerSetting,
                messages = requestMessages + UIMessage.user(compressPrompt),
                // 助手的输出上限是给聊天回复定的，结构化摘要写不完会被截断；max_tokens 不属于请求前缀，改了不影响缓存
                params = params.copy(maxTokens = null),
            )
        }
        val summary = result.message.compressionSummaryOrNull()
            ?: error(context.getString(R.string.chat_page_compress_tool_call_response))
        return summary.ifBlank { error("Failed to generate compressed summary") }
    }

    /**
     * 预览请求初始化时的内容，供「请求预览」界面展示。
     *
     * 只包含每轮对话开头都相同的部分：系统提示词（含记忆与工具提示词）、动态工具路由块、
     * 以及工具定义。不包含任何历史或用户消息——那些是对话正文，不属于初始化内容。
     *
     * 与真正发送时走的是同一个 [buildStep]（`includeHistory = false`），因此除非用户改过
     * 助手配置，看到的内容就是每一轮实际发出的前缀。不含 API Key 等凭证。
     */
    suspend fun previewRequest(
        turn: ChatTurnContext,
        messages: List<UIMessage> = emptyList(),
    ): StepRequest = buildStep(turn, messages, includeHistory = false)

    /** 一步请求实际发出的消息和参数；正常对话和压缩共用，二者的请求前缀必须一致才能命中提示词缓存 */
    private suspend fun buildStep(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        diagnosticSteerIds: Set<Uuid> = emptySet(),
        // false 时不带任何历史消息，只保留请求前缀，用于预览初始化内容
        includeHistory: Boolean = true,
    ): StepRequest {
        val assistant = turn.assistant
        val historyMessages = if (includeHistory) {
            messages.limitContext(assistant.contextMessageLimit).withContinuationHint()
        } else {
            emptyList()
        }
        val diagnosticSteers = historyMessages.flatMap { message ->
            message.parts.filterIsInstance<UIMessagePart.Steer>().mapNotNull { steer ->
                steer.diagnosticId
                    ?.takeIf { it in diagnosticSteerIds }
                    ?.let { it to (steer.createdAt ?: message.createdAt) }
            }
        }
        val requestMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !turn.conversationSystemPrompt.isNullOrBlank()) {
                        turn.conversationSystemPrompt
                    } else {
                        assistant.effectiveSystemPrompt()
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(turn.memories))
                }
                // 工具prompt
                turn.tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(turn.model, messages))
                }
                // 动态工具：未列出工具的数量与领域，让模型知道自己可能缺工具
                appendDynamicToolsHint(turn)
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
                // 路由块每轮都变，必须追加在 system prompt 之后、对话之前，避免毁掉前缀缓存
                // 预览初始化内容时没有用户消息，路由块按空查询渲染，与首次请求一致
                val route = turn.dynamicTools.renderRoute(lastUserQuery(messages))
                if (route.isNotBlank()) {
                    add(UIMessage.system(prompt = route).copy(isSynthetic = true))
                }
            }
            if (includeHistory) {
                // 插话要在输入转换器之前拆成普通用户消息，附件、模板、用户正则才和直接发送一致
                addAll(historyMessages.extractSteers())
            }
        }.transforms(
            transformers = inputTransformers,
            context = context,
            model = turn.model,
            assistant = assistant,
            settings = turn.settings,
            processingStatus = processingStatus,
            workspaceCwd = turn.workspaceCwd,
        ).expandNotices()
        val params = TextGenerationParams(
            model = turn.model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = turn.tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(turn.model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(turn.model.customBodies)
            },
            sessionId = turn.conversationId.toString(),
        )
        val steerDiagnostics = if (diagnosticSteerIds.isEmpty()) {
            emptyMap()
        } else {
            val userMessages = requestMessages.filter { it.role == MessageRole.USER }
            val userMessageTimes = userMessages
                .groupingBy { it.createdAt }
                .eachCount()
                .toMutableMap()
            val included = diagnosticSteerIds.associateWith { false }.toMutableMap()
            diagnosticSteers.forEach { (id, timestamp) ->
                val matchedById = userMessages.firstOrNull { it.steerDiagnosticId == id }
                if (matchedById != null) {
                    included[id] = true
                    val remaining = userMessageTimes[matchedById.createdAt] ?: 0
                    if (remaining > 0) userMessageTimes[matchedById.createdAt] = remaining - 1
                } else {
                    val remaining = userMessageTimes[timestamp] ?: 0
                    included[id] = remaining > 0
                    if (remaining > 0) userMessageTimes[timestamp] = remaining - 1
                }
            }
            included
        }
        return StepRequest(requestMessages, params, steerDiagnostics)
    }
}

/** 本轮用户消息的文本，作为路由检索的输入；没有用户消息时返回空串（不注入路由块） */
private fun lastUserQuery(messages: List<UIMessage>): String =
    messages.lastOrNull { it.role == MessageRole.USER }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        .orEmpty()

/**
 * 「还有 N 个工具未列出」的提示。
 *
 * 只给数量与领域，不列具体名字——否则等于又把清单塞回去。模型据此判断是否需要先搜索
 * （见 docs/references/capability-tool-search-research.md 5.5.1 第二层）。
 */
internal fun StringBuilder.appendDynamicToolsHint(turn: ChatTurnContext) {
    val dynamic = turn.dynamicTools
    if (dynamic.deferredCount <= 0) return
    appendLine()
    appendLine("<available_tools>")
    // 不写死数量：search_tools 的 omitted 随查询变化，写死会让模型形成错误预期
    append(
        "Additional tools are not listed here. They cover " +
            dynamic.deferredDomains.joinToString(", ") +
            ". Call search_tools before planning when the task might need them.\n"
    )
    append("</available_tools>")
}

private const val CONTINUATION_HINT =
    "The conversation history before this point was replaced by the summary above. " +
        "If a task was in progress, continue it from where it left off: do not reply to the summary itself, " +
        "and do not ask the user to repeat anything."

/**
 * 检查点后面不是用户消息时（一轮中途压缩，后面直接是助手的回复或什么都没有），告诉模型接着干活，
 * 否则它会把摘要当成用户发来的内容去回应。
 *
 * 按"后面不是用户消息"而不是"在末尾"判断：这一轮之后的请求里检查点仍然带着这段话，前缀不变，不影响提示词缓存。
 */
internal fun List<UIMessage>.withContinuationHint(): List<UIMessage> {
    val index = indexOfLast { it.isContextCheckpoint }
    if (index < 0 || getOrNull(index + 1)?.role == MessageRole.USER) return this
    val checkpoint = this[index]
    return toMutableList().also {
        it[index] = checkpoint.copy(parts = checkpoint.parts + UIMessagePart.Text(CONTINUATION_HINT))
    }
}

/**
 * 取出压缩请求回复里的摘要正文。
 *
 * 指令要求不调用工具，模型仍可能发起调用。这时附带的文本只是"我先看看文件"之类的说明，
 * 当成摘要保存会让之后的请求丢掉全部历史，所以返回 null 交给调用方报错。
 * 压缩请求不经过输出转换器，正文开头的 <think> 思考内容也要在这里剥掉，否则会存进摘要。
 */
internal fun UIMessage.compressionSummaryOrNull(): String? {
    if (getTools().isNotEmpty()) return null
    return listOf(this)
        .transformThinkTags(now = Clock.System.now(), generationFinished = true)
        .single()
        .toText()
        .trim()
}

/**
 * 输出转换只处理这一步新生成的 part：之前步骤的内容已经处理过，再处理一遍会让正则这类改写叠加，
 * 也能让每一步开头的 <think> 都被识别出来。
 */
internal suspend fun UIMessage.transformNewParts(
    newPartsFrom: Int,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): UIMessage {
    val transformed = transform(listOf(copy(parts = parts.drop(newPartsFrom)))).single()
    return transformed.copy(parts = parts.take(newPartsFrom) + transformed.parts)
}

/** 标题、建议、记忆整理这类后台请求的参数：只带模型自己的自定义请求头和请求体 */
internal fun backgroundTextGenerationParams(
    model: Model,
    sessionId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = sessionId.toString(),
)

internal fun Settings.retryPolicy() =
    if (networkSetting.enableAutoRetry) RetryPolicy.NETWORK else RetryPolicy.NONE
