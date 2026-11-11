package me.rerere.rikkahub.data.sync.importer

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.Reader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.uuid.Uuid
import java.time.Instant as JavaInstant
import kotlin.time.Instant as KotlinInstant

/**
 * Imports the `conversations.json` inside the archive produced by DeepSeek's official
 * *Settings → Data → Export data* flow (the ZIP also carries a `user.json`, which is ignored
 * because it only holds account identity).
 *
 * The export is a flat JSON array of conversations. Each conversation carries a `mapping`
 * (node key -> node) describing a message tree, similar to the ChatGPT export:
 *
 * ```
 * node    = { id, parent, children[], message|null }
 * message = { model, inserted_at, fragments[] }
 * ```
 *
 * `parent` / `children` reference the mapping **key**, which is not always equal to the node's
 * own `id` field — every lookup here therefore indexes by key.
 *
 * Fragment types map onto app concepts as follows:
 *
 * | fragment | 归属 | 转换结果 |
 * |---|---|---|
 * | `REQUEST` | 用户 | `UIMessagePart.Text` |
 * | `FILE` | 用户 | `UIMessagePart.Text`（导出包不含文件实体，仅保留提示行） |
 * | `RESPONSE` | 助手 | `UIMessagePart.Text`，其中的 `[citation:N]` 改写成可点击的引用角标 |
 * | `THINK` | 助手 | `UIMessagePart.Reasoning`（App 内渲染为「思考」块） |
 * | `SEARCH` / `TOOL_SEARCH` | 助手 | `UIMessagePart.Tool("search_web")`，结果列表可在 App 内打开 |
 * | `TEMPLATE_RESPONSE` | 助手 | `UIMessagePart.Text`（导出里它取代了回答本身，如「服务器繁忙」） |
 * | `TIP` / `TOOL_OPEN` / `TOOL_FIND` | 助手 | 丢弃并计数：导出没有留下可还原的内容 |
 *
 * 由于 `REQUEST`/`FILE` 只会出现在用户轮次，`RESPONSE`/`THINK`/`SEARCH`/… 只会出现在助手轮次，
 * 消息角色可以直接由片段类型推导，无需依赖节点深度。
 *
 * 导出不标记「当前分支」，因此主链按 `children` 的书写顺序取第一条；其余分支不会被静默丢弃，
 * 而是按链展开后追加到会话末尾（`branchMessages` 统计）。同一条提问下的多条助手回复
 * （用户点过「重新生成」）会合并成一个 `MessageNode` 的候选列表，可在 App 内左右切换。
 *
 * 导出包只有 `conversations.json` / `user.json`，没有任何二进制资源，因此附件一律降级为文本。
 */
object DeepSeekImporter {
    private const val CONVERSATIONS_PATH = "conversations.json"

    /** 实测一份导出 1946 个会话、解压后约 42 MB；留足余量但避免无上限读取。 */
    private const val MAX_JSON_ENTRY_SIZE = 512L * 1024 * 1024

    private const val SEARCH_TOOL_NAME = "search_web"

    private const val FRAGMENT_REQUEST = "REQUEST"
    private const val FRAGMENT_RESPONSE = "RESPONSE"
    private const val FRAGMENT_THINK = "THINK"
    private const val FRAGMENT_FILE = "FILE"
    private const val FRAGMENT_SEARCH = "SEARCH"
    private const val FRAGMENT_TOOL_SEARCH = "TOOL_SEARCH"
    private const val FRAGMENT_TOOL_FIND = "TOOL_FIND"
    private const val FRAGMENT_TOOL_OPEN = "TOOL_OPEN"
    private const val FRAGMENT_TEMPLATE_RESPONSE = "TEMPLATE_RESPONSE"
    private const val FRAGMENT_TIP = "TIP"

    private val USER_FRAGMENT_TYPES = setOf(FRAGMENT_REQUEST, FRAGMENT_FILE)
    private val ASSISTANT_FRAGMENT_TYPES = setOf(
        FRAGMENT_RESPONSE,
        FRAGMENT_THINK,
        FRAGMENT_SEARCH,
        FRAGMENT_TOOL_SEARCH,
        FRAGMENT_TOOL_FIND,
        FRAGMENT_TOOL_OPEN,
        FRAGMENT_TEMPLATE_RESPONSE,
        FRAGMENT_TIP,
    )
    private val SEARCH_FRAGMENT_TYPES = setOf(FRAGMENT_SEARCH, FRAGMENT_TOOL_SEARCH)

