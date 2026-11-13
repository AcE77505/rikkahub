package me.rerere.rikkahub.ui.pages.extensions.skills

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.skills.InvalidSkillDir
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.data.skills.SkillValidationException
import me.rerere.rikkahub.data.skills.SkillValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream
import kotlin.collections.iterator

class SkillsVM(
    private val context: Application,
    private val skillManager: SkillManager,
) : ViewModel() {
    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    private val _invalidSkillDirs = MutableStateFlow<List<InvalidSkillDir>>(emptyList())
    val invalidSkillDirs = _invalidSkillDirs.asStateFlow()

    init {
        loadSkills()
    }

    private fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshSkills()
        }
    }

    private fun refreshSkills() {
        _skills.value = skillManager.listSkills()
        _invalidSkillDirs.value = skillManager.listInvalidSkillDirs()
    }

    fun deleteInvalidSkillDir(dirName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteInvalidSkillDir(dirName)
            refreshSkills()
        }
    }

    fun saveSkill(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkill(name, content)
            refreshSkills()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSkill(skill: SkillMetadata) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteSkill(skill)
            refreshSkills()
        }
    }

    fun importSkillFromFile(uri: Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = FileUtils.getFileNameFromUri(context, uri).orEmpty()
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        withContext(Dispatchers.Main) {
                            onResult(false, context.getString(R.string.skills_page_import_error_read_file))
                        }
                        return@launch
                    }

                val importedNames = if (isZipFile(fileName, bytes)) {
                    importSkillsFromZip(bytes)
                } else {
                    importSkillMarkdown(bytes)
                }

                refreshSkills()
                withContext(Dispatchers.Main) {
                    onResult(true, importedNames.joinToString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(
                        false,
                        e.message ?: context.getString(R.string.skills_page_unknown_error)
                    )
                }
            }
        }
    }

    companion object {
        /** 一次导入最多处理的技能数；GitHub 上的技能合集可能非常多，避免一次卡太久。 */
        private const val MAX_SKILLS_PER_IMPORT = 100
        private const val MAX_FILES_PER_SKILL = 2000
        private const val MAX_FILE_BYTES = 10 * 1024 * 1024
    }

    fun importSkillFromGitHub(repoUrl: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseGitHubUrl(repoUrl) ?: run {
                    withContext(
                        Dispatchers.Main
                    ) { onResult(false, context.getString(R.string.skills_page_import_error_invalid_github_url)) }
                    return@launch
                }

                // 先用 Git Trees API 一次拿到整棵树；被截断或失败时退回逐目录的 Contents API
                val tree = runCatching { fetchRepoTree(info) }.getOrNull()
                val importedNames = if (tree != null) {
                    importSkillsFromTree(info, tree)
                } else {
                    importSkillFromContents(info)
                }

                refreshSkills()
                withContext(Dispatchers.Main) { onResult(true, importedNames.joinToString()) }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(
                        false,
                        e.message ?: context.getString(R.string.skills_page_unknown_error)
                    )
                }
            }
        }
    }

    private data class GitHubTreeFile(val path: String)

    /**
     * GitHub Trees API：`/git/trees/{ref}?recursive=1`，一次请求列出整个仓库。
     * 返回 null 表示树被截断或响应异常，调用方应回退到 Contents API。
     */
    private fun fetchRepoTree(info: GitHubRepoInfo): List<GitHubTreeFile>? {
        val ref = URLEncoder.encode(info.branch, "UTF-8").replace("+", "%20")
        val apiUrl = "https://api.github.com/repos/${info.owner}/${info.repo}/git/trees/$ref?recursive=1"
        val json = downloadBytes(apiUrl)?.toString(Charsets.UTF_8) ?: return null
        val obj = JSONObject(json)
        if (obj.optBoolean("truncated", false)) return null
        val array = obj.optJSONArray("tree") ?: return null
        val files = ArrayList<GitHubTreeFile>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.optString("type") != "blob") continue
            val path = item.optString("path").takeIf { it.isNotBlank() } ?: continue
            files += GitHubTreeFile(path)
        }
        return files
    }

    /** 从整棵树里找出所有 `SKILL.md`，每个父目录是一个技能，文件用 raw 下载（不耗 API 配额）。 */
    private fun importSkillsFromTree(info: GitHubRepoInfo, tree: List<GitHubTreeFile>): List<String> {
        val scopePrefix = info.path.trim('/')
        val skillMdPaths = tree
            .map { it.path }
            .filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }
            .filter { scopePrefix.isEmpty() || it == "$scopePrefix/SKILL.md" || it.startsWith("$scopePrefix/") }
            .sorted()
        if (skillMdPaths.isEmpty()) {
            error(context.getString(R.string.skills_page_import_error_no_skill_md))
        }

        val basePaths = skillMdPaths.map { it.substringBeforeLast('/', missingDelimiterValue = "") }.distinct()
        val importedNames = mutableListOf<String>()
        for (basePath in basePaths.take(MAX_SKILLS_PER_IMPORT)) {
            // 一个技能目录里可能还嵌套了另一个技能，外层技能不包含内层技能的文件
            val nestedBases = basePaths.filter { other ->
                other != basePath && (basePath.isEmpty() || other.startsWith("$basePath/"))
            }
            val skillFilesInTree = tree
                .filter { file ->
                    relativeToBase(file.path, basePath) != null &&
                        nestedBases.none { nested ->
                            file.path.startsWith("$nested/") || file.path == "$nested/SKILL.md"
                        }
                }
                .take(MAX_FILES_PER_SKILL)
            val skillMdFile = skillFilesInTree.firstOrNull {
                relativeToBase(it.path, basePath)?.equals("SKILL.md", ignoreCase = true) == true
            } ?: continue

            val skillMdBytes = downloadRaw(info, skillMdFile.path) ?: continue
            // 合集里可能有格式不完整的技能，跳过而不是让整次导入失败
            val name = SkillValidator.validate(skillMdBytes.toString(Charsets.UTF_8)).getOrNull()
                ?: continue

            val skillFiles = LinkedHashMap<String, ByteArray>()
            var complete = true
            for (file in skillFilesInTree) {
                val relativePath = relativeToBase(file.path, basePath) ?: continue
                val targetPath = if (relativePath.equals("SKILL.md", ignoreCase = true)) "SKILL.md" else relativePath
                val bytes = if (file.path == skillMdFile.path) {
                    skillMdBytes
                } else {
                    downloadRaw(info, file.path) ?: run { complete = false; null }
                } ?: continue
                if (bytes.size > MAX_FILE_BYTES) {
                    complete = false
                    continue
                }
                skillFiles[targetPath] = bytes
            }
            if (!complete || skillFiles.isEmpty()) continue
            if (skillManager.saveSkillFileBytesAtomically(name, skillFiles)) {
                importedNames += name
            }
        }

        if (importedNames.isEmpty()) {
            error(context.getString(R.string.skills_page_import_error_save_failed, info.path))
        }
        return importedNames.distinct()
    }

    /** 兼容路径：树接口不可用时逐目录列文件，只导入根目录的 SKILL.md。 */
    private fun importSkillFromContents(info: GitHubRepoInfo): List<String> {
        // Collect all files recursively via GitHub Contents API
        val files = mutableListOf<Pair<String, String>>() // relativePath -> downloadUrl
        val listed = listFilesRecursively(info.owner, info.repo, info.branch, info.path, info.path, files)
        if (!listed) {
            error(context.getString(R.string.skills_page_import_error_github_list_failed))
        }

        val skillMdEntry = files.find { it.first == "SKILL.md" }
            ?: error(context.getString(R.string.skills_page_import_error_no_skill_md))

        val skillMdBytes = downloadBytes(skillMdEntry.second)
            ?: error(context.getString(R.string.skills_page_import_error_download_skill_md))

        val name = SkillValidator.validate(skillMdBytes.toString(Charsets.UTF_8)).getOrElse {
            error(invalidFormatMessage("SKILL.md", it))
        }

        // 按字节下载保存，避免图片等二进制附属文件被当作 UTF-8 文本解码而损坏
        val fileContents = LinkedHashMap<String, ByteArray>()
        for ((relativePath, downloadUrl) in files) {
            val content = if (relativePath == "SKILL.md") skillMdBytes else downloadBytes(downloadUrl)
                ?: error(context.getString(R.string.skills_page_import_error_download_file, relativePath))
            fileContents[relativePath] = content
        }

        if (!skillManager.saveSkillFileBytesAtomically(name, fileContents)) {
            error(context.getString(R.string.skills_page_import_error_save_failed, name))
        }
        return listOf(name)
    }

    private fun relativeToBase(path: String, basePath: String): String? {
        if (basePath.isEmpty()) return path
        if (path == basePath) return null
        return path.removePrefix("$basePath/").takeIf { it != path }
    }

    private fun downloadRaw(info: GitHubRepoInfo, path: String): ByteArray? {
        val encodedPath = path.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val url = "https://raw.githubusercontent.com/${info.owner}/${info.repo}/${info.branch}/$encodedPath"
        return downloadBytes(url)
    }

    private fun invalidFormatMessage(path: String, error: Throwable): String {
        val reason = (error as? SkillValidationException)?.error
            ?.let { context.getString(it.messageRes, *it.formatArgs) }
            ?: error.message.orEmpty()
        return context.getString(R.string.skills_page_import_error_invalid_format, path, reason)
    }

    private fun importSkillMarkdown(bytes: ByteArray): List<String> {
        val content = bytes.toString(Charsets.UTF_8)
        val name = SkillValidator.validate(content).getOrElse { error(invalidFormatMessage("SKILL.md", it)) }
        val saved = skillManager.saveSkill(name, content) ?: error(context.getString(R.string.skills_page_save_failed))
        return listOf(saved.name)
    }

    private fun importSkillsFromZip(bytes: ByteArray): List<String> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        val path = normalizeZipEntryPath(entry.name)
                        if (path != null) {
                            files[path] = zipInput.readBytes()
                        }
                    }
                } finally {
                    zipInput.closeEntry()
                }
            }
        }

        val skillMdPaths = files.keys
            .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
            .sorted()
        if (skillMdPaths.isEmpty()) {
            error(context.getString(R.string.skills_page_import_error_zip_no_skill_md))
        }
        val skillBasePaths = skillMdPaths.map {
            it.substringBeforeLast('/', missingDelimiterValue = "")
        }

        val importedNames = mutableListOf<String>()
        for (skillMdPath in skillMdPaths) {
            val skillContent = files[skillMdPath]?.toString(Charsets.UTF_8)
                ?: error(context.getString(R.string.skills_page_import_error_read_entry, skillMdPath))
            val name = SkillValidator.validate(skillContent).getOrElse { error(invalidFormatMessage(skillMdPath, it)) }

            val basePath = skillMdPath.substringBeforeLast('/', missingDelimiterValue = "")
            val skillFiles = LinkedHashMap<String, ByteArray>()
            for ((path, content) in files) {
                if (isInsideNestedSkill(path, basePath, skillBasePaths)) continue
                val relativePath = relativeToSkillBase(path, basePath) ?: continue
                val targetPath = if (relativePath.equals("SKILL.md", ignoreCase = true)) {
                    "SKILL.md"
                } else {
                    relativePath
                }
                skillFiles[targetPath] = content
            }

            val saved = skillManager.saveSkillFileBytesAtomically(name, skillFiles)
            if (!saved) {
                error(context.getString(R.string.skills_page_import_error_save_failed, name))
            }
            importedNames += name
        }
        return importedNames.distinct()
    }

    private fun isInsideNestedSkill(path: String, basePath: String, skillBasePaths: List<String>): Boolean {
        return skillBasePaths.any { otherBasePath ->
            otherBasePath != basePath &&
                isPathInsideBase(path, otherBasePath) &&
                (basePath.isBlank() || isPathInsideBase(otherBasePath, basePath))
        }
    }

    private fun isPathInsideBase(path: String, basePath: String): Boolean {
        return basePath.isBlank() || path == basePath || path.startsWith("$basePath/")
    }

    private fun relativeToSkillBase(path: String, basePath: String): String? {
        if (basePath.isBlank()) return path
        if (path == basePath) return null
        return path.removePrefix("$basePath/").takeIf { it != path }
    }

    private fun normalizeZipEntryPath(path: String): String? {
        val parts = path.replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun isZipFile(fileName: String, bytes: ByteArray): Boolean {
        return fileName.endsWith(".zip", ignoreCase = true) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x03, 0x04) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x05, 0x06) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x07, 0x08)
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (size < values.size) return false
        return values.indices.all { index -> (this[index].toInt() and 0xFF) == values[index] }
    }

    private fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
    ): Boolean {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadBytes(apiUrl)?.toString(Charsets.UTF_8) ?: return false
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("type")
            val itemPath = item.getString("path")
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    val downloadUrl = item.optString("download_url").takeIf { it.isNotBlank() }
                        ?: return false
                    result.add(relativePath to downloadUrl)
                }

                "dir" -> {
                    val ok = listFilesRecursively(owner, repo, branch, itemPath, basePath, result)
                    if (!ok) return false
                }
            }
        }
        return true
    }

    private data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        // https://github.com/owner/repo
        // https://github.com/owner/repo/tree/branch
        // https://github.com/owner/repo/tree/branch/sub/path
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "HEAD" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

    private fun downloadBytes(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            val code = connection.responseCode
            // 未登录的 GitHub API 每小时仅 60 次，超限时给出明确提示而不是笼统的"读取失败"
            if ((code == 403 || code == 429) && connection.getHeaderField("X-RateLimit-Remaining") == "0") {
                error(context.getString(R.string.skills_page_import_error_github_rate_limit))
            }
            if (code == 200) connection.inputStream.use { it.readBytes() } else null
        } finally {
            connection.disconnect()
        }
    }
}
