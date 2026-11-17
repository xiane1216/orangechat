/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

/**
 * 前台保活服务，用于提升 App 后台存活能力，确保定时消息等功能稳定运行。
 *
 * 保活链路（2025-11 重构）：
 * - START_STICKY：被系统杀死后系统侧自动重启
 * - BOOT_COMPLETED / MY_PACKAGE_REPLACED：KeepAliveRestartReceiver 在开机和覆盖安装后拉起
 *   （系统豁免的后台 FGS 启动场景，真正生效）
 * - AlarmManager 看门狗：每 15 分钟精确窗口闹钟检查并重拉服务（兜底链路全部失效时）
 * - 不再用 getRunningServices 做"跳过启动"判断（部分 ROM 对自己的服务也返回空，
 *   误判会导致服务死了也永远不重启），重复 start 无副作用，直接无条件启动
 * - 低优先级常驻通知，低调不打扰用户
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"

        // 通知渠道 ID
        private const val CHANNEL_ID = "keep_alive_channel"
        // 前台服务通知 ID
        private const val NOTIFICATION_ID = 30001
        // 看门狗闹钟requestCode
        private const val WATCHDOG_REQUEST_CODE = 30002
        // 看门狗间隔：15 分钟
        private const val WATCHDOG_INTERVAL_MS = 15L * 60 * 1000
        // 看门狗 Action
        const val ACTION_KEEP_ALIVE_WATCHDOG = "me.rerere.orangechat.KEEP_ALIVE_WATCHDOG"
        // startForeground 失败标志 (SharedPreferences "keep_alive_prefs")
        const val PREFS_NAME = "keep_alive_prefs"
        const val PREF_FAILED = "start_foreground_failed"
        const val PREF_FAILED_AT = "start_foreground_failed_at"

        /**
         * 启动保活服务。
         * 无条件 startForegroundService：重复启动只是回调 onStartCommand，系统自动去重，
         * 不做任何"已在运行"判断（getRunningServices 在高版本上不可靠，误判会漏拉）。
         */
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "保活服务启动请求已发送")
            } catch (e: Exception) {
                Log.e(TAG, "启动保活服务失败", e)
            }
        }

        /**
         * 停止保活服务（同时取消看门狗）
         */
        fun stop(context: Context) {
            cancelWatchdog(context)
            val intent = Intent(context, KeepAliveService::class.java)
            context.stopService(intent)
            Log.d(TAG, "保活服务停止请求已发送")
        }

        /**
         * 安排看门狗闹钟：15 分钟后触发 KeepAliveRestartReceiver，
         * receiver 收到后重拉服务并续排下一次。用 setAndAllowWhileIdle 兼容 Doze，
         * 不需要 exact alarm 权限（分钟级偏差无所谓，看门狗只求兜底）。
         */
        fun scheduleWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                Intent(context, KeepAliveRestartReceiver::class.java).apply {
                    action = ACTION_KEEP_ALIVE_WATCHDOG
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                    pi
                )
            } catch (e: Exception) {
                Log.w(TAG, "看门狗闹钟安排失败", e)
            }
        }

        fun cancelWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                Intent(context, KeepAliveRestartReceiver::class.java).apply {
                    action = ACTION_KEEP_ALIVE_WATCHDOG
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.cancel(pi)
        }

        /**
         * startForeground 失败时记录标志（设置页据此显示失效警告）
         */
        fun markStartFailed(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_FAILED, true)
                .putLong(PREF_FAILED_AT, System.currentTimeMillis())
                .apply()
        }

        /**
         * startForeground 成功时清除失败标志
         */
        fun clearStartFailed(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_FAILED, false)
                .apply()
        }

        fun isStartFailed(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_FAILED, false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        // 设置里关了保活（看门狗/系统重启误拉起）时自决退出
        val enabled = try {
            val settingsStore = org.koin.core.context.GlobalContext.get()
                .get<me.rerere.rikkahub.data.datastore.SettingsStore>()
            kotlinx.coroutines.runBlocking {
                settingsStore.settingsFlowRaw.first().keepAliveEnabled
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取保活开关失败，默认继续运行", e)
            true
        }
        if (!enabled) {
            Log.d(TAG, "保活开关已关闭，服务自决退出")
            cancelWatchdog(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // 构建点击通知后打开应用的 PendingIntent
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 构建低优先级常驻通知
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_content))
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setShowWhen(false)
            .setSilent(true)
            .build()

        // 启动前台服务
        // 使用 specialUse 类型（与 Manifest 声明一致）：Android 14+ 的 dataSync 类型有
        // 每 24 小时 6 小时累计配额，常驻保活必然撞配额，耗尽后系统强停引发
        // ForegroundServiceDidNotStopInTimeException 崩溃；specialUse 无此限制。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            clearStartFailed(this)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败，记录失效状态并停止服务避免崩溃", e)
            // 5 秒内不调 startForeground 系统会抛异常，stopSelf 是必须的；
            // 但把失效状态持久化，设置页展示出来，不再静默停摆
            markStartFailed(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // 每次拉起都续排看门狗（无条件 startForegroundService 后这里就是唯一常驻入口）
        scheduleWatchdog(this)

        // 兜底补排心跳：进程被杀时 WorkManager 队列里的任务会由系统重试，
        // 但"Worker 执行成功、TriggerService 生成期间进程被杀"时下一次心跳没人排，
        // 这里检查 unique work 队列，没有任何 ENQUEUED/RUNNING 的心跳任务就补排一次
        try {
            val settingsStore = org.koin.core.context.GlobalContext.get()
                .get<me.rerere.rikkahub.data.datastore.SettingsStore>()
            val setting = kotlinx.coroutines.runBlocking {
                settingsStore.settingsFlowRaw.first().heartbeatSetting
            }
            if (setting.enabled) {
                val wm = androidx.work.WorkManager.getInstance(this)
                val infos = wm.getWorkInfosForUniqueWork(
                    me.rerere.rikkahub.data.service.HeartbeatService.WORK_NAME
                ).get()
                val pending = infos.any {
                    it.state == androidx.work.WorkInfo.State.ENQUEUED ||
                        it.state == androidx.work.WorkInfo.State.RUNNING
                }
                if (!pending) {
                    me.rerere.rikkahub.data.service.HeartbeatService.scheduleNext(this, setting)
                    Log.d(TAG, "看门狗补排心跳任务")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "补排心跳检查失败", e)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }

    /**
     * 创建通知渠道（Android 8.0+ 必需）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.keep_alive_channel_desc)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
