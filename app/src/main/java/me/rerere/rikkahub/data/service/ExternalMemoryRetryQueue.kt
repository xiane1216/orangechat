/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.rerere.rikkahub.EXTERNAL_MEMORY_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import java.io.File

/**
 * 外置记忆库（进阶记忆）保存失败自动重试队列。
 *
 * - 保存失败的消息进入队列并持久化到应用私有目录（进程被杀后重启仍会继续重试）
 * - 网络类错误按指数退避重试（15s/30s/1m/2m/4m/8m/15m 封顶），网络恢复后自动完成同步
 * - 4xx 永久性错误（除 429）或重试超过上限后放弃，并发送失败通知
 *   （可用进阶记忆页面的「补传历史消息」找回）
 * - 在 RikkaHubApp.onCreate 中调用 init() 初始化
 */
object ExternalMemoryRetryQueue {
    private const val TAG = "ExternalMemoryRetryQueue"
    private const val FILE_NAME = "external_memory_retry_queue.json"

    // 状态通知（重试中，队列清空后自动取消）
    private const val STATUS_NOTIFICATION_ID = 40021
    // 放弃通知（重试耗尽，需要用户介入）
    private const val FAILED_NOTIFICATION_ID = 40022

    // 网络类错误最大重试次数；4xx 永久性错误不重试直接放弃
    private const val MAX_ATTEMPTS = 8
    // 队列容量上限，超过后丢弃最旧的并通知（防止无限增长）
    private const val MAX_QUEUE_SIZE = 500
    // 重试退避基数: 15s, 30s, 1m, 2m, 4m, 8m, 15m(封顶)
    private const val BASE_BACKOFF_MS = 15_000L
    private const val MAX_BACKOFF_MS = 15 * 60_000L

    @Serializable
    data class PendingItem(
        val configId: String,
        val assistantId: String,
        val conversationId: String,
        val role: String,
        val content: String,
        val originalCreatedAt: String? = null,
        var attempts: Int = 0,
        var nextRetryAt: Long = 0L,
    )

    private var appContext: Context? = null
    private var settingsStore: SettingsStore? = null
    private var scope: AppScope? = null

    // 保护 pending/loaded 的内存与磁盘一致性
    private val mutex = Mutex()
    private val pending = mutableListOf<PendingItem>()
    private var loaded = false

    // 仅在 synchronized(this) 内读写；scope 为 AppScope（Main 调度器），launch 由 ensureLoop 统一发起
    @Volatile
    private var retryJob: Job? = null

    /**
     * 在 Application.onCreate（主线程）中调用，加载持久化队列并恢复重试循环。
     * Android 保证同一进程内 Application.onCreate 先于任何 Service/Activity 执行，
     * 因此后续 enqueue 一定发生在 init 之后。
     */
    fun init(context: Context, settingsStore: SettingsStore, scope: AppScope) {
        if (appContext != null) return
        appContext = context.applicationContext
        this.settingsStore = settingsStore
        this.scope = scope
        scope.launch {
            runCatching {
                val count = mutex.withLock {
                    ensureLoadedLocked()
                    pending.size
                }
                if (count > 0) {
                    Log.i(TAG, "Restored $count pending external memory messages")
                    notifyRetryStatus(count)
                    ensureLoop()
                }
            }.onFailure {
                Log.e(TAG, "Failed to load retry queue", it)
            }
        }
    }

    /**
     * 保存失败后入队（挂起函数，可从任意线程的协程中调用）。
     * config 仅取 id —— 重试时按 id 从当前设置中重新解析，
     * 这样用户中途修改 URL/Key/表名后重试会使用新配置。
     */
    suspend fun enqueue(
        config: ExternalMemory,
        assistantId: String,
        conversationId: String,
        role: String,
        content: String,
        originalCreatedAt: String?,
    ) {
        if (appContext == null) {
            Log.w(TAG, "enqueue called before init, dropping message")
            return
        }
        val count = mutex.withLock {
            ensureLoadedLocked()
            if (pending.size >= MAX_QUEUE_SIZE) {
                val dropped = pending.removeAt(0)
                Log.w(TAG, "Retry queue overflow, dropped oldest item (${dropped.content.take(30)}...)")
            }
            pending.add(
                PendingItem(
                    configId = config.id.toString(),
                    assistantId = assistantId,
                    conversationId = conversationId,
                    role = role,
                    content = content,
                    originalCreatedAt = originalCreatedAt,
                    attempts = 0,
                    nextRetryAt = System.currentTimeMillis() + BASE_BACKOFF_MS,
                )
            )
            persistLocked()
            pending.size
        }
        notifyRetryStatus(count)
        ensureLoop()
    }

    /**
     * 确保重试循环在运行（queue 为空时循环自然退出，入队后重新拉起）
     */
    private fun ensureLoop() {
        val sc = scope ?: return
        synchronized(this) {
            val job = retryJob
            if (job != null && job.isActive) return
            retryJob = sc.launch { runLoop() }
        }
    }

