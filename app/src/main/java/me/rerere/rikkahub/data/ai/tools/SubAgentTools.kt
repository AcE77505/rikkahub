package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.agent.ToolCallContext
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubAgentApprovalRegistry
import me.rerere.rikkahub.data.ai.subagent.SubAgentProgressRegistry
import me.rerere.rikkahub.data.ai.subagent.SubAgentResult
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentStatus
import me.rerere.rikkahub.data.ai.subagent.SubAgentStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SubAgent
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

private const val MAX_SUBAGENT_PREVIEW_CHARS = 4_000

/**
 * 主助手用来派发子代理的工具。
 *
 * 返回 null 表示当前助手没有启用任何子代理，此时不应把 `task` 暴露给模型。
 * 工具描述里列出启用名称，便于模型选择；子代理自己的完整提示词不会进入主上下文。
 */
fun createTaskTool(
    runner: SubAgentRunner,
    store: SubAgentStore,
    settings: Settings,
    assistant: Assistant,
    parentModel: Model,
    baseTools: List<Tool>,
    conversationId: Uuid,
    approvalRegistry: SubAgentApprovalRegistry,
    progressRegistry: SubAgentProgressRegistry,
): Tool? {
    val enabled = settings.subAgents.filter { it.id in assistant.enabledSubAgents }
    if (enabled.isEmpty()) return null

    fun findAgent(raw: String?): SubAgent? {
        val name = raw?.trim().orEmpty()
        return if (name.isEmpty()) {
            enabled.firstOrNull()
        } else {
            enabled.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }

    val description = buildString {
        append(
            "Dispatch a focused sub-agent to handle an independent task and return a concise report. " +
                "The sub-agent does not see this conversation, so restate any context it needs in `prompt`. " +
                "Only its final report is returned; its working context never enters this conversation. " +
                "Use it for self-contained work such as code changes, investigation, or review."
        )
        appendLine()
        appendLine()
        append("Available sub-agents (pass the name as `agent`):")
        enabled.forEach { agent ->
            appendLine()
            append("- ${agent.name}")
            if (agent.description.isNotBlank()) append(": ${agent.description}")
        }
    }

    return Tool(
        name = "task",
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("agent", buildJsonObject {
                        put("type", "string")
                        put("description", "Name of an enabled sub-agent. Omit to use the first one.")
                    })
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "What the sub-agent should accomplish. Be specific and self-contained; " +
                                "the sub-agent does not see this conversation.",
                        )
                    })
                    put("max_steps", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Optional cap on the sub-agent's tool-call rounds. " +
                                "Defaults to the sub-agent's configured value.",
                        )
                    })
                    put("timeout_seconds", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Optional timeout in seconds. Defaults to the sub-agent's configured value.",
                        )
                    })
                    put("continue_from", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional sa_... reference from a completed or cancelled run in this " +
                                "conversation. The prior transcript is loaded and the new prompt is appended.",
                        )
                    })
                    put("adopt_ref", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional sa_... reference to reuse an existing result without running a sub-agent. " +
                                "When set, `prompt` is not required.",
                        )
                    })
                },
                required = listOf("prompt"),
            )
        },
        needsApproval = { args ->
            val adoptRef = args.jsonObject["adopt_ref"]
                ?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (adoptRef != null) {
                false
            } else {
                val raw = runCatching {
                    args.jsonObject["agent"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                val agent = findAgent(raw)
                agent != null &&
                    agent.requiresApproval &&
                    (!agent.rememberApproval || !approvalRegistry.isApproved(conversationId, agent.id))
            }
        },
        execute = { args ->
            val params = args.jsonObject
            val adoptRef = params["adopt_ref"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (adoptRef != null) {
                val read = runCatching {
                    store.readResult(
                        ref = adoptRef,
                        parentConversationId = conversationId.toString(),
                        offsetBytes = 0,
                        limitBytes = MAX_SUBAGENT_PREVIEW_CHARS,
                    )
                }.getOrNull()
                    ?: return@Tool subAgentError(
                        "unknown_ref",
                        "No readable result for $adoptRef in this conversation",
                    )
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", "adopted")
                            put("ref", read.ref)
                            put("result", read.text)
                            put("total_bytes", read.totalBytes)
                            read.nextOffsetBytes?.let { put("next_offset_bytes", it) }
                        }.toString()
                    )
                )
            }
            val prompt = params["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (prompt.isEmpty()) {
                return@Tool subAgentError("invalid_prompt", "prompt is required")
            }
            val requestedName = params["agent"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val subAgent = findAgent(requestedName)
                ?: return@Tool subAgentError(
                    "unknown_agent",
                    "No enabled sub-agent named \"$requestedName\". " +
                        "Available: ${enabled.joinToString { it.name }}",
                )

            val toolCallId = coroutineContext[ToolCallContext]?.toolCallId
            val result = try {
                runner.run(
                    settings = settings,
                    parentAssistant = assistant,
                    parentModel = parentModel,
                    subAgent = subAgent,
                    task = prompt,
                    baseTools = baseTools,
                    conversationId = conversationId,
                    maxStepsOverride = params["max_steps"]?.jsonPrimitive?.intOrNull,
                    timeoutSecondsOverride = params["timeout_seconds"]?.jsonPrimitive?.intOrNull,
                    continueFrom = params["continue_from"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotEmpty() },
                    onProgress = { progress ->
                        toolCallId?.let { progressRegistry.update(it, progress) }
                    },
                )
            } finally {
                toolCallId?.let { progressRegistry.clear(it) }
            }

            val fullText = result.text
            val truncated = fullText != null && fullText.length > MAX_SUBAGENT_PREVIEW_CHARS
            val preview = fullText?.let {
                if (it.length > MAX_SUBAGENT_PREVIEW_CHARS) {
                    it.take(MAX_SUBAGENT_PREVIEW_CHARS)
                } else {
                    it
                }
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", result.status.name.lowercase())
                        put("agent", subAgent.name)
                        result.ref?.let { put("ref", it) }
                        result.error?.let { put("error", it) }
                        preview?.let { put("result", it) }
                        if (truncated) {
                            put(
                                "note",
                                "Report is longer than $MAX_SUBAGENT_PREVIEW_CHARS characters; " +
                                    "call read_subagent_result with ref and offset_bytes=0 to read all pages.",
                            )
                        }
                        put("steps", result.steps)
                    }.toString()
                )
            )
        },
    )
}