    /** Matches the citation marks DeepSeek leaves in answers, e.g. `[citation:3]`. */
    private val CITATION_MARK = Regex("""\[citation:(\d+)]""")

    /**
     * 只读取每个对话的 id / 标题 / 更新时间，用来在导入前判断哪些对话需要用户决定。
     * 不解析 `mapping`，因此比完整导入快很多，也不会把会话内容留在内存里。
     */
    suspend fun scan(file: File): List<DeepSeekConversationSummary> {
        val summaries = mutableListOf<DeepSeekConversationSummary>()
        readConversations(file) { raw ->
            val rawId = raw["id"].ownString()?.takeIf { it.isNotBlank() } ?: return@readConversations
            summaries += DeepSeekConversationSummary(
                conversationId = stableUuid("deepseek:conversation:$rawId"),
                rawId = rawId,
                title = titleOf(raw = raw, rawId = rawId),
                updatedAt = raw["updated_at"].asEpochMillis(),
            )
        }
        return summaries
    }

    /**
     * @param decisions 已存在对话的处理方式；没有决定的对话视为「保留」
     * @param existingIds 导入前扫描出的、本地已存在的对话 id；不在其中的按新对话导入
     * @param onConversation 每写入一个对话回调一次，[DeepSeekImportAction] 说明是新增、覆盖还是并存
     */
    suspend fun importStreaming(
        file: File,
        assistantId: Uuid,
        decisions: Map<Uuid, DeepSeekImportDecision> = emptyMap(),
        existingIds: Set<Uuid> = emptySet(),
        onConversation: suspend (conversation: Conversation, action: DeepSeekImportAction) -> Unit,
    ): DeepSeekImportResult {
        val counters = ImportCounters()

        readConversations(file) { raw ->
            val rawId = raw["id"].ownString()?.takeIf { it.isNotBlank() }
            if (rawId == null) {
                counters.skippedConversations++
                return@readConversations
            }

            val title = titleOf(raw = raw, rawId = rawId)
            val conversationId = stableUuid("deepseek:conversation:$rawId")
            val action = when {
                conversationId !in existingIds -> DeepSeekImportAction.NEW
                decisions[conversationId] == DeepSeekImportDecision.OVERWRITE -> DeepSeekImportAction.OVERWRITE
                decisions[conversationId] == DeepSeekImportDecision.KEEP_BOTH -> DeepSeekImportAction.KEEP_BOTH
                else -> {
                    counters.keptExistingConversations++
                    return@readConversations
                }
            }

            val parsed = parseConversation(
                raw = raw,
                rawId = rawId,
                title = title,
                conversationId = conversationId,
                assistantId = assistantId,
            )
            if (parsed == null) {
                counters.skippedConversations++
                return@readConversations
            }

            counters.add(parsed.stats)
            counters.parsedConversations++
            when (action) {
                DeepSeekImportAction.NEW -> counters.newConversations++
                DeepSeekImportAction.OVERWRITE -> counters.overwrittenConversations++
                DeepSeekImportAction.KEEP_BOTH -> counters.copiedConversations++
            }
            onConversation(parsed.conversation.withImportAction(action), action)
        }

        return counters.toResult()
    }

    /** 并存时换一个稳定的新 id，避免覆盖原对话；副本不参与后续导入的匹配。 */
    private fun Conversation.withImportAction(action: DeepSeekImportAction): Conversation {
        if (action != DeepSeekImportAction.KEEP_BOTH) return this
        val date = updateAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val stamp = sourceUpdatedAt ?: updateAt.toEpochMilli()
        return copy(
            id = stableUuid("deepseek:conversation:$id#copy:$stamp"),
            title = "$title (DeepSeek $date)",
            sourceUpdatedAt = sourceUpdatedAt,
        )
    }

