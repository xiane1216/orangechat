/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.DEVICE_EVENT_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext

private const val TAG = "WatchModeService"

/**
 * 监测模式独立前台服务（与激进模式的 DeviceEventAiTriggerService 完全分开，互不影响）：
 * - 每 3 秒轮询 UsageStatsManager.queryEvents 检测前台应用变化
 * - 名单内应用被打开后开始 5 秒停留确认，期间切走/熄屏/回桌面则取消
 * - 确认停留后检查冷却（同一应用 10 分钟一次），命中则以用户身份注入
 *   "[监测提醒] 宝宝打开了「X」。" —— 走正常聊天 sendMessage 链路：
 *   消息留痕进历史、AI 自动回复、App 不在前台时回复完成发系统通知，全程不跳转界面
 */
class WatchModeService : Service(), KoinComponent {

    companion object {
        const val NOTIFICATION_ID = 20006
        private const val POLL_INTERVAL_MS = 3000L
        // 应用需稳定停留多久才触发
        private const val WATCH_STABLE_MS = 5_000L
        // 同一应用的触发冷却
        private const val WATCH_COOLDOWN_MS = 10 * 60_000L

        fun startIfEnabled(context: Context) {
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    val settingsStore = GlobalContext.get().get<SettingsStore>()
                    val enabled = runCatching {
                        settingsStore.settingsFlowRaw.first().proactiveMessageSetting.watchModeEnabled
                    }.getOrDefault(false)
                    if (enabled) {
                        try {
                            context.startForegroundService(Intent(context, WatchModeService::class.java))
                            Log.d(TAG, "startIfEnabled: started foreground service")
                        } catch (e: Exception) {
                            Log.e(TAG, "startIfEnabled: startForegroundService failed", e)
                        }
                    } else {
                        Log.d(TAG, "startIfEnabled: watch mode disabled, skip")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startIfEnabled: failed", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WatchModeService::class.java))
                Log.d(TAG, "stop: service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "stop: failed", e)
            }
        }
    }

    private val settingsStore: SettingsStore by inject()
    private val chatService: ChatService by inject()
    private val conversationRepository: ConversationRepository by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenReceiver: BroadcastReceiver? = null
    private var pollJob: Job? = null

    // 轮询器维护的最新前台包名
    @Volatile
    private var lastForegroundPackage: String? = null
    private var lastPollTimeMs: Long = 0L

    // 当前停留确认中的候选应用（打开后还没满 5 秒）
    private var watchCandidatePkg: String? = null
    private var watchStableJob: Job? = null

    // 每个应用上次触发提醒的时间（冷却 10 分钟，内存态：服务被杀重建最多多提醒一次）
    private val watchLastTriggerAt = mutableMapOf<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            // 熄屏后不存在"停留"一说，注册锁屏广播用于取消未确认的候选
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        cancelWatchCandidate()
                    }
                }
            }
            val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF) }
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = receiver

            startPolling()
            Log.d(TAG, "WatchModeService created")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand: startForeground failed", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            screenReceiver?.let { runCatching { unregisterReceiver(it) } }
            screenReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy: error during unregister", e)
        }
        pollJob?.cancel()
        pollJob = null
        watchStableJob?.cancel()
        watchStableJob = null
        runCatching { serviceScope.cancel() }
    }

    private fun startPolling() {
        pollJob = serviceScope.launch {
            lastPollTimeMs = System.currentTimeMillis()
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    if (usm != null) {
                        val events = usm.queryEvents(lastPollTimeMs - 5000, now)
                        val event = UsageEvents.Event()
                        var detectedForeground: String? = null

                        while (events.hasNextEvent()) {
                            events.getNextEvent(event)
                            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                                detectedForeground = event.packageName
                            }
                        }

                        if (detectedForeground != null && detectedForeground != lastForegroundPackage) {
                            lastForegroundPackage = detectedForeground
                        }

                        // 每轮读一次设置：UI/AI 工具改了名单后无需重启服务即可生效
                        val setting = runCatching {
                            settingsStore.settingsFlowRaw.first().proactiveMessageSetting
                        }.getOrNull()
                        if (setting != null && detectedForeground != null) {
                            handleForegroundChange(
                                pkg = detectedForeground,
                                watchEnabled = setting.watchModeEnabled,
                                watchPackages = setting.watchPackages,
                            )
                        }
                    }
                    lastPollTimeMs = now
                } catch (e: Exception) {
                    Log.w(TAG, "App poll error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun getAppName(pkg: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName == pkg
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 前台应用变化时刷新监测候选。只盯"打开"：
     * 名单内应用进入前台后开始 5 秒停留确认，期间切走/熄屏/回桌面则取消。
     * pkg 为 null 表示本轮轮询没有前台变化，不动候选。
     */
    private fun handleForegroundChange(
        pkg: String?,
        watchEnabled: Boolean,
        watchPackages: List<String>,
    ) {
        if (pkg == null) return
        val inList = watchEnabled &&
            watchPackages.contains(pkg) &&
            pkg != packageName && // 自己（AI 回复时切屏）不监测
            !isLauncherPackage(pkg) // 回桌面不算
        if (!inList) {
            cancelWatchCandidate()
            return
        }
        // 同一应用的重复前台事件不重置计时（轮询窗口边界可能重报）
        if (watchCandidatePkg == pkg) return
        watchCandidatePkg = pkg
        watchStableJob?.cancel()
        watchStableJob = serviceScope.launch {
            delay(WATCH_STABLE_MS)
            confirmAndTriggerWatch(pkg)
        }
        Log.d(TAG, "Watch candidate: $pkg, confirm in ${WATCH_STABLE_MS / 1000}s")
    }

    private fun cancelWatchCandidate() {
        watchStableJob?.cancel()
        watchStableJob = null
        watchCandidatePkg = null
    }

    /**
     * 停留确认到期：冷却检查后以用户身份注入一条监测提醒消息。
     * 走正常聊天 sendMessage 链路——消息留在历史里、AI 自动回复、
     * App 不在前台时回复完成会发系统通知；全程不跳转界面。
     */
    private suspend fun confirmAndTriggerWatch(pkg: String) {
        watchCandidatePkg = null
        try {
            val setting = settingsStore.settingsFlowRaw.first().proactiveMessageSetting
            if (!setting.watchModeEnabled || !setting.watchPackages.contains(pkg)) return
            // 轮询间隙漏报的安全网: 5 秒后前台已经不是它了就不触发
            if (lastForegroundPackage != pkg) {
                Log.d(TAG, "Watch skip: foreground changed to $lastForegroundPackage")
                return
            }
            val now = System.currentTimeMillis()
            val last = watchLastTriggerAt[pkg] ?: 0L
            if (now - last < WATCH_COOLDOWN_MS) {
                Log.d(TAG, "Watch cooldown, skip $pkg (${(now - last) / 1000}s since last)")
                return
            }
            watchLastTriggerAt[pkg] = now
            val appName = getAppName(pkg)
            Log.d(TAG, "Watch trigger: $pkg ($appName)")

            val settings = settingsStore.settingsFlowRaw.first()
            val assistant = settings.getCurrentAssistant()
            // 当前助手最近的会话; 没有则新建（与语音唤醒一致）
            val recent = runCatching {
                conversationRepository.getRecentConversations(assistant.id, limit = 1).firstOrNull()
            }.getOrNull()
            val conversationId = recent?.id ?: kotlin.uuid.Uuid.random().also {
                runCatching { chatService.initializeConversation(it) }
                    .onFailure { e -> Log.e(TAG, "新建监测目标会话失败", e) }
            }
            // 纯事件本身, 不带任何行为引导
            chatService.sendMessage(
                conversationId,
                listOf(UIMessagePart.Text("[监测提醒] 宝宝打开了「$appName」。"))
            )
        } catch (e: Exception) {
            Log.e(TAG, "confirmAndTriggerWatch failed", e)
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, DEVICE_EVENT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("监测模式运行中")
            .setContentText("AI 正在关注你打开了哪些应用")
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
