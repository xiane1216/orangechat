/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

@file:Suppress("unused")

package me.rerere.rikkahub.data.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val totalTimeInForeground: Long,
    val lastTimeUsed: Long,
    val launchCount: Int = 0
)

data class AppTrajectoryEvent(
    val packageName: String,
    val appName: String,
    val eventType: String,
    val timestamp: Long
)

/**
 * 当前前台应用信息: 包名 + 应用名 + 最近一次进入前台的时间 + 屏幕亮灭状态
 */
data class ForegroundAppInfo(
    val packageName: String,
    val appName: String,
    val sinceTimestamp: Long,
    /** 屏幕当前是否亮着; null=系统没上报过屏幕事件(旧系统), 无法判断 */
    val screenOn: Boolean?
)

/**
 * 用户最近一次活动信息: 时刻 + 屏幕亮灭状态
 */
data class LastActivityInfo(
    val lastActivityTime: Long,
    val screenOn: Boolean?
)

class AppUsageService(private val context: Context) {
    companion object {
        // 计算前台时长时向前回看的窗口(12h), 用于还原"今日零点前就已在前台"的 App;
        // 取值需覆盖典型的一次连续使用时长, 过小会漏算开头, 过大只是多遍历些事件.
        // (移植自 RikkaHub ScreenTimeTool 的 LOOKBACK_MS)
        private const val FOREGROUND_LOOKBACK_MS = 12L * 60 * 60 * 1000
    }

    private val usageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    private val packageManager by lazy {
        context.packageManager
    }

