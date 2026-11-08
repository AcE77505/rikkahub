package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(root)
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry {
        val file = resolvePath(root, path)
        file.parentFile?.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target.toEntry(root)
    }

    private fun resolveConflict(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do { candidate = File(file.parentFile, "$stem ($n)$ext"); n++ } while (candidate.exists())
        return candidate
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root, path)
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            } else {
                targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) {
            "Failed to move $source to $target"
        }
        return targetFile.toEntry(root)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = globToRegex(pattern)
        val rootCanonical = root.canonicalFile
        return walk(start)
            .filter { it.isFile || it.isDirectory }
            .filter { !it.name.startsWith(".l2s.") }
            .filter { matcher.matches(it.relativePathTo(rootCanonical)) }
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
            .toList()
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let(::globToRegex)
        val rootCanonical = root.canonicalFile

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start)
            .filter { it.isFile }
            .filter { !it.name.startsWith(".l2s.") }
            .forEach { file ->
                if (results.size >= config.maxSearchResults) return@forEach
                if (includeMatcher != null &&
                    !includeMatcher.matches(file.relativePathTo(rootCanonical))
                ) {
                    return@forEach
                }
                if (file.length() > config.maxReadBytes) return@forEach
                file.useLines(StandardCharsets.UTF_8) { lines ->
                    lines.forEachIndexed { index, line ->
                        if (results.size >= config.maxSearchResults) return@useLines
                        if (matcher.containsMatchIn(line)) {
                            results += WorkspaceSearchMatch(
                                path = file.relativePath(root),
                                line = index + 1,
                                text = line,
                            )
                        }
                    }
                }
            }
        return results
    }

    /**
     * 深度优先遍历，默认不跟随符号链接（与原 `Files.walk` 行为一致）。
     */
    private fun walk(start: File): Sequence<File> =
        start.walkTopDown().onEnter { dir -> dir == start || !isSymbolicLink(dir) }

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun File.relativePathTo(root: File): String =
        relativeTo(root).path.replace(File.separatorChar, '/')
}

private fun isSymbolicLink(file: File): Boolean =
    runCatching { file.canonicalPath != file.absolutePath }.getOrDefault(false)

/**
 * 把 glob 转成匹配 POSIX 相对路径（`/` 分隔）的正则。
 *
 * `java.nio.file.PathMatcher` 从 API 26 才有，而 core library desugaring 不覆盖
 * `java.nio.file`，所以这里自己实现常用的 glob 语义：`*`、`**`、`?`、`[abc]`、
 * `{a,b}` 与 `\` 转义。
 */
private fun globToRegex(glob: String): Regex {
    val pattern = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '\\' && i + 1 < glob.length -> {
                i++
                pattern.append(Regex.escape(glob[i].toString()))
            }

            c == '*' -> {
                if (i + 1 < glob.length && glob[i + 1] == '*') {
                    i++
                    if (i + 1 < glob.length && glob[i + 1] == '/') {
                        i++
                        // **/ 可以匹配零个目录
                        pattern.append("(?:.*/)?")
                    } else {
                        pattern.append(".*")
                    }
                } else {
                    pattern.append("[^/]*")
                }
            }

            c == '?' -> pattern.append("[^/]")

            c == '[' -> {
                val end = glob.indexOf(']', i + 1)
                if (end == -1) {
                    pattern.append("\\[")
                } else {
                    val body = glob.substring(i + 1, end).let {
                        if (it.startsWith("!")) "^" + it.substring(1) else it
                    }
                    pattern.append('[').append(body.replace("\\", "\\\\")).append(']')
                    i = end
                }
            }

            c == '{' -> {
                val end = glob.indexOf('}', i + 1)
                if (end == -1) {
                    pattern.append("\\{")
                } else {
                    val alternatives = glob.substring(i + 1, end).split(',')
                    pattern.append("(?:")
                    pattern.append(alternatives.joinToString("|") { Regex.escape(it) })
                    pattern.append(')')
                    i = end
                }
            }

            else -> pattern.append(Regex.escape(c.toString()))
        }
        i++
    }
    pattern.append('$')
    return Regex(pattern.toString())
}
