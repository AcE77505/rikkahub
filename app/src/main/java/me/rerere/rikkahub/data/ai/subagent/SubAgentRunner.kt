package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.ai.retryPolicy
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.DEFAULT_SUBAGENT_SYSTEM_PROMPT
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentToolDefaults
import me.rerere.rikkahub.data.ai.tools.buildCompleteSubtaskTool
import kotlin.uuid.Uuid

/** 子代理运行结果；只把最终报告和状态交回主 Agent，不暴露子代理的完整上下文 */
data class SubAgentResult(
    val status: SubAgentStatus,
    val text: String? = null,
    val error: String? = null,
    val steps: Int = 0,
    val ref: String? = null,
)

enum class SubAgentStatus {
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

private val READ_ONLY_TOOL_NAMES = SubAgentToolDefaults.DEFAULT_ALLOWED_TOOLS

/** 即使配置放开写权限，也不能交给无人值守子代理的工具：递归、审批和需要设备界面的工具 */
private val ALWAYS_DENIED_TOOL_NAMES = setOf(
    "task",
    "fleet",
    "read_subagent_result",
    "ask_user",
    "clipboard_tool",
    "text_to_speech",
    "get_screen_time",
    "eval_javascript",
    "calendar_create",
)

/**
 * 在独立上下文里运行一个子代理。
 *
 * 只负责执行：不创建会话、不写 Conversation，也不经过主助手的 transformers。
 * 子代理看到的只有自己的系统提示和 task 文本；父对话历史不会传进来。
 */
class SubAgentRunner(
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val store: SubAgentStore,
) {
    private val conversationLimits = ConcurrentHashMap<String, Semaphore>()
    suspend fun run(
        settings: Settings,
        parentAssistant: Assistant,
        parentModel: Model,
        subAgent: SubAgent,
        task: String,
        baseTools: List<Tool>,
        conversationId: Uuid,
        maxStepsOverride: Int? = null,
        timeoutSecondsOverride: Int? = null,
        continueFrom: String? = null,
        onProgress: (SubAgentProgress) -> Unit = {},
    ): SubAgentResult {
        val timeoutSeconds = (timeoutSecondsOverride ?: subAgent.timeoutSeconds)
            .coerceIn(10, SubAgent.MAX_TIMEOUT_SECONDS)
        val timeoutMs = timeoutSeconds * 1000L
        val prior = if (continueFrom != null) {
            store.transcriptForContinuation(continueFrom, conversationId.toString())
                ?: return SubAgentResult(
                    status = SubAgentStatus.FAILED,
                    error = "Cannot continue sub-agent $continueFrom: it is missing, not completed, or belongs to another conversation",
                )
        } else {
            emptyList()
        }
        val systemPrompt = subAgent.systemPrompt.trim()
            .ifBlank { DEFAULT_SUBAGENT_SYSTEM_PROMPT }
        val messages = if (prior.isEmpty()) {
            listOf(
                UIMessage.system(systemPrompt),
                UIMessage.user(task),
            )
        } else {
            prior + UIMessage.user(task)
        }
        val ref = store.newRef()
        val now = System.currentTimeMillis()
        store.prepare(
            SubAgentRunMeta(
                ref = ref,
                parentConversationId = conversationId.toString(),
                parentAssistantId = parentAssistant.id.toString(),
                subAgentId = subAgent.id.toString(),
                label = subAgent.name,
                modelId = (subAgent.modelId ?: settings.subAgentModelId)?.toString(),
                status = SubAgentRunStatus.RUNNING,
                startedAt = now,
                updatedAt = now,
            )
        )

        var lastMessage: UIMessage? = null
        val limit = conversationLimits.getOrPut(conversationId.toString()) {
            Semaphore(settings.subAgentMaxConcurrency.coerceIn(1, 8))
        }
        try {
            val result = withTimeoutOrNull(timeoutMs) {
                limit.withPermit {
                    runInternal(
                        settings = settings,
                        parentAssistant = parentAssistant,
                        parentModel = parentModel,
                        subAgent = subAgent,
                        messages = messages,
                        baseTools = baseTools,
                        conversationId = conversationId,
                        maxStepsOverride = maxStepsOverride,
                        onMessage = { lastMessage = it },
                        onProgress = onProgress,
                        startedAt = now,
                    )
                }
            }
            val transcript = messages + listOfNotNull(lastMessage)
            if (result == null) {
                val error = "Sub-agent timed out after ${timeoutSeconds}s"
                store.cancel(ref, transcript, error)
                return SubAgentResult(
                    status = SubAgentStatus.TIMED_OUT,
                    error = error,
                    ref = ref,
                )
            }
            when (result.status) {
                SubAgentStatus.COMPLETED -> store.complete(ref, transcript, result.steps, result.text)
                SubAgentStatus.FAILED, SubAgentStatus.TIMED_OUT ->
                    store.fail(ref, transcript, result.error ?: "Sub-agent failed")
                SubAgentStatus.CANCELLED -> store.cancel(ref, transcript, result.error ?: "cancelled")
            }
            return result.copy(ref = ref)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                store.cancel(ref, messages + listOfNotNull(lastMessage), "cancelled")
            }
            throw e
        } catch (e: Throwable) {
            store.fail(ref, messages + listOfNotNull(lastMessage), e.message ?: e::class.simpleName.orEmpty())
            return SubAgentResult(
                status = SubAgentStatus.FAILED,
                error = e.message ?: e::class.simpleName.orEmpty(),
                ref = ref,
            )
        }
    }

