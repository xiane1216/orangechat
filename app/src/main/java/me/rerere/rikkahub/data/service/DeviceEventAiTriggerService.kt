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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DeviceEventAiTrigger"

/**
 * 激进模式 + 监测模式 共用常驻前台服务：
 * - 监听亮屏/锁屏（ACTION_SCREEN_ON / ACTION_SCREEN_OFF 动态广播）
 * - 每 3 秒轮询 UsageStatsManager.queryEvents 检测应用切换/回桌面
 * - 激进模式: 收到事件后按用户设置的秒数防抖（批量收集），限流检查后触发 AI 思考
 * - 监测模式: 名单内应用被打开且稳定停留 5 秒后, 以用户身份注入一条监测提醒消息
 *   （chatService.sendMessage 正常聊天链路, 落库+自动回复+后台通知, 不跳转界面）
 */
class DeviceEventAiTriggerService : Service(), KoinComponent {

    companion object {
        const val NOTIFICATION_ID = 20005
        private const val POLL_INTERVAL_MS = 3000L
        // 默认防抖延迟（毫秒），仅在读取设置失败/值非法时兜底使用
        private const val DEFAULT_DEBOUNCE_DELAY_MS = 30_000L
        // 防抖最长等待: 从最早未消费事件起算, 防止活跃使用时计时器被无限重置导致饥饿
        private const val MAX_DEBOUNCE_WAIT_MS = 90_000L
        // 监测模式: 应用需稳定停留多久才触发
        private const val WATCH_STABLE_MS = 5_000L
        // 监测模式: 同一应用的触发冷却
        private const val WATCH_COOLDOWN_MS = 10 * 60_000L

        fun startIfEnabled(context: Context) {
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    val settingsStore = GlobalContext.get().get<SettingsStore>()
                    val settings = settingsStore.settingsFlowRaw.first()
                    val proactiveSetting = settings.proactiveMessageSetting
                    // 激进模式/监测模式都可独立工作，不需要主动消息开关；两者共用本服务
                    if (proactiveSetting.aggressiveModeEnabled || proactiveSetting.watchModeEnabled) {
                        val intent = Intent(context, DeviceEventAiTriggerService::class.java)
                        try {
                            context.startForegroundService(intent)
                            Log.d(TAG, "startIfEnabled: started foreground service")
                        } catch (e: Exception) {
                            Log.e(TAG, "startIfEnabled: startForegroundService failed", e)
                        }
                    } else {
                        Log.d(TAG, "startIfEnabled: conditions not met, skip")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startIfEnabled: failed", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DeviceEventAiTriggerService::class.java))
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
    private var appPollJob: Job? = null
    private var debounceJob: Job? = null

    // 事件收集缓冲区（防抖期间累积）
    private val eventBuffer = mutableListOf<DeviceEvent>()
    private val eventBufferMutex = Mutex()

    // 上次轮询时记录的前台包名，用于检测切换
    @Volatile
    private var lastForegroundPackage: String? = null
    private var lastPollTimeMs: Long = 0L

    // 上次 AI 思考时间，用于限流
    private var lastAiTriggerTimeMs: Long = 0L

    // ---- 监测模式状态 ----
    // 当前停留确认中的候选应用（打开后还没满 5 秒）
    private var watchCandidatePkg: String? = null
    private var watchStableJob: Job? = null
    // 每个应用上次触发提醒的时间（冷却 10 分钟，内存态即可：服务被杀重建最多多提醒一次）
    private val watchLastTriggerAt = mutableMapOf<String, Long>()
    // 常驻通知文案（轮询循环里按当前设置刷新，通知构建读缓存值避免主线程阻塞）
    @Volatile
    private var notificationText: String = "AI 正在感知你的手机动向"

    private data class DeviceEvent(
        val type: String,        // screen_on, screen_off, app_switch, home
        val packageName: String, // 相关包名
        val appName: String,     // 应用名
        val timestamp: Long
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            // 注册亮屏/锁屏广播
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val eventType = when (intent?.action) {
                        Intent.ACTION_SCREEN_ON -> "screen_on"
                        Intent.ACTION_SCREEN_OFF -> "screen_off"
                        else -> null
                    } ?: return
                    val now = System.currentTimeMillis()
                    serviceScope.launch {
                        addEvent(DeviceEvent(eventType, "", "", now))
                        if (eventType == "screen_off") {
                            // 熄屏后不存在"停留"一说，取消未满 5 秒的监测候选
                            cancelWatchCandidate()
                        }
                        scheduleDebounce(getDebounceDelayMs())
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiver = receiver
            Log.d(TAG, "Screen on/off receiver registered")

            // 启动应用切换轮询
            startAppPolling()
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
            screenReceiver?.let {
                runCatching { unregisterReceiver(it) }
            }
            screenReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy: error during unregister", e)
        }
        appPollJob?.cancel()
        appPollJob = null
        debounceJob?.cancel()
        debounceJob = null
        runCatching { serviceScope.cancel() }
    }

    /**
     * 每 3 秒轮询 UsageStatsManager，检测应用切换和回桌面
     */
    private fun startAppPolling() {
        appPollJob = serviceScope.launch {
            lastPollTimeMs = System.currentTimeMillis()
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    if (usm != null) {
                        val events = usm.queryEvents(lastPollTimeMs - 5000, now)
                        val event = UsageEvents.Event()
                        var detectedForeground: String? = null
                        var detectedAppName: String = ""

                        while (events.hasNextEvent()) {
                            events.getNextEvent(event)
                            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                                detectedForeground = event.packageName
                                detectedAppName = getAppName(event.packageName)
                            }
                        }

                        // 检测到前台应用变化
                        if (detectedForeground != null && detectedForeground != lastForegroundPackage) {
                            val isHome = isLauncherPackage(detectedForeground)
                            val eventType = if (isHome) "home" else "app_switch"

                            // 跳过本应用自身的切换（避免AI思考时切屏导致循环）
                            if (detectedForeground != packageName) {
                                addEvent(DeviceEvent(
                                    type = eventType,
                                    packageName = detectedForeground,
                                    appName = detectedAppName,
                                    timestamp = now
                                ))
                                scheduleDebounce(getDebounceDelayMs())
                            }
                            lastForegroundPackage = detectedForeground
                        }

                        // 监测模式: 前台变化时刷新候选应用（每轮都读一次设置，
                        // 这样 UI/AI 工具改了名单后无需重启服务即可生效）
                        val proactiveSetting = runCatching {
                            settingsStore.settingsFlowRaw.first().proactiveMessageSetting
                        }.getOrNull()
                        if (proactiveSetting != null) {
                            notificationText = when {
                                proactiveSetting.aggressiveModeEnabled && proactiveSetting.watchModeEnabled ->
                                    "激进模式 + 监测模式运行中（监测 ${proactiveSetting.watchPackages.size} 个应用）"
                                proactiveSetting.watchModeEnabled ->
                                    "监测模式运行中（监测 ${proactiveSetting.watchPackages.size} 个应用）"
                                else -> "AI 正在感知你的手机动向"
                            }
                            handleForegroundChange(
                                pkg = detectedForeground,
                                watchEnabled = proactiveSetting.watchModeEnabled,
                                watchPackages = proactiveSetting.watchPackages,
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

    private suspend fun addEvent(event: DeviceEvent) {
        eventBufferMutex.withLock {
            eventBuffer.add(event)
            // 限制缓冲区大小
            if (eventBuffer.size > 50) {
                eventBuffer.removeAt(0)
            }
        }
        Log.d(TAG, "Event added: ${event.type} ${event.appName}")
    }

    /**
     * 防抖：收到事件后等待指定时长，期间新事件会重置计时器。
     * 但重置不能无限继续: 用户活跃使用手机时事件源源不断, 计时器永远被重置,
     * AI 一次都不会触发(饥饿, 旧版"从没好使过"的主因). 因此从缓冲区最早
     * 未消费事件起算, 最长等待 MAX_DEBOUNCE_WAIT_MS 后强制触发交付这批事件.
     */
    private fun scheduleDebounce(defaultDelayMs: Long) {
        debounceJob?.cancel()
        val delayMs = eventBufferMutex.withLock {
            val first = eventBuffer.firstOrNull()?.timestamp
            if (first == null) defaultDelayMs
            else (defaultDelayMs.coerceAtMost(MAX_DEBOUNCE_WAIT_MS - (System.currentTimeMillis() - first)))
                .coerceAtLeast(3_000L)
        }
        debounceJob = serviceScope.launch {
            delay(delayMs)
            triggerAiThinking()
        }
    }

    /**
     * 从设置读取用户配置的防抖秒数，转换成毫秒。
     * 读取失败或值非法（<=0）时兜底用 DEFAULT_DEBOUNCE_DELAY_MS，避免因为一次设置读取异常
     * 导致激进模式完全不触发。
     */
    private suspend fun getDebounceDelayMs(): Long {
        return try {
            val settingsStore = GlobalContext.get().get<SettingsStore>()
            val settings = settingsStore.settingsFlowRaw.first()
            val seconds = settings.proactiveMessageSetting.aggressiveDebounceSeconds
            if (seconds > 0) seconds * 1000L else DEFAULT_DEBOUNCE_DELAY_MS
        } catch (e: Exception) {
            Log.e(TAG, "getDebounceDelayMs failed, fallback to default ${DEFAULT_DEBOUNCE_DELAY_MS}ms", e)
            DEFAULT_DEBOUNCE_DELAY_MS
        }
    }

    private suspend fun triggerAiThinking() {
        try {
            val settingsStore = GlobalContext.get().get<SettingsStore>()
            val settings = settingsStore.settingsFlowRaw.first()
            val proactiveSetting = settings.proactiveMessageSetting

            // 检查是否仍启用（激进模式可独立工作，不依赖主动消息开关）
            if (!proactiveSetting.aggressiveModeEnabled) {
                Log.d(TAG, "Aggressive mode disabled, skip")
                return
            }

            // 限流检查
            val minIntervalMs = proactiveSetting.aggressiveMinIntervalSeconds * 1000L
            val now = System.currentTimeMillis()
            if (now - lastAiTriggerTimeMs < minIntervalMs) {
                Log.d(TAG, "Rate limited, skip (${(now - lastAiTriggerTimeMs) / 1000}s < ${proactiveSetting.aggressiveMinIntervalSeconds}s)")
                return
            }

            // 取出缓冲区事件
            val events = eventBufferMutex.withLock {
                val copy = eventBuffer.toList()
                eventBuffer.clear()
                copy
            }

            if (events.isEmpty()) {
                Log.d(TAG, "No events to process")
                return
            }

            lastAiTriggerTimeMs = now
            Log.d(TAG, "Triggering AI thinking with ${events.size} events")

            // 构建事件上下文
            val eventContext = buildEventContext(events)

            // 复用 ProactiveMessageTriggerService 的 AI 思考逻辑
            val triggerIntent = Intent(this@DeviceEventAiTriggerService, ProactiveMessageTriggerService::class.java).apply {
                putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
                putExtra(ProactiveMessageTriggerService.EXTRA_DEVICE_EVENT_CONTEXT, eventContext)
            }
            startForegroundService(triggerIntent)

        } catch (e: Exception) {
            Log.e(TAG, "triggerAiThinking failed", e)
        }
    }

    private fun buildEventContext(events: List<DeviceEvent>): String {
        // 纯事实事件列表, 不带任何行为引导 (引导统一由 ProactiveMessageTriggerService 注入)
        val sb = StringBuilder()
        sb.appendLine("[设备事件] 用户的手机动向：")
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        events.forEach { event ->
            val time = dateFormat.format(Date(event.timestamp))
            val desc = when (event.type) {
                "screen_on" -> "亮屏"
                "screen_off" -> "锁屏"
                "app_switch" -> "打开应用 ${event.appName}"
                "home" -> "回到桌面"
                else -> event.type
            }
            sb.appendLine("  - [$time] $desc")
        }
        return sb.toString()
    }

    // ==================== 监测模式 ====================

    /**
     * 前台应用变化时刷新监测候选。
     * 只盯"打开"：名单内应用进入前台后开始 5 秒停留确认，期间切走/熄屏/回桌面则取消。
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
            pkg != packageName && // 自己打开自己（AI 回复时切屏）不监测
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
            .setContentTitle("感知服务运行中")
            .setContentText(notificationText)
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}