/**
 * 并行派发多个子代理。每个任务仍是独立的 `SubAgentRunner`，共享同一会话并发上限。
 *
 * 不声明写路径：多个写任务可能同时改同一个文件，调用方应自行避免这种用法。
 */
fun createFleetTool(
    runner: SubAgentRunner,
    settings: Settings,
    assistant: Assistant,
    parentModel: Model,
    baseTools: List<Tool>,
    conversationId: Uuid,
    approvalRegistry: SubAgentApprovalRegistry,
    progressRegistry: SubAgentProgressRegistry,
): Tool? {
    val enabled = settings.subAgents.filter { it.id in assistant.enabledSubAgents }
    if (enabled.isEmpty()) return null

    fun findAgent(raw: String?): SubAgent? {
        val name = raw?.trim().orEmpty()
        return if (name.isEmpty()) enabled.firstOrNull()
        else enabled.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    return Tool(
        name = "fleet",
        description = "Dispatch multiple sub-agents in parallel and return each final report with its " +
            "sa_... reference. Use it for independent tasks that can run at the same time; a single " +
            "task should use `task` instead. All tasks share the conversation concurrency limit. " +
            "Sub-agents do not see this conversation, so restate the needed context in each prompt. " +
            "Do not run two writers against the same file in one fleet.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("tasks", buildJsonObject {
                        put("type", "array")
                        put("description", "Two or more independent sub-agent tasks.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("agent", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Name of an enabled sub-agent. Omit to use the first one.")
                                })
                                put("id", buildJsonObject {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Optional unique task id. Defaults to task_<index>; use it from depends_on.",
                                    )
                                })
                                put("depends_on", buildJsonObject {
                                    put("type", "array")
                                    put("description", "Task ids that must finish first; their reports are appended to this prompt.")
                                    put("items", buildJsonObject { put("type", "string") })
                                })
                                put("prompt", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Self-contained task description.")
                                })
                                put("max_steps", buildJsonObject { put("type", "integer") })
                                put("timeout_seconds", buildJsonObject { put("type", "integer") })
                            })
                            put("required", buildJsonArray { add("prompt") })
                        })
                    })
                },
                required = listOf("tasks"),
            )
        },
        needsApproval = { args ->
            val tasks = runCatching { args.jsonObject["tasks"]?.jsonArray }.getOrNull()
            if (tasks.isNullOrEmpty()) {
                true
            } else {
                tasks.any { task ->
                    val name = runCatching {
                        task.jsonObject["agent"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    val agent = findAgent(name)
                    agent == null ||
                        (agent.requiresApproval &&
                            (!agent.rememberApproval || !approvalRegistry.isApproved(conversationId, agent.id)))
                }
            }
        },
        execute = { args ->
            val tasks = runCatching { args.jsonObject["tasks"]?.jsonArray }.getOrNull()
                ?: return@Tool subAgentError("invalid_tasks", "tasks is required")
            if (tasks.isEmpty()) {
                return@Tool subAgentError("invalid_tasks", "tasks must not be empty")
            }
            if (tasks.size > 8) {
                return@Tool subAgentError("too_many_tasks", "fleet accepts at most 8 tasks")
            }
            val requests = tasks.mapIndexed { index, task ->
                val obj = task.jsonObject
                val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val name = obj["agent"]?.jsonPrimitive?.contentOrNull
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "task_$index"
                val dependsOn = obj["depends_on"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                    .orEmpty()
                FleetRequest(
                    id = id,
                    dependsOn = dependsOn,
                    subAgent = findAgent(name),
                    prompt = prompt,
                    maxSteps = obj["max_steps"]?.jsonPrimitive?.intOrNull,
                    timeoutSeconds = obj["timeout_seconds"]?.jsonPrimitive?.intOrNull,
                )
            }
            val invalid = requests.indexOfFirst { it.subAgent == null || it.prompt.isEmpty() }
            if (invalid != -1) {
                return@Tool subAgentError(
                    "invalid_tasks",
                    "task[$invalid] needs a valid agent and a non-empty prompt",
                )
            }
            val ids = requests.map { it.id }
            if (ids.size != ids.toSet().size) {
                return@Tool subAgentError("duplicate_task_id", "task ids must be unique")
            }
            val missingDependency = requests.firstNotNullOfOrNull { request ->
                request.dependsOn.firstOrNull { it !in ids }?.let { request.id to it }
            }
            if (missingDependency != null) {
                return@Tool subAgentError(
                    "unknown_dependency",
                    "task ${missingDependency.first} depends on unknown task ${missingDependency.second}",
                )
            }

            val results = LinkedHashMap<String, SubAgentResult>()
            val pending = requests.toMutableList()
            val toolCallId = coroutineContext[ToolCallContext]?.toolCallId
            try {
                while (pending.isNotEmpty()) {
                    val ready = pending.filter { request ->
                        request.dependsOn.all { dependency -> results.containsKey(dependency) }
                    }
                    if (ready.isEmpty()) {
                        return@Tool subAgentError(
                            "dependency_cycle",
                            "depends_on contains a cycle or an unresolvable dependency",
                        )
                    }
                    val batch = coroutineScope {
                        ready.map { request ->
                            async {
                                request to runner.run(
                                    settings = settings,
                                    parentAssistant = assistant,
                                    parentModel = parentModel,
                                    subAgent = checkNotNull(request.subAgent),
                                    task = withDependencyReports(request, results),
                                    baseTools = baseTools,
                                    conversationId = conversationId,
                                    maxStepsOverride = request.maxSteps,
                                    timeoutSecondsOverride = request.timeoutSeconds,
                                    onProgress = { progress ->
                                        toolCallId?.let { progressRegistry.update(it, progress) }
                                    },
                                )
                            }
                        }.awaitAll()
                    }
                    batch.forEach { (request, result) -> results[request.id] = result }
                    pending.removeAll(ready.toSet())
                }
            } finally {
                toolCallId?.let { progressRegistry.clear(it) }
            }

            val ordered = requests.map { request -> request to checkNotNull(results[request.id]) }
            val allCompleted = ordered.all { (_, result) -> result.status == SubAgentStatus.COMPLETED }
            val anyCompleted = ordered.any { (_, result) -> result.status == SubAgentStatus.COMPLETED }
            val status = when {
                allCompleted -> "completed"
                anyCompleted -> "partial"
                else -> "failed"
            }
            val tasksJson = buildJsonArray {
                ordered.forEach { (request, result) ->
                    add(
                        buildJsonObject {
                            put("id", request.id)
                            put("agent", request.subAgent?.name.orEmpty())
                            if (request.dependsOn.isNotEmpty()) {
                                put("depends_on", buildJsonArray {
                                    request.dependsOn.forEach { add(it) }
                                })
                            }
                            put("status", result.status.name.lowercase())
                            result.ref?.let { put("ref", it) }
                            result.error?.let { put("error", it) }
                            result.text?.take(2_000)?.let { put("result", it) }
                            put("steps", result.steps)
                        }
                    )
                }
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", status)
                        put("tasks", tasksJson)
                    }.toString()
                )
            )
        },
    )
}

