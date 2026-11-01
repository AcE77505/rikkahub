package me.rerere.rikkahub.data.ai.subagent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import kotlin.math.min
import kotlin.uuid.Uuid

@Serializable
enum class SubAgentRunStatus {
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
    INTERRUPTED,
}

/** 一个子代理运行在磁盘上的元数据；transcript 单独存放在同名 .json 文件里 */
@Serializable
data class SubAgentRunMeta(
    val ref: String,
    val parentConversationId: String,
    val parentAssistantId: String,
    val subAgentId: String,
    val label: String,
    val modelId: String? = null,
    val status: SubAgentRunStatus,
    val error: String? = null,
    val startedAt: Long,
    val updatedAt: Long,
    val steps: Int = 0,
)

data class SubAgentReadResult(
    val ref: String,
    val status: SubAgentRunStatus,
    val text: String,
    val offsetBytes: Int,
    val nextOffsetBytes: Int?,
    val totalBytes: Int,
)

/**
 * 子代理运行记录存储。
 *
 * 目录结构：
 * ```
 * filesDir/subagents/
 *   sa_<uuid>.json          # List<UIMessage> transcript
 *   sa_<uuid>.meta.json     # 状态、父会话、模型、时间、步数
 * ```
 *
 * 只保存子代理自己的记录，不写入 Conversation 表。结果按 UTF-8 字节分页读取，
 * 避免长报告一次性进入主对话上下文。
 */
