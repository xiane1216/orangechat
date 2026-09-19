/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.data.datastore.HeartbeatSetting
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 心跳机制调度器 (基于 WorkManager).
 *
 * 与"主动消息"完全独立:
 * - 独立开关 (heartbeatSetting.enabled)
 * - 独立调度 (基于用户最后一条消息的闲置时间, 而非固定间隔)
 * - 独立设置页面
 *
 * 触发条件: 用户最后一条消息后闲置达到 idleMinutes (默认 30 分钟).
 * 正常聊天过程中用户持续发消息 → 每次都重置计时 → 不会触发.
 *
 * 使用 WorkManager 替代 AlarmManager:
 * - 自动兼容 Doze 模式
 * - 持久化, 重启后不丢失
 * - 系统不会随便杀掉
 *
 * AI 执行引擎复用 ProactiveMessageTriggerService (传入 ACTION_HEARTBEAT),
 * 但使用心跳专用提示词与闲置校验逻辑.
 */
object HeartbeatService {
    private const val TAG = "HeartbeatService"
    const val ACTION_HEARTBEAT = "me.rerere.rikkahub.HEARTBEAT_TRIGGER"
    private const val WORK_NAME = "heartbeat_trigger"

    /**
     * 安排下一次心跳触发: idleMinutes 分钟后.
     * 如果已有待执行的心跳任务, 取消并重新安排 (replace).
     */
    fun scheduleNext(context: Context, setting: HeartbeatSetting) {
        if (!setting.enabled) {
            cancel(context)
            Log.d(TAG, "Heartbeat disabled, cancelled any pending work")
            return
        }
        val delayMinutes = setting.idleMinutes.coerceAtLeast(1).toLong()
        val request = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(
                "idle_minutes" to setting.idleMinutes,
                "scheduled_at" to System.currentTimeMillis()
            ))
            .addTag("heartbeat")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        val triggerAt = System.currentTimeMillis() + delayMinutes * 60 * 1000L
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(triggerAt))
        Log.d(TAG, "Scheduled heartbeat in ${delayMinutes}min (at $timeStr)")

        // 保存下次触发时间到 SharedPreferences (用于 UI 显示)
        context.getSharedPreferences("heartbeat_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("next_trigger_at", triggerAt)
            .apply()
    }

    /**
     * 取消待执行的心跳任务.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(WORK_NAME)
        Log.d(TAG, "Cancelled heartbeat work")
    }

    /**
     * 用户发消息时调用: 重置心跳计时 (从现在起 idleMinutes 后触发).
     * 这就是"闲置检测"的核心: 只要用户在发消息, 心跳就不会触发.
     */
    fun resetTimer(context: Context, setting: HeartbeatSetting) {
        Log.d(TAG, "ResetTimer: user sent message, rescheduling heartbeat")
        scheduleNext(context, setting)
    }

    /**
     * 获取下次触发时间 (仅用于 UI 显示).
     */
    fun getNextTriggerTime(context: Context): Long? {
        val prefs = context.getSharedPreferences("heartbeat_prefs", Context.MODE_PRIVATE)
        val t = prefs.getLong("next_trigger_at", 0L)
        return if (t > 0) t else null
    }
}

/**
 * WorkManager Worker: 心跳触发时执行.
 *
 * 闲置校验: 如果用户在 idleMinutes 内发过消息 (正常聊天), 跳过并重新安排.
 * 否则启动 ProactiveMessageTriggerService 执行心跳逻辑.
 */
class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "HeartbeatWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Heartbeat work triggered")

        val appContext = applicationContext

        // 通过 Koin 获取设置
        val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = try {
            org.koin.core.context.GlobalContext.get().get()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get settings store via Koin, retrying later", e)
            return Result.retry()
        }

        val settings = try {
            settingsStore.settingsFlow.first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read settings", e)
            return Result.retry()
        }

        val heartbeatSetting = settings.heartbeatSetting

        // 心跳开关关闭了, 不执行
        if (!heartbeatSetting.enabled) {
            Log.d(TAG, "Heartbeat disabled, stopping")
            return Result.success()
        }

        // 闲置校验: 如果用户在 idleMinutes 内发过消息, 跳过并重新安排
        val idleMs = heartbeatSetting.idleMinutes.coerceAtLeast(1) * 60 * 1000L
        val lastUserMsgTime = try {
            // ProactiveMessageService 是 KoinComponent, 实例化后自动注入依赖
            val proactiveService = ProactiveMessageService()
            proactiveService.getLastUserMessageTimeMs()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last user message time", e)
            0L
        }

        if (lastUserMsgTime > 0 && System.currentTimeMillis() - lastUserMsgTime < idleMs) {
            val idleMin = (System.currentTimeMillis() - lastUserMsgTime) / 60000L
            Log.d(TAG, "User only idle ${idleMin}min (< ${heartbeatSetting.idleMinutes}min), rescheduling")
            HeartbeatService.scheduleNext(appContext, heartbeatSetting)
            return Result.success()
        }

        Log.d(TAG, "Idle check passed, starting ProactiveMessageTriggerService with heartbeat action")

        // 启动 ProactiveMessageTriggerService 执行心跳
        val triggerIntent = android.content.Intent(appContext, ProactiveMessageTriggerService::class.java).apply {
            action = HeartbeatService.ACTION_HEARTBEAT
        }
        try {
            appContext.startService(triggerIntent)
            Log.d(TAG, "Started ProactiveMessageTriggerService for heartbeat")
        } catch (e: Exception) {
            // Android 12+ 可能需要 startForegroundService
            try {
                ContextCompat.startForegroundService(appContext, triggerIntent)
                Log.d(TAG, "Started ProactiveMessageTriggerService via foreground service")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start ProactiveMessageTriggerService", e2)
                return Result.retry()
            }
        }

        return Result.success()
    }
}
