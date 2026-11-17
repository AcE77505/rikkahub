package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import java.io.File

private const val MAX_SEARCH_FILES = 50
private const val DEFAULT_SEARCH_LIMIT = 20
private const val SNIPPET_RADIUS = 80
private const val MAX_SNIPPETS_PER_FILE = 2
private const val MAX_IMPORT_FILES = 200
private const val MAX_IMPORT_DEPTH = 3
private const val MAX_EXPORT_FILES = 200

/**
 * 记忆库的文件工具：搜索、导出到文件、从文件导入。
 *
 * 这些工具没有独立开关，跟随助手的「记忆」设置一起启停。
 * 相对路径按当前工作区目录解析；没有工作区时要求绝对路径。
 */
fun buildMemoryFileTools(
    repository: MemoryRepository,
    assistantId: String,
    workspaceDir: File?,
): List<Tool> = listOf(
    Tool(
        name = MemoryToolNames.SEARCH,
        description = "Search the memory library for a keyword. " +
            "Returns matching files with a short snippet around each match, " +
            "so you can locate what the user stored before reading the full file.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringSchema("Keyword to search for (case-insensitive)"))
                    put("path_prefix", stringSchema("Only search files whose path starts with this, e.g. /people/"))
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of files to return (1-$MAX_SEARCH_FILES). Default $DEFAULT_SEARCH_LIMIT.")
                        put("minimum", 1)
                        put("maximum", MAX_SEARCH_FILES)
                    })
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val query = args.string("query").trim()
            if (query.isBlank()) error("query must not be blank")
            val prefix = (args.jsonObject["path_prefix"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val limit = ((args.jsonObject["limit"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_SEARCH_LIMIT)
                .coerceIn(1, MAX_SEARCH_FILES)
            val files = repository.getFiles(assistantId).filter { it.path.startsWith(prefix) }
            val matches = files.mapNotNull { file ->
                val snippets = memorySnippets(file.content, query)
                if (snippets.isEmpty()) null else file to snippets
            }.take(limit)
            toolOutput {
                put("query", query)
                put("files", buildJsonArray {
                    matches.forEach { (file, snippets) ->
                        add(buildJsonObject {
                            put("path", file.path)
                            put("title", file.title)
                            put("snippets", buildJsonArray { snippets.forEach { add(it) } })
                        })
                    }
                })
            }
        },
    ),
    Tool(
        name = MemoryToolNames.EXPORT,
        description = "Export one or more memory files to a file or directory. " +
            "Relative destination paths resolve against the workspace directory. " +
            "Existing destination files are not overwritten unless overwrite is true.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("paths", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Memory paths to export, e.g. [\"/topics/food.md\"]. Omit to export every memory file.")
                    })
                    put("destination", stringSchema("Target file or directory; relative paths resolve against the workspace"))
                    put("overwrite", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Replace destination files that already exist. Default false.")
                    })
                },
                required = listOf("destination"),
            )
        },
        execute = { args ->
            val destinationRaw = args.string("destination").trim()
            if (destinationRaw.isBlank()) error("destination is required")
            val destination = resolveMemoryPath(destinationRaw, workspaceDir)
            val requested = args.jsonObject.memoryPaths()
            val files = if (requested.isEmpty()) {
                repository.getFiles(assistantId)
            } else {
                requested.map { path ->
                    repository.getFile(assistantId, path) ?: error("Memory file not found: $path")
                }
            }
            if (files.isEmpty()) error("No memory files to export")
            if (files.size > MAX_EXPORT_FILES) error("Too many files to export (max $MAX_EXPORT_FILES)")

            val overwrite = (args.jsonObject["overwrite"] as? JsonPrimitive)?.booleanOrNull == true
            val directoryMode = destinationRaw.endsWith("/") || destination.isDirectory || files.size > 1
            val targets = files.map { file ->
                val target = if (directoryMode) {
                    File(destination, file.path.removePrefix("/"))
                } else {
                    destination
                }
                file to target
            }
            val conflicts = targets.filter { (_, target) -> target.exists() && !overwrite }
                .map { (_, target) -> target.absolutePath }
            if (conflicts.isNotEmpty()) {
                return@Tool toolOutput {
                    put("error", "Export cancelled: destination files already exist. Pass overwrite=true to replace them.")
                    put("conflicts", buildJsonArray { conflicts.forEach { add(it) } })
                }
            }

            withContext(Dispatchers.IO) {
                targets.forEach { (file, target) ->
                    target.parentFile?.mkdirs()
                    target.writeText(file.content)
                }
            }
            toolOutput {
                put("exported", buildJsonArray {
                    targets.forEach { (file, target) ->
                        add(buildJsonObject {
                            put("path", file.path)
                            put("destination", target.absolutePath)
                            put("size", file.content.toByteArray().size)
                        })
                    }
                })
            }
        },
    ),
    Tool(
        name = MemoryToolNames.IMPORT,
        description = "Import .md files from a file or directory into the memory library. " +
            "Relative source paths resolve against the workspace directory. " +
            "The whole import is rejected if any target memory path already exists; existing memory files are never overwritten.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("source", stringSchema("Path to a .md file or a directory; relative paths resolve against the workspace"))
                    put("path", stringSchema("Target memory path when source is a single file. Defaults to /<file name>"))
                },
                required = listOf("source"),
            )
        },
        execute = { args ->
            val sourceRaw = args.string("source").trim()
            if (sourceRaw.isBlank()) error("source is required")
            val source = resolveMemoryPath(sourceRaw, workspaceDir)
            val explicitPath = (args.jsonObject["path"] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() }

            val sources = collectMemoryImportSources(source, explicitPath).getOrElse {
                return@Tool toolOutput { put("error", it.message ?: "Cannot read $sourceRaw") }
            }
            if (sources.isEmpty()) {
                return@Tool toolOutput {
                    put("error", "No .md files found under ${source.absolutePath}")
                }
            }

            val normalized = sources.map { (file, targetPath) ->
                val path = MemoryFile.normalizePath(targetPath)
                    ?: return@Tool toolOutput {
                        put("error", "Invalid memory path: $targetPath")
                    }
                file to path
            }
            if (normalized.map { it.second }.toSet().size != normalized.size) {
                return@Tool toolOutput {
                    put("error", "Import cancelled: several source files map to the same memory path.")
                }
            }

            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    normalized.map { (file, path) ->
                        Triple(file, path, file.readText().removePrefix("\uFEFF"))
                    }
                }
            }.getOrElse {
                return@Tool toolOutput {
                    put("error", it.message ?: "Failed to read the source files")
                }
            }
            val tooLarge = loaded.filter { it.third.toByteArray().size > MemoryFile.MAX_BYTES }
                .map { it.second }
            if (tooLarge.isNotEmpty()) {
                return@Tool toolOutput {
                    put("error", "Import cancelled: files exceed the ${MemoryFile.MAX_BYTES / 1024}KB memory file limit.")
                    put("too_large", buildJsonArray { tooLarge.forEach { add(it) } })
                }
            }

            val existing = repository.getFiles(assistantId).map { it.path }.toSet()
            val conflicts = loaded.map { it.second }.filter { it in existing }
            if (conflicts.isNotEmpty()) {
                return@Tool toolOutput {
                    put(
                        "error",
                        "Import cancelled: target memory files already exist. " +
                            "Rename or delete them before importing."
                    )
                    put("conflicts", buildJsonArray { conflicts.forEach { add(it) } })
                }
            }

            val imported = mutableListOf<Pair<String, String>>()
            val failed = mutableListOf<Pair<String, String>>()
            for ((_, path, content) in loaded) {
                when (val result = repository.writeFile(
                    assistantId = assistantId,
                    path = path,
                    content = content,
                    ifVersion = MemoryRepository.NEW_FILE_VERSION,
                )) {
                    is MemoryWriteResult.Success -> imported += path to result.file?.version.orEmpty()
                    is MemoryWriteResult.Conflict -> failed += path to "already exists"
                    is MemoryWriteResult.Rejected -> failed += path to result.reason
                }
            }
            toolOutput {
                put("imported", buildJsonArray {
                    imported.forEach { (path, version) ->
                        add(buildJsonObject {
                            put("path", path)
                            put("version", version)
                        })
                    }
                })
                if (failed.isNotEmpty()) {
                    put("failed", buildJsonArray {
                        failed.forEach { (path, reason) ->
                            add(buildJsonObject {
                                put("path", path)
                                put("error", reason)
                            })
                        }
                    })
                }
            }
        },
    ),
)

