/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.update
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.service.ChatService
import org.koin.core.context.GlobalContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 纪念日小组件刷新调度:
 * - WorkManager 每 24 小时一次, initialDelay 对齐下一个零点 (跨天保证"第 N 天"翻页)
 * - 监听 ChatService.generationDoneFlow, 一轮回复生成完成后立即刷新"最近一句"
 * 在 RikkaHubApp.onCreate 中调用 [start] 启动
 */
object AnniversaryWidgetUpdater {

    private const val TAG = "AnniversaryWidgetUpdater"
    private const val UNIQUE_WORK_NAME = "anniversary_widget_daily_update"

    fun start(context: Context) {
        val appContext = context.applicationContext
        scheduleDailyUpdate(appContext)
        observeGenerationDone(appContext)
        // 启动时立即刷新一次, 保证数据与当前设置同步
        refreshOnce(appContext)
    }

    /**
     * 每天 24 小时一次的周期任务, 首次执行时间对齐下一个自然日零点
     */
    private fun scheduleDailyUpdate(context: Context) {
        runCatching {
            val now = LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atTime(LocalTime.MIDNIGHT)
            val request = PeriodicWorkRequestBuilder<AnniversaryMidnightWorker>(Duration.ofHours(24))
                .setInitialDelay(Duration.between(now, nextMidnight))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    /**
     * 监听生成完成事件刷新小组件 (进程存活期间生效)
     */
    private fun observeGenerationDone(context: Context) {
        runCatching {
            val koin = GlobalContext.get()
            val chatService = koin.get<ChatService>()
            val appScope = koin.get<AppScope>()
            appScope.launch {
                chatService.generationDoneFlow.collect {
                    updateAllWidgets(context)
                }
            }
        }
    }

    private fun refreshOnce(context: Context) {
        runCatching {
            GlobalContext.get().get<AppScope>().launch {
                updateAllWidgets(context)
            }
        }
    }

    /**
     * 刷新桌面上所有纪念日小组件实例
     */
    suspend fun updateAllWidgets(context: Context) {
        runCatching {
            val manager = GlanceAppWidgetManager(context)
            val ids = manager.getGlanceIds(AnniversaryWidget::class.java)
            ids.forEach { id ->
                AnniversaryWidget().update(context, id)
            }
        }
    }
}

/**
 * 零点定时刷新任务
 */
class AnniversaryMidnightWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AnniversaryWidgetUpdater.updateAllWidgets(applicationContext)
        return Result.success()
    }
}
