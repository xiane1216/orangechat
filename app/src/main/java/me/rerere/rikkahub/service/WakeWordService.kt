/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.WAKE_WORD_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WakeWordSetting
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.service.AppUsageService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private const val TAG = "WakeWordService"

/**
 * 语音唤醒前台服务 (全天候离线监听).
 *
 * sherpa-onnx KeywordSpotter 在本地识别唤醒词 (不联网、不录音上传):
 * 用户喊昵称 -> 震动反馈 -> 弹到当前助手聊天页 -> 以用户身份插入昵称消息 -> AI 自动回复.
 *
 * 麦克风使用: 与语音通话互斥 (通话期间暂停拾音让出麦克风, 通话结束自动恢复);
 * 其他 App 抢占麦克风时本服务一般会收到静音数据, 检测自然停摆, 不额外处理.
 */
class WakeWordService : Service(), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val chatService: ChatService by inject()
    private val conversationRepository: ConversationRepository by inject()

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "WakeWordService coroutine exception", e)
        }
    )

    // 当前生效的设置 (拾音循环线程直接读, 不走 StateFlow)
    private var currentSetting: WakeWordSetting = WakeWordSetting()
    private var lastTriggerAt = 0L
    private var listenJob: Job? = null

    companion object {
        const val NOTIFICATION_ID = 40002
        const val ACTION_STOP = "me.rerere.rikkahub.WAKE_WORD_STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WakeWordService::class.java)
                )
            } catch (e: Exception) {
                Log.e(TAG, "启动 WakeWordService 失败", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WakeWordService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "停止 WakeWordService 失败", e)
            }
        }

        /** 读取设置, 开着且麦克风权限在才拉起 (App 启动/开机/开关打开时调用) */
        fun startIfEnabled(context: Context) {
            val appContext = context.applicationContext
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching {
                    // Application 已 startKoin, 这里走全局 Koin 实例读设置
                    val store = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
                    val setting = store.settingsFlowRaw.first().wakeWordSetting
                    val granted = ContextCompat.checkSelfPermission(
                        appContext, android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (setting.enabled && granted) {
                        start(appContext)
                    }
                }.onFailure {
                    Log.e(TAG, "startIfEnabled 失败", it)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 订阅设置变化: 改昵称 -> 重建引擎; 关开关 -> 自毁
        serviceScope.launch {
            var last = currentSetting
            settingsStore.settingsFlow.collect { settings ->
                val setting = settings.wakeWordSetting
                currentSetting = setting
                if (!setting.enabled) {
                    stopSelf()
                    return@collect
                }
                if (setting.wakeWord != last.wakeWord || setting.sensitivity != last.sensitivity) {
                    startListening(setting)
                }
                last = setting
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 关键: startForegroundService 后 5 秒内必须 startForeground, 初始化放到协程里
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(currentSetting),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasMicPermission()) {
            Log.w(TAG, "缺少 RECORD_AUDIO 权限, 停止语音唤醒")
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val setting = settingsStore.settingsFlow.first().wakeWordSetting
            if (!setting.enabled) {
                stopSelf()
                return@launch
            }
            currentSetting = setting
            startListening(setting)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listenJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * (重)建引擎并开始拾音循环. 换昵称时也会调这个, 旧循环被 cancel 后资源随之释放.
     */
    private fun startListening(setting: WakeWordSetting) {
        listenJob?.cancel()
        listenJob = serviceScope.launch(Dispatchers.IO) {
            var spotter: KeywordSpotter? = null
            var stream: OnlineStream? = null
            var recorder: AudioRecord? = null
            var readFailures = 0

            try {
                spotter = WakeWordEngine.create(applicationContext, setting.wakeWord, setting.sensitivity)
                stream = spotter.createStream()
                Log.i(TAG, "唤醒引擎就绪: ${setting.wakeWord}")
                // 昵称可能在运行中被改: 引擎重建后同步刷新常驻通知文案
                runCatching {
                    NotificationManagerCompat.from(this@WakeWordService).notify(NOTIFICATION_ID, buildNotification(setting))
                }
            } catch (e: Exception) {
                Log.e(TAG, "唤醒引擎初始化失败: ${e.message}", e)
                stopSelf()
                return@launch
            }

            // 优先级: 音频线程提权, 减少拾音丢块
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }

            val buf = ShortArray(1600) // 100ms @16kHz
            try {
                while (isActive) {
                    // 权限被用户在系统设置里收回 -> 自毁
                    if (!hasMicPermission()) {
                        stopSelf()
                        break
                    }

                    // 语音通话期间让出麦克风, 通话结束后重新申请
                    if (VoiceCallService.isRunning()) {
                        recorder?.let { releaseRecorder(it) }
                        recorder = null
                        delay(1000)
                        continue
                    }

                    if (recorder == null) {
                        recorder = openRecorder()
                        if (recorder == null) {
                            delay(2000)
                            continue
                        }
                        readFailures = 0
                    }

                    val n = recorder!!.read(buf, 0, buf.size)
                    if (n <= 0) {
                        // 偶发 0 是正常调度; 连续失败视为录音实例损坏, 重建
                        if (++readFailures > 20) {
                            Log.w(TAG, "AudioRecord 连续读取失败, 重建录音实例")
                            recorder?.let { releaseRecorder(it) }
                            recorder = null
                        } else {
                            delay(50)
                        }
                        continue
                    }
                    readFailures = 0

                    val samples = FloatArray(n) { buf[it] / 32768f }
                    stream!!.acceptWaveform(samples, 16000)
                    while (spotter!!.isReady(stream)) {
                        spotter.decode(stream)
                    }
                    val result = spotter.getResult(stream)
                    if (result.keyword.isNotBlank()) {
                        Log.i(TAG, "检测到唤醒词: ${result.keyword}")
                        // 流复位, 准备下一次唤醒
                        spotter.reset(stream)
                        onKeywordDetected()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "拾音循环异常退出", e)
            } finally {
                recorder?.let { releaseRecorder(it) }
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun openRecorder(): AudioRecord? = runCatching {
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 32000)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        record.startRecording()
        record
    }.getOrNull()

    private fun releaseRecorder(record: AudioRecord) {
        runCatching {
            record.stop()
            record.release()
        }
    }

    /**
     * 唤醒命中: 冷却去抖 -> 震动反馈 -> 跳聊天页 -> 以用户身份插入昵称消息并触发生成.
     * 发消息用的就是正常聊天 sendMessage 链路, 会落库/触发回复/通知, 与手打消息完全一致.
     */
    private suspend fun onKeywordDetected() {
        val now = System.currentTimeMillis()
        val cooldownMs = currentSetting.cooldownSeconds.coerceAtLeast(5) * 1000L
        if (now - lastTriggerAt < cooldownMs) {
            Log.d(TAG, "冷却中, 忽略本次唤醒")
            return
        }
        lastTriggerAt = now
        vibrate()

        // 先取前台应用 (必须在跳转之前, 跳转后前台就是本 App 自己了).
        // 直接 new, AppUsageService 未注册进 Koin, inject 会 resolve 失败.
        // 拿不到应用名/权限未开时退化为不带前台的简短提醒.
        val fgName = runCatching {
            AppUsageService(applicationContext).getForegroundApp().getOrNull()?.let {
                if (it.screenOn == false) null else it.appName // 熄屏状态下没有"正在刷"一说, 不带前台
            }
        }.getOrNull()

        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getCurrentAssistant()
        // 当前助手最近的会话; 没有则新建 (与心跳目标选择一致)
        val recent = runCatching {
            conversationRepository.getRecentConversations(assistant.id, limit = 1).firstOrNull()
        }.getOrNull()
        val conversationId = recent?.id ?: Uuid.random().also {
            runCatching { chatService.initializeConversation(it) }
                .onFailure { e -> Log.e(TAG, "新建唤醒目标会话失败", e) }
        }

        // 弹到聊天页 (与主动消息跳转同一套 flags)
        try {
            val jumpIntent = Intent(this, RouteActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra("conversationId", conversationId.toString())
            }
            startActivity(jumpIntent)
        } catch (e: Exception) {
            Log.e(TAG, "跳转聊天页失败", e)
        }

        // 以用户身份插入唤醒提醒 -> 自动生成回复.
        // 系统提醒式叙述: 告诉 AI 用户此刻在前台刷什么 + 是喊出来的, 不扮演用户口吻.
        try {
            val text = if (!fgName.isNullOrBlank()) {
                "[语音唤醒] 宝宝当前的前台是在刷「$fgName」，宝宝叫了你一下。"
            } else {
                "[语音唤醒] 宝宝叫了你一下。"
            }
            chatService.sendMessage(conversationId, listOf(UIMessagePart.Text(text)))
        } catch (e: Exception) {
            Log.e(TAG, "插入唤醒消息失败", e)
        }
    }

    private fun vibrate() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun buildNotification(setting: WakeWordSetting): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, WAKE_WORD_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("语音唤醒运行中")
            .setContentText("喊「${setting.wakeWord}」即可唤醒")
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .addAction(0, "关闭", stopIntent)
            .build()
    }
}
