/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

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
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
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

private const val TAG = "S3Sync"

class S3Sync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val conversationRepository: me.rerere.rikkahub.data.repository.ConversationRepository,
) {
    private var pendingBackupDbFile: File? = null
    private fun getS3Client(config: S3Config): S3Client {
        return S3Client(config, httpClient)
    }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        // Test by listing objects with max 1 result
        client.listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(config: S3Config) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        val client = getS3Client(config)
        val key = "rikkahub_backups/${file.name}"

        client.putObject(
            key = key,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()

        Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")

        // Clean up temp file
        file.delete()
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val result = client.listObjects(
            prefix = "rikkahub_backups/",
            maxKeys = 1000
        ).getOrThrow()

        result.objects
            .filter { it.key.startsWith("rikkahub_backups/backup_") && it.key.endsWith(".zip") }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast("/"),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restoreFromS3: Downloading ${item.displayName}")
            client.downloadObjectToFile(item.key, backupFile).getOrThrow()

            Log.i(TAG, "restoreFromS3: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restoreFromS3: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        client.deleteObject(item.key).getOrThrow()
        Log.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
    }

    suspend fun prepareBackupFile(config: S3Config): File = withContext(Dispatchers.IO) {
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
            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
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
            if (config.items.contains(S3Config.BackupItem.FILES)) {
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
            }
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: S3Config) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

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
                            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                                // 不直接覆盖数据库文件, 而是解压到临时位置稍后导入
                                if (zipEntry.name == "rikka_hub.db") {
                                    val tempDbFile = File(context.cacheDir, "temp_backup_rikka_hub.db")
                                    FileOutputStream(tempDbFile).use { outputStream ->
                                        zipIn.copyTo(outputStream)
                                    }
                                    pendingBackupDbFile = tempDbFile
                                    Log.i(TAG, "restoreFromBackupFile: Saved backup db to temp file")
                                }
                            }
                        }

                        else -> {
                            if (config.items.contains(S3Config.BackupItem.FILES) &&
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
                                        Log.e(TAG, "restoreFromBackupFile: Failed to restore file ${zipEntry.name}", e)
                                        throw Exception("Failed to restore file ${zipEntry.name}: ${e.message}")
                                    }
                                }
                            } else if (config.items.contains(S3Config.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.SKILLS}/")
                            ) {
                                restoreSkillEntry(zipIn, zipEntry.name)
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

        // 从备份数据库导入 conversations
        pendingBackupDbFile?.let { dbFile ->
            try {
                importConversationsFromBackupDb(dbFile)
            } finally {
                dbFile.delete()
                pendingBackupDbFile = null
            }
        }
    }

    /**
     * 从备份数据库文件中读取 conversations 并导入到当前数据库.
     */
    private suspend fun importConversationsFromBackupDb(backupDbFile: File) {
        if (!backupDbFile.exists()) return

        val sqliteDb = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                backupDbFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
        } catch (e: Exception) {
            Log.e(TAG, "importConversations: failed to open backup db", e)
            return
        }

        try {
            val tableCursor = sqliteDb.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='conversationentity'", null
            )
            val hasTable = tableCursor.use { it.moveToFirst() && it.count > 0 }
            if (!hasTable) return

            val columnCursor = sqliteDb.rawQuery("PRAGMA table_info(conversationentity)", null)
            val columns = mutableListOf<String>()
            columnCursor.use {
                while (it.moveToNext()) columns.add(it.getString(1))
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
            convCursor.use {
                while (it.moveToNext()) {
                    try {
                        val id = it.getString(it.getColumnIndexOrThrow("id"))
                        if (conversationRepository.existsConversationById(kotlin.uuid.Uuid.parse(id))) continue

                        val assistantId = it.getString(it.getColumnIndexOrThrow("assistant_id"))
                        val title = it.getString(it.getColumnIndexOrThrow("title"))
                        val nodesJson = it.getString(it.getColumnIndexOrThrow("nodes"))
                        val createAt = it.getLong(it.getColumnIndexOrThrow("create_at"))
                        val updateAt = it.getLong(it.getColumnIndexOrThrow("update_at"))
                        val chatSuggestionsJson = if (columns.contains("suggestions"))
                            it.getString(it.getColumnIndexOrThrow("suggestions")) ?: "[]" else "[]"
                        val isPinned = if (columns.contains("is_pinned"))
                            it.getInt(it.getColumnIndexOrThrow("is_pinned")) == 1 else false
                        val customSystemPrompt = if (columns.contains("custom_system_prompt"))
                            it.getString(it.getColumnIndexOrThrow("custom_system_prompt")) ?: "" else ""
                        val folderId = if (columns.contains("folder_id"))
                            it.getString(it.getColumnIndexOrThrow("folder_id")) ?: "" else ""

                        // 优先从 message_node 表读取消息节点; 否则回退到旧版 nodes JSON
                        val messageNodes = messageNodesByConversation[id]
                            ?: runCatching {
                                json.decodeFromString<List<me.rerere.rikkahub.data.model.MessageNode>>(nodesJson)
                            }.getOrDefault(emptyList())

                        // 剥离 base64 内嵌图片, 避免触发 require 校验失败导致整条对话被跳过
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
                        Log.e(TAG, "importConversations: failed", e)
                    }
                }
            }
            Log.i(TAG, "importConversations: imported $importedCount conversations")
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
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
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

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