    private suspend fun runLoop() {
        // 循环永不退出：队列清空后进入低频空转（30s 一次）。
        // 若此处像旧版那样 return 退出，退出瞬间（锁已释放、协程尚未结束）恰好有
        // enqueue 入队时，ensureLoop 会误判旧 Job 仍活跃而不拉起新循环，
        // 该消息将滞留队列直到下一条消息入队才被处理 —— 空转轮询彻底消除该竞态。
        while (true) {
            val now = System.currentTimeMillis()
            val due: List<PendingItem> = mutex.withLock {
                ensureLoadedLocked()
                pending.filter { it.nextRetryAt <= now }
            }
            if (due.isEmpty()) {
                val next: Long? = mutex.withLock {
                    if (pending.isEmpty()) null else pending.minOf { it.nextRetryAt }
                }
                if (next == null) {
                    delay(30_000L) // 空队列：低频轮询等待新入队
                } else {
                    delay((next - System.currentTimeMillis()).coerceIn(1_000L, 60_000L))
                }
                continue
            }
            for (item in due) {
                // 单条处理中的意外异常不应终止整个重试循环
                try {
                    processItem(item)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error processing retry item", e)
                }
            }
        }
    }

    private suspend fun processItem(item: PendingItem) {
        val store = settingsStore
        if (store == null) {
            removeItem(item)
            return
        }
        // 按当前设置解析配置（用户可能已修改/删除）
        val config = store.settingsFlow.value.externalMemories.firstOrNull {
            it.id.toString() == item.configId
        }
        if (config == null || !config.enabled) {
            removeItem(item)
            Log.w(TAG, "Dropped pending message: config ${item.configId} missing or disabled")
            return
        }

        val result = ExternalMemoryService(config).saveMessage(
            assistantId = item.assistantId,
            conversationId = item.conversationId,
            role = item.role,
            content = item.content,
            originalCreatedAt = item.originalCreatedAt,
        )
        val error = result.exceptionOrNull()
        if (error == null) {
            val remaining = removeItem(item)
            notifyRetryStatus(remaining)
            Log.i(TAG, "Retry succeeded for ${item.configId} (attempts=${item.attempts + 1})")
            return
        }

        val httpError = error as? SupabaseHttpException
        val permanent = httpError != null &&
            httpError.statusCode in 400..499 &&
            httpError.statusCode != 429

        var gaveUp = false
        var attempts = item.attempts
        var count = 0
        mutex.withLock {
            ensureLoadedLocked()
            val idx = pending.indexOfFirst { it === item }
            if (idx < 0) {
                count = pending.size
                return@withLock
            }
            val current = pending[idx]
            current.attempts += 1
            attempts = current.attempts
            if (permanent || current.attempts >= MAX_ATTEMPTS) {
                pending.removeAt(idx)
                gaveUp = true
            } else {
                current.nextRetryAt = System.currentTimeMillis() + backoffMs(current.attempts)
            }
            persistLocked()
            count = pending.size
        }

        if (gaveUp) {
            Log.w(TAG, "Gave up message for ${item.configId} after $attempts attempts: ${error.message}")
            notifyFailure(
                config.name,
                "1 条消息重试 $attempts 次后仍失败：${error.message ?: "未知错误"}。" +
                    "网络恢复后可在 进阶记忆 页面使用「补传历史消息」找回。"
            )
        } else {
            Log.w(TAG, "Retry #$attempts failed for ${item.configId}: ${error.message}")
        }
        notifyRetryStatus(count)
    }

    /**
     * 移除一条待重试消息，返回剩余数量
     */
    private suspend fun removeItem(item: PendingItem): Int = mutex.withLock {
        ensureLoadedLocked()
        pending.removeAll { it === item }
        persistLocked()
        pending.size
    }

    private fun backoffMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 20)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    // ---- 持久化（调用方必须持有 mutex；磁盘 IO 在函数内部切换到 IO 调度器）----

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val ctx = appContext ?: return
        withContext(Dispatchers.IO) {
            try {
                val file = File(ctx.filesDir, FILE_NAME)
                if (file.exists()) {
                    val text = file.readText()
                    if (text.isNotBlank()) {
                        val items =
                            JsonInstant.decodeFromString(ListSerializer(PendingItem.serializer()), text)
                        pending.addAll(items)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load retry queue file, discarding", e)
                pending.clear()
            }
        }
    }

    private suspend fun persistLocked() {
        val ctx = appContext ?: return
        withContext(Dispatchers.IO) {
            try {
                val file = File(ctx.filesDir, FILE_NAME)
                val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
                tmp.writeText(JsonInstant.encodeToString(ListSerializer(PendingItem.serializer()), pending.toList()))
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist retry queue", e)
            }
        }
    }

    // ---- 通知 ----

    private fun notifyRetryStatus(count: Int) {
        val ctx = appContext ?: return
        if (count <= 0) {
            // 队列已清空，撤掉"同步中"状态通知
            cancelStatusNotification()
            return
        }
        ctx.sendNotification(EXTERNAL_MEMORY_NOTIFICATION_CHANNEL_ID, STATUS_NOTIFICATION_ID) {
            title = "进阶记忆同步中"
            content = "有 $count 条消息暂未同步成功，正在自动重试；网络恢复后会自动完成同步"
            ongoing = true
            onlyAlertOnce = true
        }
    }

    private fun notifyFailure(memoryName: String, detail: String) {
        val ctx = appContext ?: return
        ctx.sendNotification(EXTERNAL_MEMORY_NOTIFICATION_CHANNEL_ID, FAILED_NOTIFICATION_ID) {
            title = "进阶记忆同步失败（$memoryName）"
            content = detail
            autoCancel = true
            onlyAlertOnce = true
        }
    }

    private fun cancelStatusNotification() {
        appContext?.cancelNotification(STATUS_NOTIFICATION_ID)
    }
}
