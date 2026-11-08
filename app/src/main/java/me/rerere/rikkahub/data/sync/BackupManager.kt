package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.BackupFields
import me.rerere.rikkahub.data.datastore.BackupItem
import me.rerere.rikkahub.data.datastore.BackupResource
import me.rerere.rikkahub.data.datastore.BackupSelection
import me.rerere.rikkahub.data.datastore.BackupSettingsFields
import me.rerere.rikkahub.data.datastore.RestoreMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.datastore.normalized
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.MediaCreationFiles
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Shared archive format and restore lifecycle for local, WebDAV and S3 backups.
 *
 * 归档布局：
 * - `backup.json`：格式版本与本次包含的备份项
 * - `settings.json`：所选备份项拥有的设置字段（键名与 Settings 字段一致），外加 `launchCount`
 * - `tables.db`：所选备份项的数据库表，拼成一份独立、可校验的 SQLite 文件
 * - `database.db`：旧版整库备份
 * - `upload/`、`skills/`、`fonts/`、`media_creation/`、`ssh/`：文件备份
 *
 * 旧版归档没有 `backup.json`，`settings.json` 是完整设置、数据库是整库，按整份内容恢复。
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(selection: BackupSelection): File = withContext(Dispatchers.IO) {
        val items = selection.effectiveItems
        val resources = selection.resources
        // 选了「所有可备份内容」时输出旧版本也认识的格式：完整 settings.json + rikka_hub.db 整库条目
        val legacyCompatible = resources == BackupResource.entries.toSet()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = File.createTempFile("backup-", "", context.cacheDir).apply {
            check(delete() && mkdirs()) { "Cannot create backup staging directory" }
        }
        try {
            val settings = settingsStore.awaitLoaded()
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                putBytes(
                    zip,
                    MANIFEST,
                    json.encodeToString(BackupManifest(resources = resources.toList())).toByteArray(Charsets.UTF_8)
                )
                putBytes(
                    zip,
                    SETTINGS_JSON,
                    BackupFields.withLaunchCount(
                        if (legacyCompatible) {
                            BackupFields.all(settings)
                        } else {
                            BackupSettingsFields.extract(settings, resources)
                        },
                        settingsStore.launchCountFlow.first(),
                    ).toByteArray(Charsets.UTF_8),
                )
                if (resources.any { DatabaseBackupTables.hasTables(it) }) {
                    val snapshot = File(staging, TABLES_DATABASE)
                    if (DatabaseBackupTables.export(
                            context,
                            database.openHelper.writableDatabase,
                            resources,
                            snapshot
                        )) {
                        addFile(
                            zip,
                            snapshot,
                            if (legacyCompatible) DatabaseBackup.ARCHIVE_DATABASE else TABLES_DATABASE,
                        )
                    }
                }
                if (BackupResource.FILES in resources) {
                    for (folder in ATTACHMENT_FOLDERS) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder in NESTED_ATTACHMENT_FOLDERS) {
                            directory.walkTopDown().asSequence()
                        } else {
                            directory.listFiles().orEmpty().asSequence()
                        }
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            // A media result still being downloaded is incomplete and gets renamed when it finishes.
                            if (folder == FileFolders.MEDIA_CREATION &&
                                file.name.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
                            ) {
                                continue
                            }
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            addFile(zip, file, "$folder/$relative")
                        }
                    }
                }
                if (BackupResource.WORKSPACES in resources) addDirectory(zip, File(context.filesDir, SSH_DIR), SSH_DIR)
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(archive: File, selection: BackupSelection) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedTables = File(staging, TABLES_DATABASE)
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    var plan: RestoreDatabasePlan? = null
                    ZipFile(archive).use { zip ->
                        val manifest = zip.getEntry(MANIFEST)?.let { entry ->
                            json.decodeFromString<BackupManifest>(
                                zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                            )
                        }
                        // 旧版归档没有清单：按原本就是“整库 + 完整设置”的内容恢复
                        val legacy = manifest == null
                        // 备份里实际包含的资源；旧版归档没有清单，按“整库 + 完整设置”处理
                        val available = manifest?.resources?.toSet() ?: BackupResource.entries.toSet()
                        val resources = selection.resources.filter { it in available }.toSet()
                        val databaseResources = resources.filter { DatabaseBackupTables.hasTables(it) }
                        // 所选内容必须真的出现在这份归档里，否则说明用户选的项根本不在备份中
                        check(databaseResources.isNotEmpty() || resources.intersect(SEARCHABLE_ITEMS).isNotEmpty()) {
                            "No selected data found in the backup"
                        }
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            val target = entryTarget(
                                name = entry.name,
                                legacy = legacy,
                                resources = resources,
                                databaseResources = databaseResources,
                                staging = staging,
                                payload = payload,
                                stagedTables = stagedTables,
                            ) ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                        if (restoredEntries == 0) error("No selected data found in the backup")
                        require(
                            !stagedWal.exists() || stagedDatabase.exists()
                        ) { "Backup WAL has no matching database" }
                        plan = prepareDatabase(context, stagedTables, stagedDatabase, selection, databaseResources)

                        val settingsFile = File(staging, SETTINGS_JSON)
                        if (settingsFile.exists()) {
                            val migrated = SettingsJsonMigrator.migrate(settingsFile.readText())
                            val backedUp = json.decodeFromString<Settings>(migrated)
                            val fieldsFor = if (legacy) BackupResource.entries else resources
                            // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                            PendingRestore.writeDurably(
                                settingsFile,
                                BackupFields.withLaunchCount(
                                    BackupSettingsFields.extract(backedUp, fieldsFor),
                                    BackupFields.launchCountOf(migrated),
                                ),
                            )
                        }
                        plan?.let { PendingRestore.writeDurably(File(staging, DATABASE_PLAN), json.encodeToString(it)) }
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    /** 决定归档条目落到暂存目录的哪个位置；不需要恢复的条目返回 null。 */
    private fun entryTarget(
        name: String,
        legacy: Boolean,
        resources: Set<BackupResource>,
        databaseResources: List<BackupResource>,
        staging: File,
        payload: File,
        stagedTables: File,
    ): File? = when {
        name == SETTINGS_JSON -> File(staging, SETTINGS_JSON)
        name == TABLES_DATABASE -> if (databaseResources.isNotEmpty()) stagedTables else null
        name == DatabaseBackup.ARCHIVE_DATABASE ->
            // 旧版归档和本版本的「所有可备份内容」都用这个文件名
            if (databaseResources.isNotEmpty()) {
                File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
            } else {
                null
            }
        name == DatabaseBackup.WAL ->
            if (databaseResources.isNotEmpty()) {
                File(payload, "database/${DatabaseBackup.ARCHIVE_DATABASE}-wal")
            } else {
                null
            }
        name == DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
        BackupResource.WORKSPACES in resources && name.startsWith("$SSH_DIR/") -> {
            require('/' in name.removePrefix("$SSH_DIR/") && name.count { it == '/' } == 1) {
                "Invalid backup file: $name"
            }
            PendingRestore.resolveInside(File(payload, "files"), name)
        }
        BackupResource.FILES in resources && isAttachment(
            name
        ) -> PendingRestore.resolveInside(File(payload, "files"), name)
        else -> null
    }

    /**
     * 校验备份库并生成写入计划。
     *
     * 分表备份只写归档里真正存在的表；旧版整库备份按所选数据库项整库覆盖。
     */
    private fun prepareDatabase(
        context: Context,
        stagedTables: File,
        stagedDatabase: File,
        selection: BackupSelection,
        databaseResources: List<BackupResource>,
    ): RestoreDatabasePlan? {
        if (stagedDatabase.exists()) {
            DatabaseBackup.normalize(context, stagedDatabase)
            // Reject unsupported schemas before publishing; run supported old migrations on the copy.
            val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
            try {
                DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
            } finally {
                room.close()
            }
            DatabaseBackup.removeSidecars(stagedDatabase)
            // 旧版整库：每张表按各自备份项的恢复方式处理
            val overwrite = mutableListOf<String>()
            val merge = mutableListOf<String>()
            for (resource in databaseResources) {
                val tables = DatabaseBackupTables.tablesOf(resource)
                when (selection.modeOf(resource)) {
                    RestoreMode.OVERWRITE -> overwrite += tables
                    RestoreMode.MERGE -> merge += tables
                }
            }
            return planOf(SQLiteConfiguration.DATABASE_NAME, overwrite, merge)
        }
        if (!stagedTables.exists()) return null
        // 分表备份的库里只应有它自己的表，能打开即可
        DatabaseBackup.normalize(context, stagedTables)
        val overwrite = mutableListOf<String>()
        val merge = mutableListOf<String>()
        for (resource in databaseResources) {
            val tables = DatabaseBackupTables.existingTables(context, stagedTables, resource)
            // 备份里一张表都没有说明内容与所选资源不符（例如表名变过），宁可报错也不要静默跳过
            require(tables.isNotEmpty()) { "Backup is missing ${resource.name.lowercase()} data" }
            when (selection.modeOf(resource)) {
                RestoreMode.OVERWRITE -> overwrite += tables
                RestoreMode.MERGE -> merge += tables
            }
        }
        if (overwrite.isEmpty() && merge.isEmpty()) {
            // 选了数据库项但备份里没有对应的表：视为备份内容与选择不符，直接失败，避免静默跳过
            error("Backup is missing the selected data")
        }
        return planOf(TABLES_DATABASE, overwrite, merge)
    }

    /** 同一张表只出现在一种恢复方式里：覆盖优先，避免先清空再合并两次写入。 */
    private fun planOf(source: String, overwrite: List<String>, merge: List<String>): RestoreDatabasePlan {
        val overwriteTables = overwrite.distinct()
        return RestoreDatabasePlan(
            source = source,
            overwrite = overwriteTables,
            merge = merge.distinct().filterNot { it in overwriteTables }
        )
    }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        if (!directory.isDirectory) return
        for (file in directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }) {
            addFile(zip, file, "$prefix/${file.name}")
        }
    }

    companion object {
        private const val MANIFEST = "backup.json"
        private const val SETTINGS_JSON = "settings.json"
        private const val TABLES_DATABASE = "tables.db"
        internal const val DATABASE_PLAN = "database-plan.json"
        private const val SSH_DIR = "ssh"

        private val ATTACHMENT_FOLDERS =
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.MEDIA_CREATION)

        /** Backed up with their subdirectories; the other folders only contain top-level files. */
        private val NESTED_ATTACHMENT_FOLDERS = setOf(FileFolders.SKILLS, FileFolders.MEDIA_CREATION)

        /** 只靠文件或键值就能恢复的项（不涉及数据库表）。 */
        private val SEARCHABLE_ITEMS = setOf(
            BackupResource.SETTINGS,
            BackupResource.MCP,
            BackupResource.SEARCH,
            BackupResource.VOICE,
            BackupResource.MEDIA_GENERATION,
            BackupResource.ASSISTANTS,
            BackupResource.FILES,
            BackupResource.WORKSPACES,
        )

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            val pending = File(context.noBackupFilesDir, "backup-restore/pending")
            val plan = File(pending, DATABASE_PLAN).takeIf { it.isFile }?.let { file ->
                runCatching { json.decodeFromString<RestoreDatabasePlan>(file.readText()) }.getOrNull()
            }
            pendingRestore(context).apply { settingsJson ->
                if (plan != null) DatabaseBackupTables.import(context, plan, File(pending, plan.source))
                val fields = BackupFields.fieldsOf(settingsJson)
                if (fields.isEmpty()) {
                    SettingsStore.restoreBeforeInitialization(
                        context = context,
                        settings = SettingsStore.loadFromDiskBeforeInitialization(context).normalized(),
                        launchCount = BackupFields.launchCountOf(settingsJson),
                    )
                } else {
                    SettingsStore.patchBeforeInitialization(context, fields)
                }
            }
        }
    }
}

/** 恢复时写入数据库的计划。 */
@kotlinx.serialization.Serializable
internal data class RestoreDatabasePlan(
    /** 暂存目录里的备份库文件名 */
    val source: String,
    /** 整体替换的表 */
    val overwrite: List<String> = emptyList(),
    /** 按主键合并的表 */
    val merge: List<String> = emptyList(),
)

/** 备份归档的清单。 */
@kotlinx.serialization.Serializable
internal data class BackupManifest(
    val version: Int = 2,
    /** 这份归档里实际包含的资源 */
    val resources: List<BackupResource> = BackupResource.entries,
)