private fun resolveMemoryPath(raw: String, workspaceDir: File?): File {
    val file = File(raw)
    if (file.isAbsolute) return file
    val base = workspaceDir ?: error("Relative paths require an active workspace; pass an absolute path.")
    return File(base, raw)
}

private fun JsonObject.memoryPaths(): List<String> = when (val paths = this["paths"]) {
    is JsonArray -> paths.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    is JsonPrimitive -> listOfNotNull(paths.contentOrNull?.trim()?.takeIf(String::isNotEmpty))
    else -> emptyList()
}

/** 每行最多两条片段，关键词用 [方括号] 标出，方便模型直接定位 */
private fun memorySnippets(content: String, query: String): List<String> {
    val snippets = mutableListOf<String>()
    var start = 0
    while (snippets.size < MAX_SNIPPETS_PER_FILE) {
        val index = content.indexOf(query, startIndex = start, ignoreCase = true)
        if (index < 0) break
        val from = (index - SNIPPET_RADIUS).coerceAtLeast(0)
        val to = (index + query.length + SNIPPET_RADIUS).coerceAtMost(content.length)
        val before = content.substring(from, index)
        val match = content.substring(index, index + query.length)
        val after = content.substring(index + query.length, to)
        snippets += buildString {
            if (from > 0) append("…")
            append(before.replace(Regex("\\s+"), " "))
            append('[').append(match).append(']')
            append(after.replace(Regex("\\s+"), " "))
            if (to < content.length) append("…")
        }
        start = index + query.length
    }
    return snippets
}

private fun collectMemoryImportSources(
    source: File,
    explicitPath: String?,
): Result<List<Pair<File, String>>> = runCatching {
    require(source.exists()) { "Path does not exist: ${source.absolutePath}" }
    if (source.isFile) {
        require(source.extension.equals("md", ignoreCase = true)) {
            "Not a .md file: ${source.absolutePath}"
        }
        return@runCatching listOf(source to (explicitPath ?: "/${source.name}"))
    }
    require(source.isDirectory) { "Not a file or directory: ${source.absolutePath}" }
    val files = source.walkTopDown()
        .maxDepth(MAX_IMPORT_DEPTH)
        .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
        .filter { file ->
            file.relativeTo(source).invariantSeparatorsPath.split('/').none { segment ->
                segment.startsWith(".")
            }
        }
        .take(MAX_IMPORT_FILES + 1)
        .toList()
    require(files.size <= MAX_IMPORT_FILES) {
        "Too many files (max $MAX_IMPORT_FILES). Import a subdirectory instead."
    }
    files.map { file ->
        val relative = file.relativeTo(source).invariantSeparatorsPath
        file to "/$relative"
    }
}
