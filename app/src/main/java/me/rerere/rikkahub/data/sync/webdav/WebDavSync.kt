/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.repository.PluginSettingsExport
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "WebDavSync"

/**
 * 备份恢复结果摘要, 用于向用户展示恢复详情 (诊断导入问题):
 * 备份里有没有数据库/WAL、检测到多少条对话、导入/跳过/失败各多少条等.
 */
data class RestoreSummary(
    val dbInBackup: Boolean = false, // 备份 zip 里包含主数据库文件
    val walInBackup: Boolean = false, // 备份 zip 里包含 WAL 日志文件
    val detected: Int = 0, // 备份中检测到的对话总数
    val imported: Int = 0, // 成功导入条数
    val skipped: Int = 0, // 已存在跳过条数
    val failed: Int = 0, // 导入失败条数
    val firstError: String? = null, // 首条失败原因
    val remappedAssistant: Int = 0, // 原助手不存在被重新归属的条数
    val folderedCount: Int = 0, // 处于分组/文件夹中的对话条数
    val assistantDistribution: List<Pair<String, Int>> = emptyList(), // 备份对话所属助手 (名字 -> 条数)
    val currentAssistantName: String? = null, // 当前选中的助手名
)

class WebDavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val pluginRepository: PluginRepository,
    private val conversationRepository: me.rerere.rikkahub.data.repository.ConversationRepository,
) {
    private var pendingBackupDbFile: File? = null

    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, includePlugins = false)
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        // Upload the backup file
        client.put(
            path = file.name,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()

        Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")

        // Clean up temp file
        file.delete()
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config, includePlugins = false)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig): RestoreSummary = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        try {
            // 本地文件导入是用户明确选择的完整备份, 不受 WebDAV 同步条目开关的限制,
            // 恢复 zip 中存在的全部内容 (条目开关只应影响 WebDAV/S3 自动备份的导出范围)
            val fullConfig = config.copy(items = WebDavConfig.BackupItem.entries.toSet())
            val summary = restoreFromBackupFile(file, fullConfig, includePlugins = true)
            Log.i(TAG, "restoreFromLocalFile: Restore completed successfully")
            summary
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
            throw Exception("Restore failed: ${e.message}")
        }
    }

    suspend fun prepareBackupFile(
        config: WebDavConfig,
        includePlugins: Boolean = true
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        // Create zip file and backup data
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value)
            )

            // Backup database files
            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) {
                    addFileToZip(zipOut, dbFile, "rikka_hub.db")
                }

                val walFile = File(dbFile.parentFile, "rikka_hub-wal")
                if (walFile.exists()) {
                    addFileToZip(zipOut, walFile, "rikka_hub-wal")
                }

                val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
                if (shmFile.exists()) {
                    addFileToZip(zipOut, shmFile, "rikka_hub-shm")
                }
            }

            // Backup app files
            if (config.items.contains(WebDavConfig.BackupItem.FILES)) {
                val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                if (uploadFolder.exists() && uploadFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up files from ${uploadFolder.absolutePath}")
                    uploadFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Upload folder does not exist or is not a directory")
                }

                val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                if (skillsFolder.exists() && skillsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up skills from ${skillsFolder.absolutePath}")
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = skillsFolder,
                        currentDir = skillsFolder,
                        entryPrefix = "${FileFolders.SKILLS}/"
                    )
                } else {
                    Log.w(TAG, "prepareBackupFile: Skills folder does not exist or is not a directory")
                }

                // Backup plugin settings and plugin folders (only for local backup/export)
                if (includePlugins) {
                    try {
                        val pluginSettings = pluginRepository.exportPluginSettings()
                        addVirtualFileToZip(
                            zipOut = zipOut,
                            name = "plugin_settings.json",
                            content = json.encodeToString(pluginSettings)
                        )
                        Log.i(TAG, "prepareBackupFile: Backed up plugin settings")
                    } catch (e: Exception) {
                        Log.e(TAG, "prepareBackupFile: Failed to back up plugin settings", e)
                    }

                    val pluginsFolder = PluginScanner(context).pluginsDir
                    if (pluginsFolder.exists() && pluginsFolder.isDirectory) {
                        Log.i(TAG, "prepareBackupFile: Backing up plugins from ${pluginsFolder.absolutePath}")
                        addDirectoryToZip(
                            zipOut = zipOut,
                            rootDir = pluginsFolder,
                            currentDir = pluginsFolder,
                            entryPrefix = "${PluginScanner.PLUGINS_DIR}/"
                        )
                    } else {
                        Log.w(TAG, "prepareBackupFile: Plugins folder does not exist or is not a directory")
                    }
                }
            }
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(
        backupFile: File,
        config: WebDavConfig,
        includePlugins: Boolean = true
    ): RestoreSummary = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

        // 备份中数据库文件的存在情况 (诊断用)
        var dbInBackup = false
        var walInBackup = false

        ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
            var entry: ZipEntry?
            while (zipIn.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                    when (zipEntry.name) {
                        "settings.json" -> {
                            val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                            Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                            try {
                                val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                val settings = json.decodeFromString<Settings>(migratedJson)
                                settingsStore.update(settings)
                                Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                            } catch (e: Exception) {
                                Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                throw Exception("Failed to restore settings: ${e.message}")
                            }
                        }

                        "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                            // 记录备份里实际包含哪些数据库文件 (诊断用, 不受条目开关影响)
                            if (zipEntry.name == "rikka_hub.db") dbInBackup = true
                            if (zipEntry.name == "rikka_hub-wal") walInBackup = true
                            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                // 不再直接覆盖数据库文件 (会导致 Room 迁移崩溃)
                                // 而是将 db 文件解压到临时位置, 稍后读取 conversations 导入到当前数据库
                                if (zipEntry.name == "rikka_hub.db") {
                                    val tempDbFile = File(context.cacheDir, "temp_backup_rikka_hub.db")
                                    FileOutputStream(tempDbFile).use { outputStream ->
                                        zipIn.copyTo(outputStream)
                                    }
                                    pendingBackupDbFile = tempDbFile
                                    Log.i(TAG, "restoreFromBackupFile: Saved backup db to temp file")
                                }
                                // WAL 必须与主库一起解压: WAL 模式下最近的写入 (包括新对话)
                                // 都在 -wal 文件里, 主库可能还是未 checkpoint 的旧快照,
                                // 只读主库会丢掉全部近期对话 ("导入成功但没有聊天记录"的根因).
                                // 文件名遵循 SQLite 约定 (主库文件名 + "-wal"),
                                // 后续 importConversationsFromBackupDb 打开时自动回放.
                                if (zipEntry.name == "rikka_hub-wal") {
                                    val tempWalFile = File(context.cacheDir, "temp_backup_rikka_hub.db-wal")
                                    FileOutputStream(tempWalFile).use { outputStream ->
                                        zipIn.copyTo(outputStream)
                                    }
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Saved backup wal to temp file " +
                                            "(${tempWalFile.length()} bytes)"
                                    )
                                }
                                // shm 不需要: SQLite 打开时会自动重建
                            }
                        }

                        else -> {
                            if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.UPLOAD}/")
                            ) {
                                val fileName = zipEntry.name.substringAfter("${FileFolders.UPLOAD}/")
                                if (fileName.isNotEmpty()) {
                                    val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                                    if (!uploadFolder.exists()) {
                                        uploadFolder.mkdirs()
                                        Log.i(TAG, "restoreFromBackupFile: Created upload directory")
                                    }

                                    val targetFile = File(uploadFolder, fileName)
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restoring file ${zipEntry.name} to ${targetFile.absolutePath}"
                                    )

                                    try {
                                        FileOutputStream(targetFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                        )
                                    } catch (e: Exception) {
                                        // 单个文件失败跳过, 不中断整个恢复
                                        Log.e(TAG, "restoreFromBackupFile: Failed to restore file ${zipEntry.name}, skip", e)
                                    }
                                }
                            } else if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.SKILLS}/")
                            ) {
                                restoreSkillEntry(zipIn, zipEntry.name)
                            } else if (includePlugins && zipEntry.name == "plugin_settings.json") {
                                try {
                                    val pluginSettingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                    val pluginSettings = json.decodeFromString<PluginSettingsExport>(pluginSettingsJson)
                                    pluginRepository.importPluginSettings(pluginSettings)
                                    Log.i(TAG, "restoreFromBackupFile: Restored plugin settings")
                                } catch (e: Exception) {
                                    Log.e(TAG, "restoreFromBackupFile: Failed to restore plugin settings", e)
                                }
                            } else if (includePlugins &&
                                config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${PluginScanner.PLUGINS_DIR}/")
                            ) {
                                restorePluginEntry(zipIn, zipEntry.name)
                            } else {
                                Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                            }
                        }
                    }

                    zipIn.closeEntry()
                }
            }
        }

        Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")

        // 从备份数据库导入 conversations (不覆盖当前数据库, 避免 Room 迁移崩溃)
        val convSummary = pendingBackupDbFile?.let { dbFile ->
            try {
                importConversationsFromBackupDb(dbFile)
            } finally {
                dbFile.delete()
                // 同步清理 wal/shm 临时文件, 避免残留干扰下次导入
                File(dbFile.parentFile, dbFile.name + "-wal").delete()
                File(dbFile.parentFile, dbFile.name + "-shm").delete()
                pendingBackupDbFile = null
            }
        }
        convSummary?.copy(dbInBackup = true, walInBackup = walInBackup)
            ?: RestoreSummary(dbInBackup = dbInBackup, walInBackup = walInBackup)
    }

    /**
     * 从备份数据库文件中读取 conversations 并导入到当前数据库.
     * 不直接覆盖数据库文件, 避免 Room 版本迁移导致崩溃.
     */
    private suspend fun importConversationsFromBackupDb(backupDbFile: File) {
        if (!backupDbFile.exists()) {
            Log.w(TAG, "importConversations: backup db file not found")
            return
        }

        // 先清掉上次导入可能残留的 shm (与本次 wal 不匹配会干扰回放)
        File(backupDbFile.parentFile, backupDbFile.name + "-shm").delete()

        // 用 READWRITE 打开: WAL 回放需要写 shm 文件, 只读打开带 wal 的库
        // 会直接报错. 打开后 SQLite 首次读取时自动回放 WAL,
        // 主库 + WAL 里的对话都能查到.
        val sqliteDb = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                backupDbFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
        } catch (e: Exception) {
            // WAL 损坏等极端情况: 删掉 wal/shm 退回只读打开 (旧行为兜底, 至少保住主库数据)
            Log.e(TAG, "importConversations: failed to open db with wal, fallback to main db only", e)
            File(backupDbFile.parentFile, backupDbFile.name + "-wal").delete()
            File(backupDbFile.parentFile, backupDbFile.name + "-shm").delete()
            try {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    backupDbFile.absolutePath, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
            } catch (e2: Exception) {
                Log.e(TAG, "importConversations: failed to open backup db", e2)
                return
            }
        }

        try {
            // 检查 conversationentity 表是否存在
            val tableCursor = sqliteDb.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='conversationentity'", null
            )
            val hasTable = tableCursor.use { it.moveToFirst() && it.count > 0 }
            if (!hasTable) {
                Log.w(TAG, "importConversations: conversationentity table not found in backup")
                return
            }

            // 获取列名, 兼容旧版 schema (可能缺少 folder_id 等列)
            val columnCursor = sqliteDb.rawQuery("PRAGMA table_info(conversationentity)", null)
            val columns = mutableListOf<String>()
            columnCursor.use {
                while (it.moveToNext()) {
                    columns.add(it.getString(1))
                }
            }

            // 新版备份将消息存在 message_node 表 (conversationentity.nodes 恒为 "[]"),
            // 因此需要额外读取该表, 否则导入的对话会没有消息.
            val messageNodeTableCursor = sqliteDb.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='message_node'", null
            )
            val hasMessageNodeTable = messageNodeTableCursor.use { it.moveToFirst() && it.count > 0 }
            val messageNodesByConversation = if (hasMessageNodeTable) {
                loadMessageNodesFromBackup(sqliteDb)
            } else {
                emptyMap()
            }

            val convCursor = sqliteDb.rawQuery("SELECT * FROM conversationentity", null)
            var importedCount = 0
            var skippedCount = 0

            convCursor.use {
                while (it.moveToNext()) {
                    try {
                        val id = it.getString(it.getColumnIndexOrThrow("id"))
                        val assistantId = it.getString(it.getColumnIndexOrThrow("assistant_id"))
                        val title = it.getString(it.getColumnIndexOrThrow("title"))
                        val nodesJson = it.getString(it.getColumnIndexOrThrow("nodes"))
                        val createAt = it.getLong(it.getColumnIndexOrThrow("create_at"))
                        val updateAt = it.getLong(it.getColumnIndexOrThrow("update_at"))
                        val chatSuggestionsJson = if (columns.contains("suggestions")) {
                            it.getString(it.getColumnIndexOrThrow("suggestions")) ?: "[]"
                        } else "[]"
                        val isPinned = if (columns.contains("is_pinned")) {
                            it.getInt(it.getColumnIndexOrThrow("is_pinned")) == 1
                        } else false
                        val customSystemPrompt = if (columns.contains("custom_system_prompt")) {
                            it.getString(it.getColumnIndexOrThrow("custom_system_prompt")) ?: ""
                        } else ""
                        val folderId = if (columns.contains("folder_id")) {
                            it.getString(it.getColumnIndexOrThrow("folder_id")) ?: ""
                        } else ""

                        // 检查是否已存在
                        if (conversationRepository.existsConversationById(
                                kotlin.uuid.Uuid.parse(id)
                            )
                        ) {
                            skippedCount++
                            continue
                        }

                        // 优先从 message_node 表读取消息节点; 否则回退到旧版 nodes JSON
                        val messageNodes = messageNodesByConversation[id]
                            ?: parseMessageNodesFromBackup(nodesJson)

                        // 剥离 base64 内嵌图片, 避免触发 ConversationRepository 中的
                        // require(...) 校验失败导致整条对话被跳过.
                        val cleanedNodes = stripBase64ImageParts(messageNodes)

                        val conversation = me.rerere.rikkahub.data.model.Conversation(
                            id = kotlin.uuid.Uuid.parse(id),
                            assistantId = kotlin.uuid.Uuid.parse(assistantId),
                            title = title,
                            messageNodes = cleanedNodes,
                            chatSuggestions = runCatching {
                                json.decodeFromString<List<String>>(chatSuggestionsJson)
                            }.getOrDefault(emptyList()),
                            isPinned = isPinned,
                            createAt = java.time.Instant.ofEpochMilli(createAt),
                            updateAt = java.time.Instant.ofEpochMilli(updateAt),
                            customSystemPrompt = customSystemPrompt.ifEmpty { null },
                            folderId = folderId.ifEmpty { null }?.let { kotlin.uuid.Uuid.parse(it) },
                        )

                        conversationRepository.insertConversation(conversation)
                        importedCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "importConversations: failed to import conversation", e)
                    }
                }
            }

            Log.i(TAG, "importConversations: imported $importedCount, skipped $skippedCount")
        } finally {
            sqliteDb.close()
        }
    }

    /**
     * 从备份数据库的 message_node 表读取所有消息节点, 按 conversation_id 分组.
     */
    private fun loadMessageNodesFromBackup(
        sqliteDb: android.database.sqlite.SQLiteDatabase
    ): Map<String, List<me.rerere.rikkahub.data.model.MessageNode>> {
        val result = mutableMapOf<String, MutableList<me.rerere.rikkahub.data.model.MessageNode>>()
        val cursor = sqliteDb.rawQuery(
            "SELECT id, conversation_id, node_index, messages, select_index FROM message_node ORDER BY conversation_id, node_index",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                try {
                    val nodeId = it.getString(it.getColumnIndexOrThrow("id"))
                    val conversationId = it.getString(it.getColumnIndexOrThrow("conversation_id"))
                    val messagesJson = it.getString(it.getColumnIndexOrThrow("messages"))
                    val selectIndex = it.getInt(it.getColumnIndexOrThrow("select_index"))

                    val messages = runCatching {
                        json.decodeFromString<List<me.rerere.ai.ui.UIMessage>>(messagesJson)
                    }.getOrElse { e ->
                        Log.e(TAG, "loadMessageNodesFromBackup: failed to parse messages", e)
                        emptyList()
                    }

                    val node = me.rerere.rikkahub.data.model.MessageNode(
                        id = kotlin.uuid.Uuid.parse(nodeId),
                        messages = messages,
                        selectIndex = selectIndex,
                    )
                    result.getOrPut(conversationId) { mutableListOf() }.add(node)
                } catch (e: Exception) {
                    Log.e(TAG, "loadMessageNodesFromBackup: failed to read node", e)
                }
            }
        }
        return result
    }

    /**
     * 剥离消息中内嵌的 base64 图片, 避免触发 require 校验失败.
     */
    private fun stripBase64ImageParts(
        nodes: List<me.rerere.rikkahub.data.model.MessageNode>
    ): List<me.rerere.rikkahub.data.model.MessageNode> {
        return nodes.map { node ->
            node.copy(
                messages = node.messages.map { msg ->
                    msg.copy(
                        parts = msg.parts.map { part ->
                            if (part is me.rerere.ai.ui.UIMessagePart.Image && part.url.startsWith("data:")) {
                                part.copy(url = "")
                            } else {
                                part
                            }
                        }
                    )
                }
            )
        }
    }

    /**
     * 解析备份数据库中的 nodes JSON 为 List<MessageNode>.
     * 兼容旧版格式 (nodes 直接存 List<MessageNode> 的 JSON).
     */
    private fun parseMessageNodesFromBackup(nodesJson: String?): List<me.rerere.rikkahub.data.model.MessageNode> {
        if (nodesJson.isNullOrBlank() || nodesJson == "[]") return emptyList()
        return runCatching {
            json.decodeFromString<List<me.rerere.rikkahub.data.model.MessageNode>>(nodesJson)
        }.getOrElse { e ->
            Log.e(TAG, "parseMessageNodes: failed to parse nodes JSON", e)
            emptyList()
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun restorePluginEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${PluginScanner.PLUGINS_DIR}/")
        if (relativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid plugin entry $entryName")
            return
        }

        val pluginsRoot = PluginScanner(context).pluginsDir.apply { mkdirs() }
        val targetFile = File(pluginsRoot, relativePath)
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored plugin file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            // 插件文件恢复失败不中断整个恢复 (典型场景: 未授予所有文件访问权限时 EACCES).
            // 聊天记录和设置的恢复优先级更高; 失败的插件文件记日志跳过.
            Log.e(TAG, "restoreFromBackupFile: Failed to restore plugin file $entryName, skip", e)
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
            return
        }

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
        if (skillDir == null) {
            // 无效路径跳过而不是中断, 聊天记录恢复优先
            Log.e(TAG, "restoreFromBackupFile: Invalid skill directory $entryName, skip")
            return
        }
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
        if (targetFile == null) {
            Log.e(TAG, "restoreFromBackupFile: Invalid skill file path $entryName, skip")
            return
        }

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            // 技能文件恢复失败不中断整个恢复, 记日志跳过
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName, skip", e)
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
