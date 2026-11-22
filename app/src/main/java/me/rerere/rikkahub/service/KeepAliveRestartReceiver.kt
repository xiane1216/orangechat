/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 保活服务拉起接收器（2025-11 重构）。
 *
 * 处理三类事件（均为系统豁免的后台启动场景，拉起前台服务真正生效）：
 * - BOOT_COMPLETED / QUICKBOOT_POWERON：开机完成
 * - MY_PACKAGE_REPLACED：覆盖安装后（系统会杀掉进程与 FGS，必须重新拉起）
 * - ACTION_KEEP_ALIVE_WATCHDOG：15 分钟看门狗闹钟（兜底：所有链路都失效时重拉）
 *
 * 是否运行由 KeepAliveService.onStartCommand 自决（读保活开关，关了就退出），
 * receiver 保持无脑转发，不做不可靠的"服务是否存活"判断。
 *
 * 注意：部分深度定制的 ROM（MIUI/鸿蒙等）可能拦截广播拉起，建议用户同时把
 * 本 App 加入电池优化白名单并允许自启动。
 */
class KeepAliveRestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KeepAliveRestartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            KeepAliveService.ACTION_KEEP_ALIVE_WATCHDOG -> {
                Log.d(TAG, "收到拉起事件: ${intent.action}")
                KeepAliveService.start(context)
                // 语音唤醒服务同步拉起 (是否运行由 WakeWordService.startIfEnabled 自决: 读开关+麦克风权限)
                WakeWordService.startIfEnabled(context)
            }
        }
    }
}
