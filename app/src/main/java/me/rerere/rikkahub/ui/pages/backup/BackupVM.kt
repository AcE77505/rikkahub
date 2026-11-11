package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.BackupItem
import me.rerere.rikkahub.data.datastore.BackupSelection
import me.rerere.rikkahub.data.datastore.DEFAULT_BACKUP_ITEMS
import me.rerere.rikkahub.data.datastore.RestoreMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.importer.DeepSeekImportAction
import me.rerere.rikkahub.data.sync.importer.DeepSeekImportDecision
import me.rerere.rikkahub.data.sync.importer.DeepSeekImportResult
import me.rerere.rikkahub.data.sync.importer.DeepSeekImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.utils.UiState
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)

    /** 本地导出/导入勾选的备份项与恢复方式。 */
    val localBackupSelection = MutableStateFlow(
        BackupSelection(items = DEFAULT_BACKUP_ITEMS.toSet())
    )

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    fun updateLocalBackupItems(items: Collection<BackupItem>) {
        // 「所有可备份内容」优先：同时存在其它选项时忽略它们
        val normalized = if (BackupItem.ALL in items) setOf(BackupItem.ALL) else items.toSet()
        localBackupSelection.update { it.copy(items = normalized) }
    }

    fun updateLocalRestoreMode(item: BackupItem, mode: RestoreMode) {
        localBackupSelection.update { it.copy(modes = it.modes + (item to mode)) }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime(BackupChannel.WEBDAV)
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        // 导出只看勾选：本地文件不需要“恢复方式”
        val file = webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = localBackupSelection.value.items.toList())
        )
        recordBackupTime(BackupChannel.LOCAL)
        return file
    }

    suspend fun restoreFromLocalFile(file: File) {
        val selection = localBackupSelection.value
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.webDavConfig.copy(items = selection.effectiveItems.toList()),
            selection.modes,
        )
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.value
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            providers = currentSettings.providers,
            shouldImportConversation = { conversationId ->
                val exists = conversationRepository.existsConversationById(conversationId)
                if (exists) skippedExistingConversations++
                !exists
            },
            saveImage = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation)
                importedConversations++
            }
        )

        val targetAssistantId = currentSettings.assistantId
        settingsStore.update { latestSettings ->
            latestSettings.copy(
                providers = result.providers + latestSettings.providers.filterNot { existing ->
                    result.providers.any { imported -> imported.id == existing.id }
                },
                assistants = latestSettings.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        }

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "import ${result.importedImageParts} images, drop ${result.skippedImageParts} images, " +
                "skip ${result.skippedForkMessages} fork messages and ${result.skippedSessions} sessions"
        )
        ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            importedImageParts = result.importedImageParts,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
            skippedForkMessages = result.skippedForkMessages,
            skippedSessions = result.skippedSessions,
        )
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings {
            it.copy(
                providers = importProviders + it.providers,
            )
        }
    }

    /**
     * 导入前先扫描导出文件，和本地记录比较，找出新增、无变化和有变化的对话。
     * 有变化的对话交给用户决定（覆盖 / 保留 / 并存）。
     */
    suspend fun previewDeepSeek(file: File): DeepSeekImportPreview = withContext(Dispatchers.IO) {
        val summaries = DeepSeekImporter.scan(file)
        val states = conversationRepository.getImportStates(summaries.map { it.conversationId })
        val newConversations = mutableListOf<DeepSeekPreviewItem>()
        val unchangedConversations = mutableListOf<DeepSeekPreviewItem>()
        val changed = mutableListOf<DeepSeekChangedConversation>()
        val backfill = mutableMapOf<Uuid, Long>()
        var legacyCount = 0
        summaries.forEach { summary ->
            val state = states[summary.conversationId]
            if (state == null) {
                newConversations += DeepSeekPreviewItem(summary.conversationId, summary.title)
                return@forEach
            }
            // 有来源时间就精确比较；旧数据没有来源时间时，退回比较本地更新时间，
            // 这样「导入后没再动过」的对话不会被误判成有变化
            if (state.sourceUpdatedAt == null) legacyCount++
            val baseline = state.sourceUpdatedAt ?: state.updateAt
            val exportedAt = summary.updatedAt
            if (exportedAt != null && baseline == exportedAt) {
                unchangedConversations += DeepSeekPreviewItem(summary.conversationId, summary.title)
                if (state.sourceUpdatedAt == null) {
                    // 顺手补写来源时间，下次导入就能精确判断
                    backfill[summary.conversationId] = exportedAt
                }
            } else {
                changed += DeepSeekChangedConversation(
                    conversationId = summary.conversationId,
                    title = summary.title,
                    previousUpdatedAt = state.sourceUpdatedAt ?: state.updateAt,
                    updatedAt = exportedAt,
                )
            }
        }
        Log.i(
            TAG,
            "previewDeepSeek: new=${newConversations.size} unchanged=${unchangedConversations.size} " +
                "changed=${changed.size} legacyWithoutSource=$legacyCount"
        )
        DeepSeekImportPreview(
            newConversations = newConversations,
            unchangedConversations = unchangedConversations,
            changed = changed,
            existingIds = states.keys,
            backfillSourceTimes = backfill,
        )
    }

    /**
     * 导入 DeepSeek 官方数据导出（设置 → 数据管理 → 导出数据）里的聊天记录。
     * 导出包不含任何供应商配置，因此只落会话；[assistantId] 决定这些会话归到哪个助手。
     */
    suspend fun restoreFromDeepSeek(
        file: File,
        assistantId: Uuid = settings.value.assistantId,
        preview: DeepSeekImportPreview,
        decisions: Map<Uuid, DeepSeekImportDecision>,
    ): DeepSeekRestoreResult = withContext(Dispatchers.IO) {
        var importedConversations = 0
        val result = DeepSeekImporter.importStreaming(
            file = file,
            assistantId = assistantId,
            decisions = decisions,
            existingIds = preview.existingIds,
            onConversation = { conversation, action ->
                // 覆盖：先删掉本地会话（节点、收藏、FTS 一并清理），再写入导出内容
                if (action == DeepSeekImportAction.OVERWRITE) {
                    conversationRepository.deleteConversation(conversation)
                }
                conversationRepository.insertConversation(conversation)
                importedConversations++
            },
        )

        conversationRepository.backfillSourceUpdatedAt(preview.backfillSourceTimes)

        val keptTitles = preview.changed
            .filter { changed ->
                val decision = decisions[changed.conversationId]
                decision == null || decision == DeepSeekImportDecision.KEEP
            }
            .map { it.title }

        Log.i(
            TAG,
            "restoreFromDeepSeek: import $importedConversations conversations " +
                "(new ${result.newConversations}, overwrite ${result.overwrittenConversations}, " +
                "copy ${result.copiedConversations}), keep ${keptTitles.size}, " +
                "unchanged ${preview.unchangedCount}, " +
                "${result.reasoningParts} reasoning parts, ${result.searchParts} search parts, " +
                "${result.attachmentParts} attachment notices, ${result.emptyMessages} empty messages, " +
                "${result.branchMessages} branch messages, ${result.alternativeReplies} alternative replies, " +
                "drop ${result.skippedToolParts} tool steps / ${result.skippedTipParts} tips / " +
                "${result.unknownFragments} unknown fragments, " +
                "skip ${result.skippedConversations} unreadable conversations"
        )
        DeepSeekRestoreResult(
            result = result,
            importedConversations = importedConversations,
            keptTitles = keptTitles,
            unchangedConversations = preview.unchangedConversations.size,
        )
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime(BackupChannel.S3)
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        // 远程备份的恢复方式固定：对话/子代理合并，其余覆盖
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    /**
     * 记下一次成功备份。`lastBackupTime` 取任意渠道的最近一次，用于到期提醒；
     * WebDAV / S3 还各自记录一份，页面上只显示本渠道的时间。
     */
    private suspend fun recordBackupTime(channel: BackupChannel) {
        val now = System.currentTimeMillis()
        settingsStore.update { settings ->
            val config = settings.backupReminderConfig
            settings.copy(
                backupReminderConfig = config.copy(
                    lastBackupTime = now,
                    lastWebDavBackupTime = if (channel == BackupChannel.WEBDAV) now else config.lastWebDavBackupTime,
                    lastS3BackupTime = if (channel == BackupChannel.S3) now else config.lastS3BackupTime,
                )
            )
        }
    }

    private enum class BackupChannel { LOCAL, WEBDAV, S3 }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
)

