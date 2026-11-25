package me.rerere.rikkahub.data.ai.tools.dynamic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

/** 两个入口工具的名字；模型侧与提示词里都用这两个 */
internal const val SEARCH_TOOLS_NAME = "search_tools"
internal const val CALL_TOOL_NAME = "call_tool"

/**
 * 构造动态工具模式下的两个入口工具。
 *
 * 设计取自 Open WebUI 的 `search_tools` + `call_tool`：
 * 检索与调用分开，调用时**把批量数组拆回单个工具调用**，让每个目标工具保留自己的审批语义。
 */
internal fun createDynamicToolEntries(
    dynamic: DynamicTools,
    /** 「设置 → 工具提示词」里的覆盖；null 表示用内置默认 */
    overlays: PromptOverlays = PromptOverlays.NONE,
): List<Tool> = listOf(
    createSearchToolsTool(dynamic, overlays),
    createCallTool(dynamic, overlays),
)

private fun createSearchToolsTool(dynamic: DynamicTools, overlays: PromptOverlays): Tool = Tool(
    name = SEARCH_TOOLS_NAME,
    // 提示词要写清触发条件：模型只有在知道自己可能缺工具时才会来搜
    description = overlays.description(SEARCH_TOOLS_NAME) ?: """
        Discover additional tools that are not listed in this session.
        Use this when:
        - the user asks for something that the current tools cannot do
        - the task involves files, shell commands, external services, or MCP
        - the request is vague and you need to know what is available before answering
        Pass an exact tool name if you already know it; otherwise pass short keywords.
        Results must be invoked through $CALL_TOOL_NAME.
        If an MCP tool the user named or you need cannot be connected, tell the user it cannot be connected.
    """.trimIndent(),
    systemPrompt = overlays.systemPrompt(SEARCH_TOOLS_NAME)?.let { custom ->
        { _, _ -> custom }
    } ?: { _, _ -> "" },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Short keywords describing the capability you need, or an exact tool name.")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of results (default 5, max ${Bm25.MAX_RESULTS}).")
                })
            },
            required = listOf("query"),
        )
    },
    execute = { input ->
        val args = input.jsonObject
        val query = args["query"]?.jsonPrimitive?.content.orEmpty()
        val count = args["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: Bm25.DEFAULT_RESULTS
        val candidates = dynamic.entries.filter { it.id in dynamic.deferredIds }
        val hits = Bm25.search(candidates, query, count)
        listOf(UIMessagePart.Text(renderSearchResult(hits, dynamic.deferredIds.size)))
    },
)

/**
 * 检索结果：命中工具的定义摘要 + 调用方式。
 *
 * 不返回完整 `inputSchema`：模型需要它才能填参数，所以参数名与必填项要给出，但把整段 JSON Schema
 * 塞回去等于又付了一次体积成本。这里给「参数名 + 是否必填」，配合 `call_tool` 的宽松校验即可。
 */
private fun renderSearchResult(hits: List<CapabilityEntry>, totalDeferred: Int): String {
    if (hits.isEmpty()) {
        return buildJsonObject {
            put("tools", JsonArray(emptyList()))
            put("message", JsonPrimitive("No matching tools found. Try different keywords or the exact tool name."))
        }.toString()
    }
    val tools = hits.map { entry ->
        buildJsonObject {
            put("name", JsonPrimitive(entry.id))
            put("description", JsonPrimitive(entry.description.replace('\n', ' ').trim().take(DESCRIPTION_CHARS)))
            entry.tool?.let { tool ->
                val schema = tool.parameters()
                if (schema is InputSchema.Obj) {
                    put(
                        "parameters",
                        buildJsonObject {
                            put("names", JsonArray(schema.properties.keys.map(::JsonPrimitive)))
                            put("required", JsonArray((schema.required ?: emptyList()).map(::JsonPrimitive)))
                        },
                    )
                }
            }
            put("status", JsonPrimitive(entry.status.name))
            entry.failureReason?.let { put("failure", JsonPrimitive(it.take(FAILURE_CHARS))) }
        }
    }
    return buildJsonObject {
        put("tools", JsonArray(tools))
        val omitted = totalDeferred - hits.size
        if (omitted > 0) put("omitted", JsonPrimitive(omitted))
        put("invoke_with", JsonPrimitive("$CALL_TOOL_NAME calls=[{tool_name, params}]"))
    }.toString()
}

