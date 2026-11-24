/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TAG = "AppWatchlistTool"

/** 监测名单上限 */
private const val WATCH_LIST_MAX = 10

private data class InstalledApp(
    val packageName: String,
    val name: String,
)

private fun listLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return runCatching {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .filter { it.packageName != context.packageName } // 排除自己
    }.getOrDefault(emptyList())
}

/**
 * 按名字模糊匹配: 完全相等 > 包名相等 > 包含关系. 返回 (精确命中, 候选列表)
 */
private fun matchApps(apps: List<InstalledApp>, query: String): Pair<InstalledApp?, List<InstalledApp>> {
    val q = query.trim()
    if (q.isEmpty()) return null to emptyList()
    val qLower = q.lowercase()
    // 名字完全相等（忽略大小写）
    val exact = apps.filter { it.name.lowercase() == qLower }
    if (exact.size == 1) return exact[0] to emptyList()
    if (exact.size > 1) return null to exact
    // 包名相等
    val byPkg = apps.filter { it.packageName.equals(q, ignoreCase = true) }
    if (byPkg.size == 1) return byPkg[0] to emptyList()
    // 名字包含
    val contains = apps.filter { it.name.lowercase().contains(qLower) }.take(8)
    if (contains.size == 1) return contains[0] to emptyList()
    return null to contains
}

private fun watchListJson(apps: List<InstalledApp>, packages: List<String>): String {
    return buildJsonObject {
        put("success", true)
        put("action", "list")
        put("count", packages.size)
        put("max", WATCH_LIST_MAX)
        putJsonArray("apps") {
            packages.forEach { pkg ->
                add(buildJsonObject {
                    put("package_name", pkg)
                    put("app_name", apps.find { it.packageName == pkg }?.name ?: pkg)
                })
            }
        }
    }.toString()
}

/**
 * 监测名单管理工具 (监测模式).
 * AI 可以查看/添加/删除被监测的应用; 用户打开名单里的应用并停留后,
 * AI 会收到一条监测提醒并自动回复.
 */