data class DeepSeekRestoreResult(
    val result: DeepSeekImportResult,
    val importedConversations: Int,
    /** 有变化但用户选择保留的对话标题，按导出里的顺序 */
    val keptTitles: List<String>,
    /** 来源时间与导入记录一致、直接跳过的对话数 */
    val unchangedConversations: Int,
)

/** 导入前的扫描结果，按新增 / 有变化 / 无变化分类。 */
data class DeepSeekImportPreview(
    val newConversations: List<DeepSeekPreviewItem>,
    val unchangedConversations: List<DeepSeekPreviewItem>,
    val changed: List<DeepSeekChangedConversation>,
    val existingIds: Set<Uuid>,
    /** 旧数据与导出内容一致时补写的来源时间，导入完成后落库 */
    val backfillSourceTimes: Map<Uuid, Long> = emptyMap(),
) {
    val newCount: Int get() = newConversations.size
    val unchangedCount: Int get() = unchangedConversations.size
}

/** 预览列表里只需要展示标题的对话（新增 / 无变化）。 */
data class DeepSeekPreviewItem(
    val conversationId: Uuid,
    val title: String,
)

/** 本地已存在、但来源时间与导出不一致（或没有来源时间）的对话。 */
data class DeepSeekChangedConversation(
    val conversationId: Uuid,
    val title: String,
    val previousUpdatedAt: Long?,
    val updatedAt: Long?,
)
