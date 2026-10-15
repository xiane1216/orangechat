/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun createAppUsageTool(context: Context): Tool = Tool(
    name = "get_app_usage",
    description = "Get today's app usage statistics. Returns app names, usage time, and last used time.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum number of apps to return (default 10)")
                }
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        // 无"使用情况访问"权限时 queryUsageStats 返回空列表, 会被误报为"今天没玩手机"(success=true, count=0),
        // 先显式检查权限, 无权限时返回明确的错误信息
        if (!SystemTools.hasAppUsagePermission(context)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "App usage permission not granted. Enable 'Usage access' for this app in system settings.")
                }.toString()
            ))
        }
        try {
            val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 10
            // 改用 AppUsageService 的事件配对口径(与系统"屏幕使用时间"一致).
            // 之前直接读 queryUsageStats 统计桶总账, 会把前一天使用并进"今天"且含息屏幻影计时,
            // 导致数字虚高(微信几百分钟问题的直接来源).
            val stats = me.rerere.rikkahub.data.service.AppUsageService(context)
                .getTodayUsageStats()
                .getOrThrow()
                .take(limit)

            if (stats.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("count", 0)
                        put("message", "No app usage data. Grant usage access permission.")
                    }.toString()
                ))
            }

            val arr = buildJsonArray {
                stats.forEach { stat ->
                    val appName = try {
                        val appInfo = context.packageManager.getApplicationInfo(stat.packageName, 0)
                        context.packageManager.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) {
                        stat.packageName
                    }
                    add(buildJsonObject {
                        put("app_name", appName)
                        put("package_name", stat.packageName)
                        put("usage_minutes", (stat.totalTimeInForeground / 60000).toInt())
                        put("last_used", dateFormat.format(Date(stat.lastTimeUsed)))
                    })
                }
            }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("count", stats.size)
                    put("apps", arr)
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)