package me.rerere.rikkahub.service

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceManager
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * 通过 Storage Access Framework 把两类目录暴露给系统文件管理器。
 *
 * **工作区根**（可读写）：
 * ```
 * <root>
 * └── {workspace name}        <- 虚拟目录，对应工作区目录
 *     └── ...                 <- 工作区目录下的实际文件
 * ```
 *
 * **终端根**（只有 toolchain 只读，其余可读写）：
 * ```
 * <root>
 * ├── toolchain               <- files/toolchain（只读：这里的 .so 会被我们启动的 shell 加载）
 * └── {workspace name} · HOME / TMP
 * ```
 *
 * documentId 设计：
 * - 顶层（两个 provider 根）：[ROOT_DOC_ID] / [TERMINAL_ROOT_DOC_ID]
 * - 工作区：`ws/{root}`、`ws/{root}/{相对路径}`
 * - 终端：`tc/{baseKey}`、`tc/{baseKey}/{相对路径}`，baseKey 为 `toolchain` / `home:{root}` / `tmp:{root}`
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private fun manager(): WorkspaceManager = GlobalContext.get().get()

    private fun dao(): WorkspaceDAO = GlobalContext.get().get()

    private fun allWorkspaces(): List<WorkspaceEntity> = runBlocking { dao().getAll() }

    private fun workspaceName(root: String): String =
        allWorkspaces().firstOrNull { it.root == root }?.name ?: root

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name))
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE,
            )
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        // 第二个根：终端目录，供用户管理 .bashrc / 脚本 / 工具链文件
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, TERMINAL_ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, TERMINAL_ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.setting_tools_terminal_dir))
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        when (val target = parseDocId(documentId)) {
            DocId.Root -> addVirtualRootRow(
                cursor,
                ROOT_DOC_ID,
                context?.getString(R.string.app_name).orEmpty(),
            )

            DocId.TerminalRoot -> addVirtualRootRow(
                cursor,
                TERMINAL_ROOT_DOC_ID,
                context?.getString(R.string.setting_tools_terminal_root).orEmpty(),
            )

            is DocId.WorkspaceNode -> {
                if (target.relPath.isEmpty() && target.workspaceRoot.isEmpty()) return cursor
                val base = if (target.relPath.isEmpty()) {
                    workspaceBaseDir(target.workspaceRoot)
                } else {
                    workspaceBaseDir(target.workspaceRoot)
                }
                val file = resolveInside(base, target.relPath)
                if (file.exists()) {
                    addFileRow(
                        cursor = cursor,
                        documentId = buildWorkspaceDocId(target.workspaceRoot, target.relPath),
                        displayName = if (target.relPath.isEmpty()) {
                            workspaceName(target.workspaceRoot)
                        } else {
                            file.name
                        },
                        file = file,
                        readOnly = false,
                    )
                }
            }

            is DocId.TerminalNode -> {
                val base = terminalBase(target.baseKey) ?: return cursor
                val file = resolveInside(base.dir, target.relPath)
                if (file.exists()) {
                    addFileRow(
                        cursor = cursor,
                        documentId = buildTerminalDocId(target.baseKey, target.relPath),
                        displayName = if (target.relPath.isEmpty()) base.displayName else file.name,
                        file = file,
                        readOnly = base.readOnly,
                    )
                }
            }
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        when (val parent = parseDocId(parentDocumentId)) {
            DocId.Root -> {
                // 顶层：列出所有 workspace
                for (ws in allWorkspaces()) {
                    val dir = workspaceBaseDir(ws.root).also { it.mkdirs() }
                    addFileRow(
                        cursor = cursor,
                        documentId = buildWorkspaceDocId(ws.root, ""),
                        displayName = ws.name,
                        file = dir,
                        readOnly = false,
                    )
                }
            }

            DocId.TerminalRoot -> {
                // 终端根：toolchain（只读）+ 每个工作区的 home / tmp
                terminalBases().forEach { base ->
                    val dir = base.dir.also { it.mkdirs() }
                    addFileRow(
                        cursor = cursor,
                        documentId = buildTerminalDocId(base.key, ""),
                        displayName = base.displayName,
                        file = dir,
                        readOnly = base.readOnly,
                    )
                }
            }

            is DocId.WorkspaceNode -> {
                val base = workspaceBaseDir(parent.workspaceRoot)
                listChildren(cursor, base, parent.relPath, readOnly = false) { rel ->
                    buildWorkspaceDocId(parent.workspaceRoot, rel)
                }
            }

            is DocId.TerminalNode -> {
                val base = terminalBase(parent.baseKey) ?: return cursor
                listChildren(cursor, base.dir, parent.relPath, readOnly = base.readOnly) { rel ->
                    buildTerminalDocId(parent.baseKey, rel)
                }
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val target = parseDocId(documentId)
        val (file, readOnly) = when (target) {
            DocId.Root, DocId.TerminalRoot -> throw IllegalArgumentException("Cannot open root as a document")
            is DocId.WorkspaceNode -> {
                require(target.relPath.isNotEmpty()) { "Cannot open workspace root as a document" }
                resolveInside(workspaceBaseDir(target.workspaceRoot), target.relPath) to false
            }

            is DocId.TerminalNode -> {
                val base = terminalBase(target.baseKey) ?: throw IllegalArgumentException("Unknown terminal dir")
                require(target.relPath.isNotEmpty()) { "Cannot open terminal root as a document" }
                resolveInside(base.dir, target.relPath) to base.readOnly
            }
        }
        // 只读节点拒绝任何含写标志的打开方式：这些文件会被我们启动的 shell 加载
        if (readOnly && mode.contains('w') || readOnly && mode.contains('a') || readOnly && mode.contains('r') && mode.contains('+')) {
            throw SecurityException("This directory is read-only: $documentId")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = parseDocId(parentDocumentId)
        val (parentDir, relPrefix, readOnly, buildId) = when (parent) {
            DocId.Root, DocId.TerminalRoot ->
                throw IllegalArgumentException("Cannot create document at root")

            is DocId.WorkspaceNode -> {
                val base = workspaceBaseDir(parent.workspaceRoot)
                CreateTarget(
                    dir = resolveInside(base, parent.relPath),
                    relPrefix = parent.relPath,
                    readOnly = false,
                    buildId = { rel -> buildWorkspaceDocId(parent.workspaceRoot, rel) },
                )
            }

            is DocId.TerminalNode -> {
                val base = terminalBase(parent.baseKey)
                    ?: throw IllegalArgumentException("Unknown terminal dir")
                CreateTarget(
                    dir = resolveInside(base.dir, parent.relPath),
                    relPrefix = parent.relPath,
                    readOnly = base.readOnly,
                    buildId = { rel -> buildTerminalDocId(parent.baseKey, rel) },
                )
            }
        }
        require(!readOnly) { "Cannot create documents in a read-only directory" }
        require(parentDir.isDirectory) { "Parent is not a directory" }
        val target = uniqueChild(parentDir, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) {
            require(target.mkdir()) { "Failed to create directory: $displayName" }
        } else {
            require(target.createNewFile()) { "Failed to create file: $displayName" }
        }
        notifyChange(parentDocumentId)
        val rel = if (relPrefix.isEmpty()) target.name else "$relPrefix/${target.name}"
        return buildId(rel)
    }

    override fun deleteDocument(documentId: String) {
        val target = parseDocId(documentId)
        val (file, parentDocumentId) = when (target) {
            DocId.Root, DocId.TerminalRoot -> throw IllegalArgumentException("Cannot delete this document")
            is DocId.WorkspaceNode -> {
                require(target.relPath.isNotEmpty()) { "Cannot delete this document" }
                resolveInside(workspaceBaseDir(target.workspaceRoot), target.relPath) to
                    buildWorkspaceDocId(target.workspaceRoot, target.relPath.substringBeforeLast('/', ""))
            }

            is DocId.TerminalNode -> {
                val base = terminalBase(target.baseKey) ?: throw IllegalArgumentException("Unknown terminal dir")
                require(!base.readOnly) { "Cannot delete from a read-only directory" }
                require(target.relPath.isNotEmpty()) { "Cannot delete this document" }
                resolveInside(base.dir, target.relPath) to
                    buildTerminalDocId(target.baseKey, target.relPath.substringBeforeLast('/', ""))
            }
        }
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        require(ok) { "Failed to delete: $documentId" }
        notifyChange(parentDocumentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val target = parseDocId(documentId)
        val (file, parentId, buildId) = when (target) {
            DocId.Root, DocId.TerminalRoot -> throw IllegalArgumentException("Cannot rename this document")
            is DocId.WorkspaceNode -> {
                require(target.relPath.isNotEmpty()) { "Cannot rename this document" }
                val parentRel = target.relPath.substringBeforeLast('/', "")
                Triple(
                    resolveInside(workspaceBaseDir(target.workspaceRoot), target.relPath),
                    buildWorkspaceDocId(target.workspaceRoot, parentRel),
                    { rel: String -> buildWorkspaceDocId(target.workspaceRoot, rel) },
                )
            }

            is DocId.TerminalNode -> {
                val base = terminalBase(target.baseKey) ?: throw IllegalArgumentException("Unknown terminal dir")
                require(!base.readOnly) { "Cannot rename in a read-only directory" }
                require(target.relPath.isNotEmpty()) { "Cannot rename this document" }
                val parentRel = target.relPath.substringBeforeLast('/', "")
                Triple(
                    resolveInside(base.dir, target.relPath),
                    buildTerminalDocId(target.baseKey, parentRel),
                    { rel: String -> buildTerminalDocId(target.baseKey, rel) },
                )
            }
        }
        val dest = File(file.parentFile, displayName.replace('/', '_'))
        require(!dest.exists()) { "Target already exists: $displayName" }
        require(file.renameTo(dest)) { "Failed to rename: $documentId" }
        notifyChange(parentId)
        val parentRel = when (target) {
            is DocId.WorkspaceNode -> target.relPath.substringBeforeLast('/', "")
            is DocId.TerminalNode -> target.relPath.substringBeforeLast('/', "")
            else -> ""
        }
        val rel = if (parentRel.isEmpty()) dest.name else "$parentRel/${dest.name}"
        return buildId(rel)
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val source = parseDocId(sourceDocumentId)
        val targetParent = parseDocId(targetParentDocumentId)
        val (srcFile, _) = documentFile(source)
        val (destDir, destRelPrefix, destReadOnly, destBuildId) = documentParent(targetParent)
        require(!destReadOnly) { "Cannot copy into a read-only directory" }
        require(destDir.isDirectory) { "Target is not a directory" }
        val dest = uniqueChild(destDir, srcFile.name)
        // 防止把目录复制到自身内部
        require(!dest.canonicalPath.startsWith(srcFile.canonicalPath + File.separator)) {
            "Cannot copy a directory into itself"
        }
        require(srcFile.copyRecursively(dest)) { "Failed to copy: $sourceDocumentId" }
        notifyChange(targetParentDocumentId)
        val rel = if (destRelPrefix.isEmpty()) dest.name else "$destRelPrefix/${dest.name}"
        return destBuildId(rel)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String?,
        targetParentDocumentId: String,
    ): String {
        val source = parseDocId(sourceDocumentId)
        val targetParent = parseDocId(targetParentDocumentId)
        val (srcFile, srcReadOnly) = documentFile(source)
        val (destDir, destRelPrefix, destReadOnly, destBuildId) = documentParent(targetParent)
        require(!srcReadOnly) { "Cannot move from a read-only directory" }
        require(!destReadOnly) { "Cannot move into a read-only directory" }
        require(destDir.isDirectory) { "Target is not a directory" }
        val dest = uniqueChild(destDir, srcFile.name)
        // 防止把目录移动到自身内部
        require(!dest.canonicalPath.startsWith(srcFile.canonicalPath + File.separator)) {
            "Cannot move a directory into itself"
        }
        if (!srcFile.renameTo(dest)) {
            // 所有目录都在应用私有目录下，renameTo 一般可行；失败则回退为复制+删除
            require(srcFile.copyRecursively(dest)) { "Failed to move: $sourceDocumentId" }
            require(if (srcFile.isDirectory) srcFile.deleteRecursively() else srcFile.delete()) {
                "Failed to remove source after move: $sourceDocumentId"
            }
        }
        sourceParentDocumentId?.let { notifyChange(it) }
        notifyChange(targetParentDocumentId)
        val rel = if (destRelPrefix.isEmpty()) dest.name else "$destRelPrefix/${dest.name}"
        return destBuildId(rel)
    }

    override fun getDocumentType(documentId: String): String = when (val target = parseDocId(documentId)) {
        DocId.Root, DocId.TerminalRoot -> Document.MIME_TYPE_DIR
        is DocId.WorkspaceNode -> mimeOf(
            resolveInside(workspaceBaseDir(target.workspaceRoot), target.relPath)
        )

        is DocId.TerminalNode -> {
            val base = terminalBase(target.baseKey)
            if (base == null) "application/octet-stream" else mimeOf(resolveInside(base.dir, target.relPath))
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = when {
        documentId == ROOT_DOC_ID || documentId == TERMINAL_ROOT_DOC_ID -> false
        parentDocumentId == ROOT_DOC_ID -> documentId.startsWith(WORKSPACE_PREFIX)
        parentDocumentId == TERMINAL_ROOT_DOC_ID -> documentId.startsWith(TERMINAL_PREFIX)
        parentDocumentId == documentId -> true
        documentId.startsWith("$parentDocumentId/") -> true
        else -> false
    }

    // --- helpers ---

    private data class CreateTarget(
        val dir: File,
        val relPrefix: String,
        val readOnly: Boolean,
        val buildId: (String) -> String,
    )

    private fun addVirtualRootRow(cursor: MatrixCursor, documentId: String, displayName: String) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS, 0)
            add(Document.COLUMN_SIZE, null)
            add(Document.COLUMN_LAST_MODIFIED, null)
        }
    }

    private fun listChildren(
        cursor: MatrixCursor,
        base: File,
        relPath: String,
        readOnly: Boolean,
        buildId: (String) -> String,
    ) {
        val dir = resolveInside(base, relPath)
        if (!dir.isDirectory) return
        dir.listFiles()
            .orEmpty()
            // toolchain/bin 是指向 /data/app 的符号链接，SAF 语义不友好，也不该被人工管理
            .filter { !it.name.startsWith(".l2s.") && !(readOnly && it.name == "bin") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .forEach { child ->
                val rel = if (relPath.isEmpty()) child.name else "$relPath/${child.name}"
                // 符号链接不跟随：canonicalFile 会解析到 /data/app 之类的目录之外
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) return@forEach
                addFileRow(
                    cursor = cursor,
                    documentId = buildId(rel),
                    displayName = child.name,
                    file = child,
                    readOnly = readOnly,
                )
            }
    }

    private fun addFileRow(
        cursor: MatrixCursor,
        documentId: String,
        displayName: String,
        file: File,
        readOnly: Boolean,
        isRootDir: Boolean = false,
    ) {
        val isDir = file.isDirectory
        val flags = when {
            isRootDir -> Document.FLAG_DIR_SUPPORTS_CREATE
            readOnly -> 0
            isDir -> Document.FLAG_DIR_SUPPORTS_CREATE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE
            else -> Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(file))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun mimeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return ext.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun uniqueChild(parent: File, name: String): File {
        val safe = name.replace('/', '_').ifBlank { "untitled" }
        var candidate = File(parent, safe)
        if (!candidate.exists()) return candidate
        val stem = candidate.nameWithoutExtension
        val ext = candidate.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        do {
            candidate = File(parent, "$stem ($n)$ext")
            n++
        } while (candidate.exists())
        return candidate
    }

    /** 工作区文件区的真实目录：优先用工作区声明的目录，未设置时回落到默认 files 目录 */
    private fun workspaceBaseDir(root: String): File {
        val declared = allWorkspaces().firstOrNull { it.root == root }?.directory
        return manager().targetDir(root, declared).also { it.mkdirs() }
    }

    /** 解析绝对路径并校验不逃逸 [base] */
    private fun resolveInside(base: File, relPath: String): File {
        val canonicalBase = base.canonicalFile
        canonicalBase.mkdirs()
        val normalized = relPath.trim().trimStart('/')
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }
        if (normalized.isEmpty()) return canonicalBase
        val target = File(canonicalBase, normalized).canonicalFile
        require(target.path == canonicalBase.path || target.path.startsWith(canonicalBase.path + File.separator)) {
            "Path escapes root: $relPath"
        }
        return target
    }

    private data class TerminalBase(
        val key: String,
        val displayName: String,
        val dir: File,
        val readOnly: Boolean,
    )

    private fun terminalBases(): List<TerminalBase> {
        val ctx = context ?: return emptyList()
        val bases = mutableListOf<TerminalBase>()
        bases += TerminalBase(
            key = TOOLCHAIN_KEY,
            displayName = "toolchain",
            dir = File(ctx.filesDir, TOOLCHAIN_DIR),
            readOnly = true,
        )
        allWorkspaces()
            .sortedBy { it.name.lowercase() }
            .forEach { ws ->
                val workspaceDir = File(File(ctx.filesDir, WORKSPACES_DIR), ws.root)
                bases += TerminalBase(
                    key = "home:${ws.root}",
                    displayName = "${ws.name} · HOME",
                    dir = File(workspaceDir, "home"),
                    readOnly = false,
                )
                bases += TerminalBase(
                    key = "tmp:${ws.root}",
                    displayName = "${ws.name} · TMP",
                    dir = File(workspaceDir, "tmp"),
                    readOnly = false,
                )
            }
        return bases
    }

    private fun terminalBase(key: String): TerminalBase? = terminalBases().firstOrNull { it.key == key }

    private fun documentFile(target: DocId): Pair<File, Boolean> = when (target) {
        DocId.Root, DocId.TerminalRoot -> throw IllegalArgumentException("Not a document")
        is DocId.WorkspaceNode -> {
            require(target.relPath.isNotEmpty()) { "Not a document" }
            resolveInside(workspaceBaseDir(target.workspaceRoot), target.relPath) to false
        }

        is DocId.TerminalNode -> {
            val base = terminalBase(target.baseKey) ?: throw IllegalArgumentException("Unknown terminal dir")
            require(target.relPath.isNotEmpty()) { "Not a document" }
            resolveInside(base.dir, target.relPath) to base.readOnly
        }
    }

    private fun documentParent(target: DocId): CreateTarget = when (target) {
        DocId.Root, DocId.TerminalRoot -> throw IllegalArgumentException("Cannot use root as parent")
        is DocId.WorkspaceNode -> CreateTarget(
            dir = resolveInside(workspaceBaseDir(target.workspaceRoot), target.relPath),
            relPrefix = target.relPath,
            readOnly = false,
            buildId = { rel -> buildWorkspaceDocId(target.workspaceRoot, rel) },
        )

        is DocId.TerminalNode -> {
            val base = terminalBase(target.baseKey) ?: throw IllegalArgumentException("Unknown terminal dir")
            CreateTarget(
                dir = resolveInside(base.dir, target.relPath),
                relPrefix = target.relPath,
                readOnly = base.readOnly,
                buildId = { rel -> buildTerminalDocId(target.baseKey, rel) },
            )
        }
    }

    private fun parseDocId(documentId: String): DocId {
        if (documentId == ROOT_DOC_ID) return DocId.Root
        if (documentId == TERMINAL_ROOT_DOC_ID) return DocId.TerminalRoot
        if (documentId.startsWith(TERMINAL_PREFIX)) {
            val rest = documentId.removePrefix(TERMINAL_PREFIX)
            val idx = rest.indexOf('/')
            return if (idx < 0) {
                DocId.TerminalNode(baseKey = rest, relPath = "")
            } else {
                DocId.TerminalNode(baseKey = rest.substring(0, idx), relPath = rest.substring(idx + 1))
            }
        }
        require(documentId.startsWith(WORKSPACE_PREFIX)) { "Invalid documentId: $documentId" }
        val rest = documentId.removePrefix(WORKSPACE_PREFIX)
        val idx = rest.indexOf('/')
        return if (idx < 0) {
            DocId.WorkspaceNode(workspaceRoot = rest, relPath = "")
        } else {
            DocId.WorkspaceNode(workspaceRoot = rest.substring(0, idx), relPath = rest.substring(idx + 1))
        }
    }

    private fun buildWorkspaceDocId(root: String, relPath: String): String =
        if (relPath.isEmpty()) "$WORKSPACE_PREFIX$root" else "$WORKSPACE_PREFIX$root/$relPath"

    private fun buildTerminalDocId(baseKey: String, relPath: String): String =
        if (relPath.isEmpty()) "$TERMINAL_PREFIX$baseKey" else "$TERMINAL_PREFIX$baseKey/$relPath"

    private fun notifyChange(parentDocumentId: String) {
        val ctx = context ?: return
        val uri = DocumentsContract.buildChildDocumentsUri(
            ctx.packageName + ".documents",
            parentDocumentId,
        )
        ctx.contentResolver.notifyChange(uri, null)
    }

    private sealed interface DocId {
        data object Root : DocId
        data object TerminalRoot : DocId
        data class WorkspaceNode(val workspaceRoot: String, val relPath: String) : DocId
        data class TerminalNode(val baseKey: String, val relPath: String) : DocId
    }

    companion object {
        private const val ROOT_ID = "rikkahub_workspaces"
        private const val ROOT_DOC_ID = "root"
        private const val TERMINAL_ROOT_ID = "rikkahub_terminal"
        private const val TERMINAL_ROOT_DOC_ID = "tc"
        private const val WORKSPACE_PREFIX = "ws/"
        private const val TERMINAL_PREFIX = "tc/"
        private const val TOOLCHAIN_KEY = "toolchain"
        private const val WORKSPACES_DIR = "workspaces"
        private const val TOOLCHAIN_DIR = "toolchain"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