    // 包名 -> 应用名 缓存, 避免心跳/同步场景频繁查 PackageManager
    private val appNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 解析包名对应的应用名; 找不到(已卸载/系统包)时原样返回包名
     */
    fun getAppName(packageName: String): String = appNameCache.getOrPut(packageName) {
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /**
     * 获取今日各应用前台使用时长.
     *
     * 用"全局单一前台"事件配对模型计算(移植自 RikkaHub ScreenTimeTool):
     * 任意时刻只有一个 App 处于计时状态, 新 App 进前台时结算上一个, 息屏即停.
     * 之前直接读 queryUsageStats 的"统计桶"总账, 在部分国产 ROM 上会把前一天的使用
     * 并进"今天"(桶溢出), 且息屏/挂后台幻影计时, 导致数字虚高(与系统"屏幕使用时间"对不上).
     */
    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND/BACKGROUND 与 API29 的 ACTIVITY_RESUMED/PAUSED 值相同, 兼容 minSdk 26
    suspend fun getTodayUsageStats(): Result<List<AppUsageInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            val launcherPackages = resolveLauncherPackages()
            val usageEvents = usageStatsManager.queryEvents(
                startTime - FOREGROUND_LOOKBACK_MS,
                endTime
            )
            val event = UsageEvents.Event()

            val foregroundMs = HashMap<String, Long>()
            val lastTimeUsedMap = HashMap<String, Long>()
            val launchCountMap = HashMap<String, Int>()

            // 当前正在计时的前台包及其起始时间; null 表示当前无 App 在前台(桌面/息屏)
            var currentPkg: String? = null
            var currentStart = 0L

            // 结算当前前台段: 把 [currentStart, until) 与 [startTime, endTime] 的交集累加给 currentPkg
            fun settle(until: Long) {
                val pkg = currentPkg
                currentPkg = null
                if (pkg == null || pkg in launcherPackages) return // 桌面不计入, 但仍清空计时状态
                val from = maxOf(currentStart, startTime) // 裁掉今日零点之前的部分
                val duration = until - from
                if (duration > 0) {
                    foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + duration
                }
            }

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (event.packageName != currentPkg) {
                            settle(event.timeStamp)          // 先结算上一个前台 App
                            currentPkg = event.packageName   // 再开始为新 App 计时
                            currentStart = event.timeStamp
                        }
                        lastTimeUsedMap[event.packageName] = maxOf(
                            lastTimeUsedMap[event.packageName] ?: 0L,
                            event.timeStamp
                        )
                        launchCountMap[event.packageName] = (launchCountMap[event.packageName] ?: 0) + 1
                    }

                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        // 只结算当前正在计时的前台包; 其他包的 background 一律忽略(避免重叠/高估)
                        if (event.packageName == currentPkg) {
                            settle(event.timeStamp)
                        }
                    }

                    // 息屏: 停止计时, 系统在息屏期间同样不计入屏幕使用时间.
                    // SCREEN_NON_INTERACTIVE 是编译期常量, 低版本设备不会产生该事件, 引用安全
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        settle(event.timeStamp)
                    }
                }
            }
            // 区间结束时仍在前台的 App, 用 endTime 截断
            settle(endTime)

            foregroundMs.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .map { (pkg, ms) ->
                    val appName = try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        pkg
                    }
                    AppUsageInfo(
                        packageName = pkg,
                        appName = appName,
                        totalTimeInForeground = ms,
                        lastTimeUsed = lastTimeUsedMap[pkg] ?: 0L,
                        launchCount = launchCountMap[pkg] ?: 0
                    )
                }
        }
    }

    /**
     * 解析设备上所有桌面(HOME)应用的包名, 计算屏幕使用时长时排除 launcher.
     * (移植自 RikkaHub ScreenTimeTool)
     */
    private fun resolveLauncherPackages(): Set<String> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        return runCatching {
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun getTodayTrajectory(): Result<List<AppTrajectoryEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            val events = mutableListOf<AppTrajectoryEvent>()
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)

            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                val eventType = when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> "打开"
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> "关闭"
                    else -> continue
                }

                val appName = try {
                    val appInfo = packageManager.getApplicationInfo(event.packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    event.packageName
                }

                events.add(
                    AppTrajectoryEvent(
                        packageName = event.packageName,
                        appName = appName,
                        eventType = eventType,
                        timestamp = event.timeStamp
                    )
                )
            }

            events.sortedByDescending { it.timestamp }
        }
    }

    fun getForegroundApp(): Result<ForegroundAppInfo> = runCatching {
        // queryUsageStats 只在应用退到后台时刷新 lastTimeUsed, 正在使用的前台应用记录是旧的,
        // 会返回"上一个使用的应用". 改用 queryEvents 找最近一次进入前台的事件.
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 24 * 60 * 60 * 1000L // 最近24小时, 覆盖长时间停留在同一应用/跨零点的场景

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var foregroundPackage: String? = null
        var sinceTimestamp = 0L
        // 亮屏/息屏事件 (API 30+ 才有, 旧系统收不到这类事件保持 null)
        var screenOn: Boolean? = null

        // 事件按时间顺序返回, 后出现的覆盖先出现的, 遍历完即为最近一次进入前台的应用
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    foregroundPackage = event.packageName
                    sinceTimestamp = event.timeStamp
                }
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> screenOn = false
            }
        }

        val pkg = foregroundPackage ?: throw IllegalStateException("无法获取前台应用")
        ForegroundAppInfo(
            packageName = pkg,
            appName = getAppName(pkg),
            sinceTimestamp = sinceTimestamp,
            screenOn = screenOn
        )
    }

    /**
     * 获取用户最近一次活动信息: 时刻 + 屏幕亮灭状态.
     * 统计范围: 进前台/退后台事件 + 亮屏/息屏事件(SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE).
     * 把息屏事件算进"最后活动"的原因: 按下电源键放下手机的那一刻就是真实意义上"最后一次碰手机",
     * 否则一直停在同一个 app 里刷(无切换事件)时, 静默时间会从"进入这个 app"开始虚算, 差出几十分钟.
     * 屏幕事件 API 30+ 才有, 旧系统退化为只用进出前台事件(误差=息屏前最后一段使用时间).
     */
    fun getLastActivityInfo(): Result<LastActivityInfo> = runCatching {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 24 * 60 * 60 * 1000L

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var lastActivity = 0L
        var screenOn: Boolean? = null

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.SCREEN_INTERACTIVE,
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (event.timeStamp > lastActivity) lastActivity = event.timeStamp
                }
            }
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> screenOn = false
            }
        }

        if (lastActivity <= 0L) throw IllegalStateException("无法获取最近使用时间")
        LastActivityInfo(lastActivityTime = lastActivity, screenOn = screenOn)
    }

    fun formatUsageTime(millis: Long): String {
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis % (1000 * 60 * 60)) / (1000 * 60)
        val seconds = (millis % (1000 * 60)) / 1000
        return when {
            hours > 0 -> "${hours}小时${minutes}分钟"
            minutes > 0 -> "${minutes}分钟${seconds}秒"
            else -> "${seconds}秒"
        }
    }
}