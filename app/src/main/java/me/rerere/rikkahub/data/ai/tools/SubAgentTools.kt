package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubAgentApprovalRegistry
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SubAgent
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
    settings: Settings,
    assistant: Assistant,
    parentModel: Model,
    baseTools: List<Tool>,
    conversationId: Uuid,
    approvalRegistry: SubAgentApprovalRegistry,
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
                },
                required = listOf("prompt"),
            )
        },
        needsApproval = { args ->
            val raw = runCatching {
                args.jsonObject["agent"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            val agent = findAgent(raw)
            agent != null &&
                agent.requiresApproval &&
                (!agent.rememberApproval || !approvalRegistry.isApproved(conversationId, agent.id))
        },
        execute = { args ->
            val params = args.jsonObject
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

            val result = runner.run(
                settings = settings,
                parentAssistant = assistant,
                parentModel = parentModel,
                subAgent = subAgent,
                task = prompt,
                baseTools = baseTools,
                conversationId = conversationId,
                maxStepsOverride = params["max_steps"]?.jsonPrimitive?.intOrNull,
                timeoutSecondsOverride = params["timeout_seconds"]?.jsonPrimitive?.intOrNull,
            )

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
 * 读取一个已完成/已取消子代理的完整结果，按 UTF-8 字节分页。
 *
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
