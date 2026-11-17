package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLConnection
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlin.time.Instant as KotlinInstant

/**
 * DeepSeek App 端的数据导出（设置 → 数据管理 → 导出数据）。
 *
 * 包内结构：
 * ```
 * manifest.json                 {"export_format_version":3}
 * user.json                     账号信息，忽略
 * chat_session_index.json       所有会话的摘要，扫描预览时优先读它
 * chat_sessions/<id>.json       单个会话，messages 通过 parent_id 组成分支树
 * chat_sessions/<id>_files/...  消息附件，由 FILE fragment 的 export_path 引用
 * ```
 *
 * 与网页版导出不同，App 导出有 `current_message_id`，能准确知道当前分支；同一条提问下的
 * 多条助手回复仍是兄弟节点（重新生成），会合并成 MessageNode 的候选列表。
 *
 * 这里保留了 fork 已有的预览/覆盖/保留/并存、稳定 id、分支展开和附件处理；预览阶段只读
 * 摘要，不会提前把附件写进上传目录。
 */
object DeepSeekImporter {
    private const val MANIFEST_PATH = "manifest.json"
    private const val INDEX_PATH = "chat_session_index.json"
    private const val SESSION_PREFIX = "chat_sessions/"
    private const val MAX_ENTRY_SIZE = 64L * 1024 * 1024
    private const val MIN_EXPORT_VERSION = 3

    suspend fun scan(file: File): List<DeepSeekConversationSummary> = withContext(Dispatchers.IO) {
        ZipFile(file).use { zip ->
            requireAppExport(zip)
            val index = zip.getEntry(INDEX_PATH)
            if (index != null) {
                readIndex(zip, index)
            } else {
                // 兼容没有 index 的包：只读每个会话的顶层元信息
                sessionEntries(zip).mapNotNull { entry ->
                    val session = readObject(zip, entry)
                    val rawId = session["id"].text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    DeepSeekConversationSummary(
                        conversationId = stableUuid("deepseek:conversation:$rawId"),
                        rawId = rawId,
                        title = session["title"].text?.takeIf { it.isNotBlank() } ?: rawId,
                        updatedAt = session["updated_at"].millis,
                    )
                }
            }
        }
    }

    /**
     * @param decisions 已存在对话的处理方式；没有决定的对话视为「保留」
     * @param existingIds 导入前扫描出的、本地已存在的对话 id
     * @param saveAttachment 保存附件并返回可直接放进 UIMessage 的 URI；返回 null 时保留占位文本
     * @param formatMissingAttachment 附件缺失时替代的提示文本
     */
    suspend fun importStreaming(
        file: File,
        assistantId: Uuid,
        decisions: Map<Uuid, DeepSeekImportDecision> = emptyMap(),
        existingIds: Set<Uuid> = emptySet(),
        saveAttachment: suspend (DeepSeekAttachment) -> String? = { null },
        formatMissingAttachment: (String) -> String = {
            "[Attachment missing from the export or unable to be imported: $it]"
        },
        onConversation: suspend (conversation: Conversation, action: DeepSeekImportAction) -> Unit,
    ): DeepSeekImportResult = withContext(Dispatchers.IO) {
        val counters = ImportCounters()
        ZipFile(file).use { zip ->
            requireAppExport(zip)
            val savedFiles = mutableMapOf<String, String?>()
            val entries = sessionEntries(zip)
            if (entries.isEmpty()) {
                throw DeepSeekAppExportException("No chat sessions found in the export")
            }

            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val session = runCatching { readObject(zip, entry) }.getOrNull()
                if (session == null) {
                    counters.skippedConversations++
                    continue
                }
                val rawId = session["id"].text?.takeIf { it.isNotBlank() }
                if (rawId == null) {
                    counters.skippedConversations++
                    continue
                }
                val conversationId = stableUuid("deepseek:conversation:$rawId")
                val action = when {
                    conversationId !in existingIds -> DeepSeekImportAction.NEW
                    decisions[conversationId] == DeepSeekImportDecision.OVERWRITE ->
                        DeepSeekImportAction.OVERWRITE

                    decisions[conversationId] == DeepSeekImportDecision.KEEP_BOTH ->
                        DeepSeekImportAction.KEEP_BOTH

                    else -> {
                        counters.keptExistingConversations++
                        continue
                    }
                }

                val parsed = parseSession(
                    zip = zip,
                    session = session,
                    rawId = rawId,
                    conversationId = conversationId,
                    assistantId = assistantId,
                    savedFiles = savedFiles,
                    saveAttachment = saveAttachment,
                    formatMissingAttachment = formatMissingAttachment,
                )
                if (parsed == null) {
                    counters.skippedConversations++
                    continue
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
        }
        counters.toResult()
    }

    private fun requireAppExport(zip: ZipFile) {
        val entry = zip.getEntry(MANIFEST_PATH)
            ?: throw DeepSeekAppExportException("manifest.json not found")
        val manifest = runCatching {
            JsonInstant.parseToJsonElement(readBytes(zip, entry).decodeToString()) as? JsonObject
        }.getOrNull() ?: throw DeepSeekAppExportException("Invalid manifest.json")
        val version = (manifest["export_format_version"] as? JsonPrimitive)?.intOrNull
        if (version == null || version < MIN_EXPORT_VERSION) {
            throw DeepSeekAppExportException(
                "Unsupported DeepSeek export format: expected app export v$MIN_EXPORT_VERSION or newer"
            )
        }
    }

    private fun readIndex(zip: ZipFile, entry: ZipEntry): List<DeepSeekConversationSummary> {
        val array = runCatching {
            JsonInstant.parseToJsonElement(readBytes(zip, entry).decodeToString()) as? JsonArray
        }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val rawId = item["id"].text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DeepSeekConversationSummary(
                conversationId = stableUuid("deepseek:conversation:$rawId"),
                rawId = rawId,
                title = item["title"].text?.takeIf { it.isNotBlank() } ?: rawId,
                updatedAt = item["updated_at"].millis,
            )
        }
    }

