package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * 助手原生导出。
 *
 * 只清理指向本机数据的引用（快捷消息、MCP、工作区、注入、Lorebook、子代理、标签），
 * 提示词、预设对话、正则和生成参数原样保留，保证在另一台设备导入后仍可用。
 */
object AssistantSerializer : ExportSerializer<Assistant> {
    override val type = "assistant"

    override fun getExportFileName(data: Assistant): String {
        val name = data.name.trim()
            .ifBlank { "assistant" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        return "$name.json"
    }

    override fun export(data: Assistant): ExportData = ExportData(
        type = type,
        data = ExportSerializer.DefaultJson.encodeToJsonElement(Assistant.serializer(), data),
    )

    override fun import(context: Context, uri: Uri): Result<Assistant> = runCatching {
        decodeFromJson(readUri(context, uri)) ?: error("Unsupported assistant file")
    }

    /** 从导出 JSON 解码；不是助手导出文件时返回 null。 */
    fun decodeFromJson(json: String): Assistant? = runCatching {
        val exportData = ExportSerializer.DefaultJson.decodeFromString(ExportData.serializer(), json)
        if (exportData.type != type) return null
        sanitize(ExportSerializer.DefaultJson.decodeFromJsonElement(Assistant.serializer(), exportData.data))
    }.getOrNull()

    private fun sanitize(assistant: Assistant): Assistant = assistant.copy(
        id = Uuid.random(),
        tags = emptyList(),
        quickMessageIds = emptySet(),
        mcpServers = emptySet(),
        workspaceId = null,
        modeInjectionIds = emptySet(),
        lorebookIds = emptySet(),
        enabledSubAgents = emptySet(),
    )
}