    private suspend fun runInternal(
        settings: Settings,
        parentAssistant: Assistant,
        parentModel: Model,
        subAgent: SubAgent,
        messages: List<UIMessage>,
        baseTools: List<Tool>,
        conversationId: Uuid,
        maxStepsOverride: Int?,
        onMessage: (UIMessage) -> Unit,
        onProgress: (SubAgentProgress) -> Unit,
        startedAt: Long,
    ): SubAgentResult {
        val model = settings.findModelById(
            subAgent.modelId,
            settings.subAgentModelId ?: parentModel.id,
        ) ?: parentModel
        val providerSetting = model.findProvider(settings.providers) ?: return SubAgentResult(
            status = SubAgentStatus.FAILED,
            error = "No provider found for model ${model.displayName}",
        )
        val selectedTools = selectTools(
            baseTools = baseTools,
            subAgent = subAgent,
            defaultTools = settings.subAgentDefaultTools,
        )
        if (selectedTools.isEmpty() && subAgent.readOnly) {
            return SubAgentResult(
                status = SubAgentStatus.FAILED,
                error = "No read-only tools available for this sub-agent",
            )
        }
        val tools = selectedTools + buildCompleteSubtaskTool()

        val request = AgentRequest(
            provider = usageRecorder.wrap(
                providerManager.getProviderByType(providerSetting),
                UsageTag(UsageSource.SUBAGENT, parentAssistant.id, conversationId),
            ),
            providerSetting = providerSetting,
            messages = messages,
            tools = tools,
            stream = true,
            maxSteps = (maxStepsOverride ?: subAgent.maxSteps)
                .coerceIn(1, SubAgent.MAX_MAX_STEPS),
            retry = settings.retryPolicy(),
        )
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest =
                StepRequest(
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        temperature = subAgent.temperature ?: parentAssistant.temperature,
                        topP = parentAssistant.topP,
                        maxTokens = parentAssistant.maxTokens,
                        tools = tools,
                        reasoningLevel = subAgent.reasoningLevel ?: me.rerere.ai.core.ReasoningLevel.AUTO,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                        sessionId = conversationId.toString(),
                    ),
                )
        }

        var finished: me.rerere.ai.ui.UIMessage? = null
        var lastEmit = 0L
        var lastPhase: SubAgentPhase? = null
        fun emitProgress(message: UIMessage) {
            val now = System.currentTimeMillis()
            val text = message.parts.filterIsInstance<UIMessagePart.Text>()
                .lastOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }
            val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
                .lastOrNull()?.reasoning?.trim()?.takeIf { it.isNotEmpty() }
            val toolName = message.getTools().lastOrNull()?.toolName
            val phase = when {
                toolName != null -> SubAgentPhase.TOOL
                text != null -> SubAgentPhase.RESPONDING
                reasoning != null -> SubAgentPhase.REASONING
                else -> SubAgentPhase.RUNNING
            }
            if (phase == lastPhase && now - lastEmit < 100) return
            lastPhase = phase
            lastEmit = now
            onProgress(
                SubAgentProgress(
                    phase = phase,
                    text = text?.take(4_000),
                    reasoning = reasoning?.take(4_000),
                    toolName = toolName,
                    elapsedMs = (now - startedAt).coerceAtLeast(0),
                )
            )
        }
        runAgent(request, hooks).collect { event ->
            when (event) {
                is AgentEvent.MessageUpdated -> {
                    onMessage(event.message)
                    emitProgress(event.message)
                }
                is AgentEvent.Finished -> {
                    finished = event.message
                    onMessage(event.message)
                    emitProgress(event.message)
                }
                else -> Unit
            }
        }
        val message = finished ?: return SubAgentResult(
            status = SubAgentStatus.FAILED,
            error = "Sub-agent finished without a message",
        )
        val report = message.getTools()
            .lastOrNull { it.toolName == "complete_subtask" && it.isExecuted }
            ?.let { tool ->
                runCatching {
                    tool.inputAsJson().jsonObject["report"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
            ?.trim()
        val text = report?.takeIf { it.isNotEmpty() } ?: message.toText().trim()
        if (text.isBlank()) {
            return SubAgentResult(
                status = SubAgentStatus.FAILED,
                error = "Sub-agent produced an empty final answer",
                steps = message.stepCount ?: 0,
            )
        }
        return SubAgentResult(
            status = SubAgentStatus.COMPLETED,
            text = text,
            steps = message.stepCount ?: 0,
        )
    }

    private fun selectTools(
        baseTools: List<Tool>,
        subAgent: SubAgent,
        defaultTools: Set<String>,
    ): List<Tool> {
        val configured = subAgent.allowedTools ?: defaultTools
        val allowed = configured
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return baseTools
            .asSequence()
            .filter { it.name in allowed }
            .filter { it.name !in ALWAYS_DENIED_TOOL_NAMES }
            .filter { !subAgent.readOnly || it.name in READ_ONLY_TOOL_NAMES }
            .map { it.copy(needsApproval = { false }) }
            .toList()
    }
}