    private suspend fun readConversations(file: File, onConversation: suspend (JsonObject) -> Unit) {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(CONVERSATIONS_PATH)
                ?: error("Invalid DeepSeek export: $CONVERSATIONS_PATH not found")
            require(!entry.isDirectory && entry.size in 0..MAX_JSON_ENTRY_SIZE) {
                "Invalid DeepSeek export: $CONVERSATIONS_PATH has an unexpected size"
            }

            zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                readConversationArray(reader) { element ->
                    val raw = element as? JsonObject ?: return@readConversationArray
                    onConversation(raw)
                }
            }
        }
    }

    private fun titleOf(raw: JsonObject, rawId: String): String =
        raw["title"].ownString()?.takeIf { it.isNotBlank() } ?: rawId

    private fun parseConversation(
        raw: JsonObject,
        rawId: String,
        title: String,
        conversationId: Uuid,
        assistantId: Uuid,
    ): ParsedConversation? {
        val mapping = raw["mapping"] as? JsonObject ?: return null
        val nodes = mapping.mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { key to parseNode(key, it) }
        }.toMap()
        if (nodes.isEmpty()) return null

        val rootKeys = nodes.values
            .filter { it.parentId == null || it.parentId !in nodes }
            .map { it.key }
            .sorted()
        if (rootKeys.isEmpty()) return null

        val order = traversalOrder(nodes, rootKeys)
        val stats = ImportStats()
        val timestamps = nodes.values.mapNotNull { it.insertedAt }
        val createMillis = raw["inserted_at"].asEpochMillis()
            ?: timestamps.minOrNull()
            ?: System.currentTimeMillis()

        val parsedByNode = nodes.values.mapNotNull { node ->
            parseMessage(
                node = node,
                conversationId = rawId,
                fallbackMillis = createMillis,
                userPrompt = userPromptOf(node = node, nodes = nodes),
                stats = stats,
            )?.let { node.key to it }
        }.toMap()
        if (parsedByNode.isEmpty()) return null

        // 同一条提问下有多条助手回复 → 用户点过「重新生成」。这些回复是同一轮回答的不同版本，
        // 合并成一个只装回答的节点的候选列表；提问本身仍单独成节点。
        val alternativeReplies = nodes.values.associate { node ->
            node.key to node.childIds
                .mapNotNull(nodes::get)
                .takeIf { children -> children.size > 1 && children.all { it.role == MessageRole.ASSISTANT } }
                .orEmpty()
                .sortedBy { order[it.key] ?: Int.MAX_VALUE }
        }.filterValues { it.isNotEmpty() }

        val consumed = alternativeReplies.values.flatten().map { it.key }.toSet()
        val rootNode = nodes.getValue(rootKeys.first())
        // 官方导出的根节点没有 message；万一有，也把它当作主链起点，避免丢掉它自己的内容
        val mainStart = if (rootNode.fragments.isNotEmpty()) {
            rootNode
        } else {
            rootNode.childIds.firstOrNull(nodes::containsKey)?.let(nodes::get) ?: rootNode
        }
        val mainline = walkChain(start = mainStart, nodes = nodes, order = order, excluded = consumed)
        val visited = (mainline.map { it.key } + consumed).toMutableSet()

        val messageNodes = arrayListOf<MessageNode>()
        mainline.forEach { node ->
            parsedByNode[node.key]?.let { primary ->
                messageNodes += MessageNode(
                    id = stableUuid("deepseek:node:$rawId:${node.key}"),
                    messages = listOf(primary),
                    selectIndex = 0,
                )
            }

            val replies = alternativeReplies[node.key]
                .orEmpty()
                .mapNotNull { parsedByNode[it.key] }
            if (replies.isNotEmpty()) {
                stats.alternativeReplies += replies.size - 1
                messageNodes += MessageNode(
                    id = stableUuid("deepseek:node:$rawId:${node.key}:replies"),
                    messages = replies,
                    selectIndex = 0,
                )
            }
        }

        // 编辑首条提问、或某一轮之后另起的分支：主链之外的所有节点都被展开成尾巴，避免静默丢消息。
        while (true) {
            val start = nodes.values
                .filter { it.key !in visited }
                .filter { node -> node.parentId == null || node.parentId !in nodes || node.parentId in visited }
                .minByOrNull { order[it.key] ?: Int.MAX_VALUE }
                ?: break

            val chain = walkChain(start = start, nodes = nodes, order = order, excluded = visited)
            if (chain.isEmpty()) {
                visited += start.key
                continue
            }
            chain.forEach { node ->
                visited += node.key
                parsedByNode[node.key]?.let { message ->
                    stats.branchMessages++
                    messageNodes += MessageNode(
                        id = stableUuid("deepseek:branch:$rawId:${node.key}"),
                        messages = listOf(message),
                        selectIndex = 0,
                    )
                }
            }
        }

        if (messageNodes.isEmpty()) return null

        val updateMillis = raw["updated_at"].asEpochMillis()
            ?: timestamps.maxOrNull()
            ?: createMillis

        return ParsedConversation(
            conversation = Conversation(
                id = conversationId,
                assistantId = assistantId,
                title = title,
                messageNodes = messageNodes.distinctBy { it.id },
                createAt = JavaInstant.ofEpochMilli(createMillis),
                updateAt = JavaInstant.ofEpochMilli(updateMillis),
                sourceUpdatedAt = updateMillis,
            ),
            stats = stats,
        )
    }

    /** 沿单子节点链收集节点（含起点）；[excluded] 里的节点不再往下走。 */
    private fun walkChain(
        start: ExportNode,
        nodes: Map<String, ExportNode>,
        order: Map<String, Int>,
        excluded: Set<String>,
    ): List<ExportNode> {
        val chain = mutableListOf<ExportNode>()
        val seen = mutableSetOf<String>()
        var cursor: ExportNode? = start
        while (cursor != null && seen.add(cursor.key)) {
            chain += cursor
            cursor = cursor.childIds
                .filter(nodes::containsKey)
                .filterNot(excluded::contains)
                .minByOrNull { order[it] ?: Int.MAX_VALUE }
                ?.let(nodes::get)
        }
        return chain
    }

    /** 遍历顺序：与导出文件里 `children` 的书写顺序一致，供候选排序与分支选择使用。 */
    private fun traversalOrder(nodes: Map<String, ExportNode>, rootKeys: List<String>): Map<String, Int> {
        val order = mutableMapOf<String, Int>()
        var cursor = 0
        val stack = rootKeys.asReversed().toMutableList()
        val visited = mutableSetOf<String>()
        while (stack.isNotEmpty()) {
            val key = stack.removeAt(stack.lastIndex)
            if (!visited.add(key)) continue
            val node = nodes[key] ?: continue
            order[key] = cursor++
            node.childIds.asReversed().forEach { child ->
                if (nodes.containsKey(child)) stack += child
            }
        }
        // 孤立节点（父节点缺失却没被判成根）兜底排序
        nodes.keys.filterNot(visited::contains).forEach { order[it] = cursor++ }
        return order
    }

    /** 本轮提问：助手消息自己的 fragments 里没有 REQUEST，要沿 parent 找到最近的一条。 */
    private fun userPromptOf(node: ExportNode, nodes: Map<String, ExportNode>): String {
        val seen = mutableSetOf<String>()
        var cursor: ExportNode? = node
        while (cursor != null && seen.add(cursor.key)) {
            cursor.fragments.firstOrNull { it.type == FRAGMENT_REQUEST }?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            cursor = cursor.parentId?.let(nodes::get)
        }
        return ""
    }

    private fun parseMessage(
        node: ExportNode,
        conversationId: String,
        fallbackMillis: Long,
        userPrompt: String,
        stats: ImportStats,
    ): UIMessage? {
        // 结构节点（例如没有 message 的 root）不算空消息
        if (node.fragments.isEmpty()) return null

        val userFragments = node.fragments.filter { it.type in USER_FRAGMENT_TYPES }
        val assistantFragments = node.fragments.filter { it.type in ASSISTANT_FRAGMENT_TYPES }
        if (userFragments.isEmpty() && assistantFragments.isEmpty()) {
            stats.emptyMessages++
            return null
        }

        val role = if (assistantFragments.isNotEmpty()) MessageRole.ASSISTANT else MessageRole.USER
        val parts = mutableListOf<UIMessagePart>()

        userFragments.forEach { fragment ->
            when (fragment.type) {
                FRAGMENT_REQUEST -> fragment.content
                    ?.takeIf { it.isNotBlank() }
                    ?.let { parts += UIMessagePart.Text(it) }

                FRAGMENT_FILE -> fragment.files.forEach { attachment ->
                    parts += UIMessagePart.Text(attachmentNotice(attachment))
                    stats.attachmentParts++
                }
            }
        }

        val results = assistantFragments
            .filter { it.type in SEARCH_FRAGMENT_TYPES }
            .flatMap { it.results }
        val query = userPrompt

        val reasoningParts = arrayListOf<UIMessagePart>()
        val bodyParts = arrayListOf<UIMessagePart>()
        assistantFragments.forEach { fragment ->
            when (fragment.type) {
                FRAGMENT_THINK -> fragment.content
                    ?.takeIf { it.isNotBlank() }
                    ?.let { content ->
                        reasoningParts += UIMessagePart.Reasoning(reasoning = content)
                        stats.reasoningParts++
                    }

                FRAGMENT_RESPONSE, FRAGMENT_TEMPLATE_RESPONSE -> fragment.content
                    ?.takeIf { it.isNotBlank() }
                    ?.let { bodyParts += UIMessagePart.Text(withCitationLinks(it, results)) }

                FRAGMENT_SEARCH, FRAGMENT_TOOL_SEARCH -> Unit // 统一在下面生成一次工具调用

                FRAGMENT_TIP -> stats.skippedTipParts++

                FRAGMENT_TOOL_OPEN, FRAGMENT_TOOL_FIND -> stats.skippedToolParts++

                else -> stats.unknownFragments++
            }
        }

        val orderedParts = buildList {
            addAll(reasoningParts)
            addAll(parts)
            if (results.isNotEmpty()) {
                add(searchToolPart(node = node, conversationId = conversationId, query = query, results = results))
                stats.searchParts++
            }
            addAll(bodyParts)
        }
        if (orderedParts.isEmpty()) {
            stats.emptyMessages++
            return null
        }

        val createdAt = KotlinInstant
            .fromEpochMilliseconds(node.insertedAt ?: fallbackMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        return UIMessage(
            id = stableUuid("deepseek:message:$conversationId:${node.key}"),
            role = role,
            parts = orderedParts,
            createdAt = createdAt,
        )
    }

    /**
     * 联网搜索片段转换为 `search_web` 工具调用，结果写成 `{items:[{id,index,title,url,text}]}`，
     * 与 App 内搜索结果卡片（含引用跳转）使用同一份数据结构。导出的结果没有摘要，`text` 留空。
     */
    private fun searchToolPart(
        node: ExportNode,
        conversationId: String,
        query: String,
        results: List<ExportSearchResult>,
    ): UIMessagePart.Tool {
        val output = buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    results.forEachIndexed { index, result ->
                        add(
                            buildJsonObject {
                                put("id", index + 1)
                                put("index", index + 1)
                                put("title", result.title)
                                put("url", result.url)
                                put("text", "")
                            }
                        )
                    }
                },
            )
        }
        return UIMessagePart.Tool(
            toolCallId = stableUuid("deepseek:search:$conversationId:${node.key}").toString(),
            toolName = SEARCH_TOOL_NAME,
            input = buildJsonObject { put("query", query) }.toString(),
            output = listOf(UIMessagePart.Text(output.toString())),
        )
    }

    /**
     * 把正文里的 `[citation:N]` 改写成 App 能识别、可点击跳转的 `[citation,域名](N)`。
     * N 超出该消息的结果数量时保持原样（导出本身可能带着对不上号的标记）。
     */
    private fun withCitationLinks(content: String, results: List<ExportSearchResult>): String {
        if (results.isEmpty() || !content.contains("[citation:")) return content
        return CITATION_MARK.replace(content) { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val result = results.getOrNull(index - 1) ?: return@replace match.value
            "[citation,${hostOf(result.url)}]($index)"
        }
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }
        .getOrDefault("")
        .ifBlank { "source" }

    private fun attachmentNotice(attachment: ExportAttachment): String {
        val name = attachment.fileName?.takeIf { it.isNotBlank() }
            ?: attachment.fileId?.takeIf { it.isNotBlank() }
            ?: "unnamed file"
        val size = attachment.size
            ?.takeIf { it > 0 }
            ?.let { ", %.1f KB".format(it / 1024.0) }
            .orEmpty()
        return "[Attachment] $name$size (the DeepSeek export does not include the file itself)"
    }

    private fun parseNode(key: String, node: JsonObject): ExportNode {
        val message = node["message"] as? JsonObject
        val fragments = message?.get("fragments").asFragmentList()
        return ExportNode(
            key = key,
            parentId = node["parent"].ownString(),
            childIds = node["children"].asStringArray(),
            fragments = fragments,
            insertedAt = message?.get("inserted_at").asEpochMillis(),
            role = if (fragments.any { it.type in ASSISTANT_FRAGMENT_TYPES }) {
                MessageRole.ASSISTANT
            } else {
                MessageRole.USER
            },
        )
    }

    /**
     * 逐元素读取顶层 JSON 数组，避免把整份导出（实测解压后 42 MB）一次性铺成对象树。
     * 每个元素解析完立即交给 [onElement]，内存占用只与单个会话的大小有关。
     */
    private suspend fun readConversationArray(reader: Reader, onElement: suspend (JsonElement) -> Unit) {
        val buffer = CharArray(64 * 1024)
        val element = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        var started = false
        var finished = false

        suspend fun flush() {
            val text = element.toString()
            element.setLength(0)
            if (text.isBlank()) return
            // 只把解析包进 try：回调里的取消信号不能被吞掉
            val parsed = try {
                JsonInstant.parseToJsonElement(text)
            } catch (_: Exception) {
                null
            }
            onElement(parsed ?: JsonNull) // null 交给调用方计为不可解析
        }

        while (!finished) {
            val read = reader.read(buffer)
            if (read == -1) break
            for (i in 0 until read) {
                val char = buffer[i]
                if (!started) {
                    when {
                        char == '[' -> started = true
                        char == '\uFEFF' || char.isWhitespace() -> Unit
                        else -> throw IllegalArgumentException(
                            "Invalid DeepSeek export: $CONVERSATIONS_PATH must be a JSON array"
                        )
                    }
                    continue
                }
                if (finished) {
                    require(char == '\uFEFF' || char.isWhitespace()) {
                        "Invalid DeepSeek export: unexpected content after $CONVERSATIONS_PATH"
                    }
                    continue
                }
                if (inString) {
                    element.append(char)
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                    continue
                }
                when (char) {
                    '"' -> {
                        inString = true
                        element.append(char)
                    }

                    '{', '[' -> {
                        depth++
                        element.append(char)
                    }

                    '}', ']' -> {
                        if (depth == 0) {
                            // 顶层数组的收尾；`]` 之前可能还有一个完整元素
                            if (element.isNotBlank()) flush()
                            finished = true
                        } else {
                            depth--
                            element.append(char)
                        }
                    }

                    ',' -> if (depth == 0) flush() else element.append(char)

                    else -> element.append(char)
                }
            }
        }

        require(started && finished) {
            "Invalid DeepSeek export: truncated JSON array in $CONVERSATIONS_PATH"
        }
        // 单个元素解析失败时上面已经以 JsonPrimitive 形式交出去了，这里不需要再判断
    }

    private fun stableUuid(value: String): Uuid =
        Uuid.parse(UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString())

    private fun JsonElement?.ownString(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.asStringArray(): List<String> =
        (this as? JsonArray).orEmpty().mapNotNull { it.ownString() }

    /** 导出里的时间是带偏移的 RFC3339 字符串；同时容忍秒/毫秒时间戳，方便手工处理过的文件。 */
    private fun JsonElement?.asEpochMillis(): Long? {
        val text = ownString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { raw -> return if (raw < 100_000_000_000L) raw * 1000 else raw }
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching { JavaInstant.parse(text).toEpochMilli() }
            .getOrNull()
    }

    private fun JsonElement?.asFragmentList(): List<ExportFragment> =
        (this as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toExportFragment() }

    private fun JsonObject.toExportFragment(): ExportFragment? {
        val type = this["type"].ownString()?.takeIf { it.isNotBlank() } ?: return null
        return ExportFragment(
            type = type,
            content = this["content"].ownString(),
            results = (this["results"] as? JsonArray).orEmpty().mapNotNull { element ->
                val result = element as? JsonObject ?: return@mapNotNull null
                ExportSearchResult(
                    title = result["title"].ownString().orEmpty(),
                    url = result["url"].ownString().orEmpty(),
                )
            },
            files = (this["files"] as? JsonArray).orEmpty().mapNotNull { element ->
                val file = element as? JsonObject ?: return@mapNotNull null
                ExportAttachment(
                    fileId = file["file_id"].ownString(),
                    fileName = file["file_name"].ownString(),
                    size = file["file_size"].ownString()?.toLongOrNull(),
                )
            },
        )
    }

    private data class ExportNode(
        val key: String,
        val parentId: String?,
        val childIds: List<String>,
        val fragments: List<ExportFragment>,
        val insertedAt: Long?,
        val role: MessageRole,
    )

    private data class ExportFragment(
        val type: String,
        val content: String?,
        val results: List<ExportSearchResult>,
        val files: List<ExportAttachment>,
    )

    private data class ExportSearchResult(val title: String, val url: String)

    private data class ExportAttachment(
        val fileId: String?,
        val fileName: String?,
        val size: Long?,
    )

    private data class ParsedConversation(
        val conversation: Conversation,
        val stats: ImportStats,
    )

    private class ImportCounters {
        var parsedConversations = 0
        var skippedConversations = 0
        var newConversations = 0
        var overwrittenConversations = 0
        var copiedConversations = 0
        var keptExistingConversations = 0
        var reasoningParts = 0
        var searchParts = 0
        var attachmentParts = 0
        var emptyMessages = 0
        var branchMessages = 0
        var alternativeReplies = 0
        var skippedTipParts = 0
        var skippedToolParts = 0
        var unknownFragments = 0

        fun add(stats: ImportStats) {
            reasoningParts += stats.reasoningParts
            searchParts += stats.searchParts
            attachmentParts += stats.attachmentParts
            emptyMessages += stats.emptyMessages
            branchMessages += stats.branchMessages
            alternativeReplies += stats.alternativeReplies
            skippedTipParts += stats.skippedTipParts
            skippedToolParts += stats.skippedToolParts
            unknownFragments += stats.unknownFragments
        }

        fun toResult() = DeepSeekImportResult(
            parsedConversations = parsedConversations,
            skippedConversations = skippedConversations,
            newConversations = newConversations,
            overwrittenConversations = overwrittenConversations,
            copiedConversations = copiedConversations,
            keptExistingConversations = keptExistingConversations,
            reasoningParts = reasoningParts,
            searchParts = searchParts,
            attachmentParts = attachmentParts,
            emptyMessages = emptyMessages,
            branchMessages = branchMessages,
            alternativeReplies = alternativeReplies,
            skippedTipParts = skippedTipParts,
            skippedToolParts = skippedToolParts,
            unknownFragments = unknownFragments,
        )
    }

    private class ImportStats {
        var reasoningParts = 0
        var searchParts = 0
        var attachmentParts = 0
        var emptyMessages = 0
        var branchMessages = 0
        var alternativeReplies = 0
        var skippedTipParts = 0
        var skippedToolParts = 0
        var unknownFragments = 0
    }
}

data class DeepSeekImportResult(
    val parsedConversations: Int,
    val skippedConversations: Int,
    val newConversations: Int,
    val overwrittenConversations: Int,
    val copiedConversations: Int,
    val keptExistingConversations: Int,
    val reasoningParts: Int,
    val searchParts: Int,
    val attachmentParts: Int,
    val emptyMessages: Int,
    val branchMessages: Int,
    val alternativeReplies: Int,
    val skippedTipParts: Int,
    val skippedToolParts: Int,
    val unknownFragments: Int,
)

/** 扫描阶段得到的对话元信息，用来和本地记录比较是否需要用户决定。 */
data class DeepSeekConversationSummary(
    val conversationId: Uuid,
    val rawId: String,
    val title: String,
    val updatedAt: Long?,
)

/** 已存在对话的处理方式；没有决定的对话视为「保留」。 */
enum class DeepSeekImportDecision {
    KEEP,
    OVERWRITE,
    KEEP_BOTH,
}

/** 这次实际执行的动作，回调给调用方决定如何落库。 */
enum class DeepSeekImportAction {
    NEW,
    OVERWRITE,
    KEEP_BOTH,
}