class SubAgentStore(
    private val root: File,
) {
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    private val lock = Any()

    init {
        root.mkdirs()
    }

    fun newRef(): String = "sa_${Uuid.random()}"

    suspend fun prepare(meta: SubAgentRunMeta) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            root.mkdirs()
            writeMeta(meta)
        }
    }

    suspend fun saveTranscript(ref: String, messages: List<UIMessage>) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            writeTextAtomic(transcriptFile(ref), JsonInstant.encodeToString(messages))
        }
    }

    suspend fun complete(
        ref: String,
        messages: List<UIMessage>,
        steps: Int,
    ) = updateTerminal(ref, SubAgentRunStatus.COMPLETED, null, steps) { messages }

    suspend fun cancel(
        ref: String,
        messages: List<UIMessage>,
        reason: String,
    ) = updateTerminal(ref, SubAgentRunStatus.CANCELLED, reason, 0) { messages }

    suspend fun fail(
        ref: String,
        messages: List<UIMessage>,
        error: String,
    ) = updateTerminal(ref, SubAgentRunStatus.FAILED, error, 0) { messages }

    private suspend fun updateTerminal(
        ref: String,
        status: SubAgentRunStatus,
        error: String?,
        steps: Int,
        transcript: () -> List<UIMessage>,
    ) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val meta = readMeta(ref) ?: return@withContext
            writeTextAtomic(transcriptFile(ref), JsonInstant.encodeToString(transcript()))
            writeMeta(
                meta.copy(
                    status = status,
                    error = error,
                    steps = steps,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun readResult(
        ref: String,
        parentConversationId: String,
        offsetBytes: Int,
        limitBytes: Int,
    ): SubAgentReadResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            require(offsetBytes >= 0) { "offset_bytes must be non-negative" }
            require(limitBytes in 1..MAX_RESULT_BYTES) {
                "limit_bytes must be between 1 and $MAX_RESULT_BYTES"
            }
            val meta = readMeta(ref) ?: error("Unknown sub-agent reference: $ref")
            require(meta.parentConversationId == parentConversationId) {
                "Sub-agent reference $ref does not belong to this conversation"
            }
            val readable = meta.status == SubAgentRunStatus.COMPLETED ||
                meta.status == SubAgentRunStatus.CANCELLED
            require(readable) {
                "Sub-agent $ref is ${meta.status.name.lowercase()}; only completed or cancelled results can be read"
            }
            val messages = readTranscript(ref)
            val text = if (meta.status == SubAgentRunStatus.COMPLETED) {
                finalAnswer(messages)
                    ?: error("Sub-agent $ref has no final assistant answer")
            } else {
                partialTranscript(messages)
            }
            page(text, ref, meta.status, offsetBytes, limitBytes)
        }
    }

    suspend fun deleteByParent(parentConversationId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            metaFiles().forEach { file ->
                val meta = runCatching { readMetaFile(file) }.getOrNull() ?: return@forEach
                if (meta.parentConversationId == parentConversationId) {
                    transcriptFile(meta.ref).delete()
                    file.delete()
                }
            }
        }
    }

    /**
     * 应用启动时把上次进程留下的 RUNNING 记录标成 INTERRUPTED。
     *
     * 只处理父会话不在 [activeConversationIds] 里的记录；当前进程新启动的运行不会出现在磁盘上。
     */
    suspend fun cleanupStaleRunning(activeConversationIds: Set<String> = emptySet()) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                metaFiles().forEach { file ->
                    val meta = runCatching { readMetaFile(file) }.getOrNull() ?: return@forEach
                    if (meta.status == SubAgentRunStatus.RUNNING &&
                        meta.parentConversationId !in activeConversationIds
                    ) {
                        writeMeta(
                            meta.copy(
                                status = SubAgentRunStatus.INTERRUPTED,
                                error = "Interrupted by app restart",
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }
            }
        }

    suspend fun listByParent(parentConversationId: String): List<SubAgentRunMeta> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                metaFiles()
                    .mapNotNull { runCatching { readMetaFile(it) }.getOrNull() }
                    .filter { it.parentConversationId == parentConversationId }
                    .sortedByDescending { it.startedAt }
            }
        }

    suspend fun listAll(): List<SubAgentRunMeta> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                metaFiles()
                    .mapNotNull { runCatching { readMetaFile(it) }.getOrNull() }
                    .sortedByDescending { it.startedAt }
            }
        }

    private fun metaFile(ref: String): File = File(root, "$ref.meta.json")

    private fun transcriptFile(ref: String): File = File(root, "$ref.json")

    private fun metaFiles(): List<File> =
        root.listFiles { file -> file.isFile && file.name.endsWith(".meta.json") }?.toList().orEmpty()

    private fun readMeta(ref: String): SubAgentRunMeta? = readMetaFile(metaFile(ref))

    private fun readMetaFile(file: File): SubAgentRunMeta? =
        if (!file.isFile) null else JsonInstant.decodeFromString<SubAgentRunMeta>(file.readText())

    private fun writeMeta(meta: SubAgentRunMeta) {
        writeTextAtomic(metaFile(meta.ref), JsonInstant.encodeToString(meta))
    }

    private fun readTranscript(ref: String): List<UIMessage> {
        val file = transcriptFile(ref)
        if (!file.isFile) return emptyList()
        return JsonInstant.decodeFromString<List<UIMessage>>(file.readText())
    }

    private fun finalAnswer(messages: List<UIMessage>): String? =
        messages.asReversed()
            .firstOrNull { it.role == MessageRole.ASSISTANT && it.toText().isNotBlank() }
            ?.toText()
            ?.trim()

    private fun partialTranscript(messages: List<UIMessage>): String = buildString {
        appendLine("Cancelled before a final answer; nothing below is a result.")
        appendLine("What the sub-agent did before it stopped.")
        messages.forEach { message ->
            val text = message.toText().trim()
            if (text.isNotEmpty()) {
                appendLine()
                appendLine("[${message.role.name.lowercase()}] $text")
            }
            message.getTools().forEach { tool ->
                appendLine("→ ${tool.toolName}")
                val output = tool.output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .trim()
                if (output.isNotEmpty()) appendLine("← ${output.take(2_000)}")
            }
        }
    }

    private fun page(
        text: String,
        ref: String,
        status: SubAgentRunStatus,
        offsetBytes: Int,
        limitBytes: Int,
    ): SubAgentReadResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(offsetBytes <= bytes.size) {
            "offset_bytes $offsetBytes exceeds result size ${bytes.size}"
        }
        var start = offsetBytes
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        var end = min(start + limitBytes, bytes.size)
        while (end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) end--
        val pageText = String(bytes, start, end - start, Charsets.UTF_8)
        return SubAgentReadResult(
            ref = ref,
            status = status,
            text = pageText,
            offsetBytes = start,
            nextOffsetBytes = if (end < bytes.size) end else null,
            totalBytes = bytes.size,
        )
    }

    private fun writeTextAtomic(file: File, text: String) {
        val parent = file.parentFile ?: return
        parent.mkdirs()
        val temp = File(parent, ".${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    companion object {
        private const val DIR_NAME = "subagents"
        const val MAX_RESULT_BYTES = 24 * 1024
        const val DEFAULT_RESULT_BYTES = 12 * 1024
    }
}