    private fun sessionEntries(zip: ZipFile): List<ZipEntry> = zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.startsWith(SESSION_PREFIX) }
        .filter { entry ->
            val relative = entry.name.removePrefix(SESSION_PREFIX)
            relative.endsWith(".json", ignoreCase = true) && '/' !in relative
        }
        .toList()

    private suspend fun parseSession(
        zip: ZipFile,
        session: JsonObject,
        rawId: String,
        conversationId: Uuid,
        assistantId: Uuid,
        savedFiles: MutableMap<String, String?>,
        saveAttachment: suspend (DeepSeekAttachment) -> String?,
        formatMissingAttachment: (String) -> String,
    ): ParsedConversation? {
        val rawMessages = session["messages"] as? JsonArray ?: return null
        val messages = LinkedHashMap<String, JsonObject>()
        rawMessages.forEach { element ->
            val message = element as? JsonObject ?: return@forEach
            val id = message["id"].text?.takeIf { it.isNotBlank() } ?: return@forEach
            if (messages.put(id, message) != null) return null
        }
        if (messages.isEmpty()) return null

        val parentOf = messages.mapValues { (_, message) -> message["parent_id"].text }
        val children = LinkedHashMap<String, MutableList<String>>()
        val order = messages.keys.withIndex().associate { (index, id) -> id to index }
        messages.keys.forEach { id ->
            val parent = parentOf[id]
            if (parent != null && parent in messages) {
                children.getOrPut(parent) { mutableListOf() }.add(id)
            }
        }
        val roots = messages.keys.filter { id -> parentOf[id]?.let { it !in messages } != false }
        if (roots.isEmpty()) return null

        val currentId = session["current_message_id"].text?.takeIf { it in messages }
        val mainChain = (currentId?.let { ancestry(it, parentOf) }
            ?: chainFrom(roots.first(), children, emptySet()))
            .filter { it in messages }
        if (mainChain.isEmpty()) return null

        val stats = ImportStats()
        val createdAt = session["inserted_at"].millis
            ?: messages.values.mapNotNull { it["inserted_at"].millis }.minOrNull()
            ?: System.currentTimeMillis()
        val updatedAt = session["updated_at"].millis
            ?: messages.values.mapNotNull { it["inserted_at"].millis }.maxOrNull()
            ?: createdAt

        val visited = mutableSetOf<String>()
        val messageNodes = mutableListOf<MessageNode>()

        suspend fun parse(id: String): UIMessage? = parseMessage(
            zip = zip,
            sessionId = rawId,
            message = messages[id] ?: return null,
            fallbackMillis = createdAt,
            savedFiles = savedFiles,
            saveAttachment = saveAttachment,
            formatMissingAttachment = formatMissingAttachment,
            stats = stats,
        )

        for (id in mainChain) {
            currentCoroutineContext().ensureActive()
            if (id in visited) continue
            val message = messages[id] ?: continue
            val role = message.role
            if (role == MessageRole.ASSISTANT) {
                val parent = parentOf[id]
                val siblings = (parent?.let { children[it] }
                    ?: messages.keys.filter { parentOf[it] == parent })
                    .filter { candidate ->
                        messages[candidate]?.role == MessageRole.ASSISTANT
                    }
                    .sortedBy { order[it] ?: Int.MAX_VALUE }
                val candidates = if (siblings.size > 1) siblings else listOf(id)
                if (candidates.size > 1) {
                    val parsed = candidates.mapNotNull { candidate -> parse(candidate) }
                    candidates.forEach { visited += it }
                    if (parsed.isNotEmpty()) {
                        val select = candidates.indexOf(id).coerceIn(0, parsed.lastIndex)
                        stats.alternativeReplies += parsed.size - 1
                        messageNodes += MessageNode(
                            id = stableUuid("deepseek:node:$rawId:${parent ?: "root"}:replies"),
                            messages = parsed,
                            selectIndex = select,
                        )
                    }
                    continue
                }
            }
            visited += id
            parse(id)?.let { messageNode ->
                messageNodes += MessageNode(
                    id = stableUuid("deepseek:node:$rawId:$id"),
                    messages = listOf(messageNode),
                    selectIndex = 0,
                )
            }
        }

        // 主链之外的分支：按树展开追加，避免静默丢消息
        while (true) {
            val start = messages.keys
                .filter { it !in visited }
                .filter { id -> parentOf[id]?.let { it in visited } != false }
                .minByOrNull { order[it] ?: Int.MAX_VALUE }
                ?: break
            val chain = chainFrom(start, children, visited)
            if (chain.isEmpty()) {
                visited += start
                continue
            }
            for (id in chain) {
                currentCoroutineContext().ensureActive()
                visited += id
                parse(id)?.let { message ->
                    stats.branchMessages++
                    messageNodes += MessageNode(
                        id = stableUuid("deepseek:branch:$rawId:$id"),
                        messages = listOf(message),
                        selectIndex = 0,
                    )
                }
            }
        }

        if (messageNodes.isEmpty()) return null
        return ParsedConversation(
            conversation = Conversation(
                id = conversationId,
                assistantId = assistantId,
                title = session["title"].text?.takeIf { it.isNotBlank() } ?: rawId,
                messageNodes = messageNodes.distinctBy { it.id },
                isPinned = (session["pinned"] as? JsonPrimitive)?.booleanOrNull == true,
                createAt = Instant.ofEpochMilli(createdAt),
                updateAt = Instant.ofEpochMilli(updatedAt),
                sourceUpdatedAt = updatedAt,
            ),
            stats = stats,
        )
    }

    private suspend fun parseMessage(
        zip: ZipFile,
        sessionId: String,
        message: JsonObject,
        fallbackMillis: Long,
        savedFiles: MutableMap<String, String?>,
        saveAttachment: suspend (DeepSeekAttachment) -> String?,
        formatMissingAttachment: (String) -> String,
        stats: ImportStats,
    ): UIMessage? {
        val role = message.role ?: return null
        val timestamp = message["inserted_at"].millis ?: fallbackMillis
        val parts = mutableListOf<UIMessagePart>()
        val citations = mutableListOf<UIMessageAnnotation.UrlCitation>()

        (message["fragments"] as? JsonArray).orEmpty().forEachIndexed { index, element ->
            val fragment = element as? JsonObject ?: return@forEachIndexed
            when (val type = fragment["type"].text) {
                "THINK" -> fragment["content"].text?.takeIf { it.isNotBlank() }?.let { content ->
                    val start = KotlinInstant.fromEpochMilliseconds(timestamp)
                    val seconds = (fragment["elapsed_secs"] as? JsonPrimitive)?.doubleOrNull
                        ?.takeIf { it.isFinite() && it >= 0 }
                    parts += UIMessagePart.Reasoning(
                        reasoning = content,
                        createdAt = start,
                        finishedAt = start + (seconds ?: 0.0).seconds,
                    )
                    stats.reasoningParts++
                }

                "FILE" -> (fragment["files"] as? JsonArray).orEmpty().forEach { fileElement ->
                    val attachment = fileElement as? JsonObject ?: return@forEach
                    val name = attachment["file_name"].text?.takeIf { it.isNotBlank() } ?: "attachment"
                    // Android 的 URLConnection.guessContentTypeFromName 对部分文件名会抛
                    // StringIndexOutOfBoundsException，这里必须兜底，不能让它中断整次导入
                    val mime = guessMimeType(name)
                    val exportPath = attachment["export_path"].text?.takeIf { it.isNotBlank() }
                    val url = if (exportPath == null) {
                        null
                    } else {
                        if (!savedFiles.containsKey(exportPath)) {
                            val entry = zip.getEntry(exportPath)
                                ?.takeUnless { it.isDirectory || it.size > MAX_ENTRY_SIZE }
                            savedFiles[exportPath] = entry?.let { zipEntry ->
                                runCatching {
                                    saveAttachment(
                                        DeepSeekAttachment(
                                            fileName = name,
                                            mimeType = mime,
                                            bytes = readBytes(zip, zipEntry),
                                        )
                                    )
                                }.getOrNull()
                            }
                        }
                        savedFiles[exportPath]
                    }
                    if (url.isNullOrBlank()) {
                        stats.attachmentParts++
                        parts += UIMessagePart.Text(formatMissingAttachment(name))
                    } else if ((attachment["is_image"] as? JsonPrimitive)?.booleanOrNull == true) {
                        stats.attachmentParts++
                        parts += UIMessagePart.Image(url)
                    } else {
                        stats.attachmentParts++
                        parts += UIMessagePart.Document(url = url, fileName = name, mime = mime)
                    }
                }

                "TOOL_SEARCH", "TOOL_OPEN", "TOOL_IMAGE_SEARCH", "TOOL_FIND" -> {
                    parts += UIMessagePart.ServerTool(
                        toolCallId = "deepseek:$sessionId:${message["id"].text}:$index",
                        toolName = type.lowercase().removePrefix("tool_"),
                        input = JsonObject(
                            fragment.filterKeys { it == "queries" || it == "url" }
                        ),
                        output = fragment,
                        status = if (fragment["status"].text == "FINISHED") {
                            ServerToolStatus.COMPLETED
                        } else {
                            ServerToolStatus.FAILED
                        },
                    )
                    stats.searchParts++
                    val sources = if (type == "TOOL_OPEN") {
                        listOf(fragment)
                    } else {
                        (fragment["results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    }
                    sources.forEach { source ->
                        source["url"].text?.takeIf { it.isNotBlank() }?.let { url ->
                            citations += UIMessageAnnotation.UrlCitation(
                                title = source["title"].text?.takeIf { it.isNotBlank() } ?: url,
                                url = url,
                            )
                        }
                    }
                }

                "TIP" -> {
                    fragment["content"].text?.takeIf { it.isNotBlank() }?.let {
                        parts += UIMessagePart.Text(it)
                    } ?: run { stats.skippedTipParts++ }
                }

                else -> {
                    fragment["content"].text?.takeIf { it.isNotBlank() }?.let {
                        parts += UIMessagePart.Text(it)
                    } ?: run { stats.unknownFragments++ }
                }
            }
        }

        if (parts.isEmpty()) {
            stats.emptyMessages++
            return null
        }
        return UIMessage(
            role = role,
            parts = parts,
            annotations = citations.distinctBy { it.url },
            createdAt = KotlinInstant.fromEpochMilliseconds(timestamp)
                .toLocalDateTime(TimeZone.currentSystemDefault()),
        )
    }

    /** 从当前消息沿 parent 回溯到根，返回根到当前的顺序。 */
    private fun ancestry(start: String, parentOf: Map<String, String?>): List<String> {
        val path = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var cursor: String? = start
        while (cursor != null && visited.add(cursor)) {
            path += cursor
            cursor = parentOf[cursor]
        }
        return path.asReversed()
    }

    /** 沿第一个未访问的子节点收集一条链。 */
    private fun chainFrom(
        start: String,
        children: Map<String, List<String>>,
        excluded: Set<String>,
    ): List<String> {
        val chain = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var cursor: String? = start
        while (cursor != null && visited.add(cursor) && cursor !in excluded) {
            chain += cursor
            cursor = children[cursor]?.firstOrNull { it !in excluded && it !in visited }
        }
        return chain
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

    private fun readObject(zip: ZipFile, entry: ZipEntry): JsonObject =
        JsonInstant.parseToJsonElement(readBytes(zip, entry).decodeToString()) as? JsonObject
            ?: error("Invalid DeepSeek export: ${entry.name} is not an object")

    private fun readBytes(zip: ZipFile, entry: ZipEntry): ByteArray {
        require(entry.size <= MAX_ENTRY_SIZE) { "DeepSeek archive entry is too large: ${entry.name}" }
        return zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_ENTRY_SIZE) { "DeepSeek archive entry is too large: ${entry.name}" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    /** Android 的 DefaultFileNameMap 对少数文件名会抛 StringIndexOutOfBoundsException。 */
    private fun guessMimeType(fileName: String): String {
        val fromName = runCatching { URLConnection.guessContentTypeFromName(fileName) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (fromName != null) return fromName
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private val JsonElement?.text: String?
        get() = (this as? JsonPrimitive)?.contentOrNull

    private val JsonElement?.millis: Long?
        get() = text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private val JsonObject.role: MessageRole?
        get() = when (this["role"].text) {
            "USER" -> MessageRole.USER
            "ASSISTANT" -> MessageRole.ASSISTANT
            "SYSTEM" -> MessageRole.SYSTEM
            else -> null
        }

    private fun stableUuid(value: String): Uuid =
        Uuid.parse(UUID.nameUUIDFromBytes(value.toByteArray()).toString())

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