private fun createCallTool(dynamic: DynamicTools, overlays: PromptOverlays): Tool = Tool(
    name = CALL_TOOL_NAME,
    description = overlays.description(CALL_TOOL_NAME) ?: """
        Invoke one or more tools found by $SEARCH_TOOLS_NAME.
        Each call uses the exact tool name and a JSON-encoded object string of arguments.
        Use "{}" for no arguments. Calls keep individual approval and results; execution is not atomic.
    """.trimIndent(),
    // 审批按目标工具逐个判断：批量调用不能整体放行，否则等于绕过每个工具的审批设置
    needsApproval = { input -> dynamic.hasPendingApproval(input) },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("calls", buildJsonObject {
                    put("type", "array")
                    put("description", "One or more calls; independent calls can be batched.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("tool_name", buildJsonObject {
                                put("type", "string")
                                put("description", "Exact name from $SEARCH_TOOLS_NAME.")
                            })
                            put("params", buildJsonObject {
                                put("type", "string")
                                put("description", "JSON-encoded object of arguments, use \"{}\" when none.")
                            })
                        })
                        put("required", JsonArray(listOf(JsonPrimitive("tool_name"), JsonPrimitive("params"))))
                    })
                })
            },
            required = listOf("calls"),
        )
    },
    execute = { input ->
        executeCalls(input, dynamic)
    },
)

private suspend fun executeCalls(input: JsonElement, dynamic: DynamicTools): List<UIMessagePart> {
    val calls = (input.jsonObject["calls"] as? JsonArray).orEmpty()
    if (calls.isEmpty()) {
        return listOf(UIMessagePart.Text("call_tool requires a non-empty calls array."))
    }
    val results = ArrayList<UIMessagePart>()
    for (call in calls) {
        val obj = call as? JsonObject ?: continue
        val toolName = obj["tool_name"]?.jsonPrimitive?.content
        val rawParams = obj["params"]?.jsonPrimitive?.content ?: "{}"
        if (toolName.isNullOrBlank()) {
            results += UIMessagePart.Text("call_tool: missing tool_name")
            continue
        }
        val entry = dynamic.findEntry(toolName)
        if (entry == null) {
            results += UIMessagePart.Text("call_tool: '$toolName' is not an available capability")
            continue
        }
        val tool = entry.tool
        if (tool == null) {
            // 技能没有可执行本体：模型应改用 view_skill
            results += UIMessagePart.Text(
                "call_tool: '$toolName' is a skill, not an executable tool. Call view_skill with that name."
            )
            continue
        }
        // 必须是 JSON 对象：宽松解析会把裸字符串当成合法值，导致工具收到错误类型的参数
        val params = runCatching { JsonInstant.parseToJsonElement(rawParams) }.getOrNull()
        if (params !is JsonObject) {
            results += UIMessagePart.Text(
                "call_tool: params for '$toolName' must be a JSON object like {\"arg\": \"value\"}"
            )
            continue
        }
        // 单个调用失败不影响其余调用（Open WebUI 的语义：execution is not atomic）
        val outcome = runCatching { tool.execute(params) }
        outcome.fold(
            onSuccess = { results += it },
            onFailure = { error ->
                results +=
                    UIMessagePart.Text("call_tool: '$toolName' failed: ${error.message ?: error.javaClass.simpleName}")
            },
        )
    }
    return results
}

private const val DESCRIPTION_CHARS = 500
private const val FAILURE_CHARS = 200