fun createAppWatchlistTool(
    context: Context,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "app_watchlist",
    needsApproval = false,
    description = "Manage the app watchlist of watch mode. Watch mode notifies you when the user opens a watched app. " +
        "Actions: 'list' (show the current watchlist), 'add' (add an app by app_name or package_name), 'remove' (remove an app). " +
        "When adding by app_name and multiple apps match, the result contains 'candidates' — ALWAYS show these candidates to the user " +
        "and let her pick one, NEVER pick one by yourself. The watchlist holds at most $WATCH_LIST_MAX apps.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "Action to perform: 'list', 'add', or 'remove'")
                    putJsonArray("enum") {
                        add("list")
                        add("add")
                        add("remove")
                    }
                }
                putJsonObject("app_name") {
                    put("type", "string")
                    put("description", "App display name, e.g. '抖音'. Use this for add/remove when you don't know the exact package name")
                }
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "Exact package name, e.g. 'com.ss.android.ugc.aweme'. Takes precedence over app_name")
                }
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.toString()?.trim('"') ?: ""
        try {
            val apps = listLaunchableApps(context)
            when (action) {
                "list" -> {
                    val current = settingsStore.settingsFlowRaw.first().proactiveMessageSetting.watchPackages
                    listOf(UIMessagePart.Text(watchListJson(apps, current)))
                }
                "add" -> {
                    val query = params["package_name"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
                        ?: params["app_name"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
                    if (query == null) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject { put("success", false); put("error", "需要提供 app_name 或 package_name") }.toString()
                        ))
                    }
                    val current = settingsStore.settingsFlowRaw.first().proactiveMessageSetting.watchPackages
                    val exactMatch = apps.find { it.packageName == query }
                    val (app, candidates) = if (exactMatch != null) exactMatch to emptyList() else matchApps(apps, query)
                    when {
                        // 歧义: 返回候选让用户确认, AI 不得擅自选择
                        app == null && candidates.isNotEmpty() -> listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("need_confirm", true)
                                put("error", "找到 ${candidates.size} 个名字相似的应用，请让用户确认要监测哪一个，不要自行选择")
                                putJsonArray("candidates") {
                                    candidates.forEach { c ->
                                        add(buildJsonObject {
                                            put("package_name", c.packageName)
                                            put("app_name", c.name)
                                        })
                                    }
                                }
                            }.toString()
                        ))
                        app == null -> listOf(UIMessagePart.Text(
                            buildJsonObject { put("success", false); put("error", "没有找到名为「$query」的已安装应用") }.toString()
                        ))
                        current.contains(app.packageName) -> listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", "「${app.name}」已经在监测名单里了")
                                put("apps", buildJsonArray { current.forEach { add(it) } })
                            }.toString()
                        ))
                        current.size >= WATCH_LIST_MAX -> listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", "监测名单已满（最多 $WATCH_LIST_MAX 个），请先删除一些应用")
                                put("apps", buildJsonArray { current.forEach { add(it) } })
                            }.toString()
                        ))
                        else -> {
                            val newPackages = current + app.packageName
                            settingsStore.update { s ->
                                s.copy(proactiveMessageSetting = s.proactiveMessageSetting.copy(watchPackages = newPackages))
                            }
                            Log.d(TAG, "Added to watchlist: ${app.packageName} (${app.name})")
                            listOf(UIMessagePart.Text(buildJsonObject {
                                put("success", true)
                                put("action", "add")
                                put("added", buildJsonObject {
                                    put("package_name", app.packageName)
                                    put("app_name", app.name)
                                })
                                put("count", newPackages.size)
                                put("apps", buildJsonArray { newPackages.forEach { add(it) } })
                            }.toString()))
                        }
                    }
                }
                "remove" -> {
                    val query = params["package_name"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
                        ?: params["app_name"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
                    if (query == null) {
                        return@Tool listOf(UIMessagePart.Text(
                            buildJsonObject { put("success", false); put("error", "需要提供 app_name 或 package_name") }.toString()
                        ))
                    }
                    val current = settingsStore.settingsFlowRaw.first().proactiveMessageSetting.watchPackages
                    val (app, candidates) = matchApps(apps.filter { current.contains(it.packageName) }, query)
                    when {
                        app == null && candidates.isNotEmpty() -> listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("need_confirm", true)
                                put("error", "名单里有 ${candidates.size} 个名字相似的应用，请让用户确认要删除哪一个，不要自行选择")
                                putJsonArray("candidates") {
                                    candidates.forEach { c ->
                                        add(buildJsonObject {
                                            put("package_name", c.packageName)
                                            put("app_name", c.name)
                                        })
                                    }
                                }
                            }.toString()
                        ))
                        app == null -> listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", "监测名单里没有找到「$query」")
                                put("apps", buildJsonArray { current.forEach { add(it) } })
                            }.toString()
                        ))
                        else -> {
                            val newPackages = current - app.packageName
                            settingsStore.update { s ->
                                s.copy(proactiveMessageSetting = s.proactiveMessageSetting.copy(watchPackages = newPackages))
                            }
                            Log.d(TAG, "Removed from watchlist: ${app.packageName} (${app.name})")
                            listOf(UIMessagePart.Text(buildJsonObject {
                                put("success", true)
                                put("action", "remove")
                                put("removed", buildJsonObject {
                                    put("package_name", app.packageName)
                                    put("app_name", app.name)
                                })
                                put("count", newPackages.size)
                                put("apps", buildJsonArray { newPackages.forEach { add(it) } })
                            }.toString()))
                        }
                    }
                }
                else -> listOf(UIMessagePart.Text(
                    buildJsonObject { put("success", false); put("error", "Unknown action: $action. Use 'list', 'add' or 'remove'.") }.toString()
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "app_watchlist failed", e)
            listOf(UIMessagePart.Text(
                buildJsonObject { put("success", false); put("error", e.message ?: "Unknown error") }.toString()
            ))
        }
    }
)