private data class FleetRequest(
    val id: String,
    val dependsOn: List<String>,
    val subAgent: SubAgent?,
    val prompt: String,
    val maxSteps: Int?,
    val timeoutSeconds: Int?,
)

/** 把已完成依赖的报告附在新任务提示词后面，供被依赖的任务使用。 */
private fun withDependencyReports(
    request: FleetRequest,
    results: Map<String, SubAgentResult>,
): String {
    if (request.dependsOn.isEmpty()) return request.prompt
    return buildString {
        append(request.prompt)
        append("\n\n## Dependency task results\n")
        request.dependsOn.forEach { id ->
            val result = results[id]
            append("\n### ").append(id).append('\n')
            append("status: ").append(result?.status?.name?.lowercase() ?: "missing").append('\n')
            val body = result?.text ?: result?.error
            if (!body.isNullOrBlank()) {
                append(body.take(8_000))
                append('\n')
            }
        }
    }
}

/**
 * `task` 只返回有界预览；长报告通过这个工具分页读取，避免一次性灌进主对话上下文。
 */
fun createReadSubAgentResultTool(
    store: SubAgentStore,
    conversationId: Uuid,
): Tool = Tool(
    name = "read_subagent_result",
    description = "Read a completed or cancelled sub-agent's full result by its sa_... reference. " +
        "Results are paged by UTF-8 byte offset so long reports stay lossless without flooding the context.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("ref", buildJsonObject {
                    put("type", "string")
                    put("description", "The ref returned by task.")
                })
                put("offset_bytes", buildJsonObject {
                    put("type", "integer")
                    put("description", "UTF-8 byte offset to start from. Omit for the beginning.")
                })
                put("limit_bytes", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum UTF-8 bytes to return. Defaults to ${SubAgentStore.DEFAULT_RESULT_BYTES} " +
                            "and is capped at ${SubAgentStore.MAX_RESULT_BYTES}.",
                    )
                })
            },
            required = listOf("ref"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val ref = params["ref"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (ref.isEmpty()) {
            return@Tool subAgentError("invalid_ref", "ref is required")
        }
        val offset = params["offset_bytes"]?.jsonPrimitive?.intOrNull ?: 0
        val limit = params["limit_bytes"]?.jsonPrimitive?.intOrNull
            ?: SubAgentStore.DEFAULT_RESULT_BYTES
        try {
            val result = store.readResult(
                ref = ref,
                parentConversationId = conversationId.toString(),
                offsetBytes = offset,
                limitBytes = limit,
            )
            val end = result.offsetBytes + result.text.toByteArray(Charsets.UTF_8).size
            val body = buildString {
                append(
                    "Sub-agent result ${result.ref} (status=${result.status.name.lowercase()}, " +
                        "bytes ${result.offsetBytes}-$end of ${result.totalBytes}):\n"
                )
                append(result.text)
                val next = result.nextOffsetBytes
                if (next != null) {
                    append("\n\nMore remains. Call read_subagent_result with ref=")
                    append(result.ref)
                    append(" and offset_bytes=")
                    append(next)
                    append(".")
                } else {
                    append("\n\nEnd of sub-agent result.")
                }
            }
            listOf(UIMessagePart.Text(body))
        } catch (e: Exception) {
            subAgentError("read_failed", e.message ?: e::class.simpleName.orEmpty())
        }
    },
)

/**
 * 子代理的终局报告工具：调用后本轮立即结束，报告正文由 SubAgentRunner 提取。
 */
fun buildCompleteSubtaskTool(): Tool = Tool(
    name = "complete_subtask",
    description = "Submit the final report for this sub-agent task and end the run. " +
        "Call this once as your last action. The report is what the parent assistant receives.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("report", buildJsonObject {
                    put("type", "string")
                    put("description", "The complete final report, preferably Markdown.")
                })
            },
            required = listOf("report"),
        )
    },
    terminal = true,
    execute = { args ->
        val report = args.jsonObject["report"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(report.isNotEmpty()) { "report is required" }
        listOf(UIMessagePart.Text("Report submitted (${report.length} chars). The run will end."))
    },
)

private fun subAgentError(error: String, detail: String): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("status", "failed")
                put("error", error)
                put("detail", detail)
            }.toString()
        )
    )
