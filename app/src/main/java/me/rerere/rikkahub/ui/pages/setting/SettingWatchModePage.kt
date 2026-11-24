/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.service.WatchModeService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RiskConfirmDialog
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingWatchModePage(vm: SettingVM = koinInject()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var showRiskDialog by remember { mutableStateOf(false) }
    if (showRiskDialog) {
        RiskConfirmDialog(
            title = "开启监测模式",
            message = "监测模式是一个常驻前台服务，会持续关注你打开了哪些应用，并小幅耗电。需要开启使用情况访问权限。确定开启吗？",
            onConfirm = {
                showRiskDialog = false
                val newSetting = settings.proactiveMessageSetting.copy(watchModeEnabled = true)
                vm.updateSettings(settings.copy(proactiveMessageSetting = newSetting))
                try {
                    context.startForegroundService(Intent(context, WatchModeService::class.java))
                } catch (e: Exception) {
                    Log.e("SettingWatchMode", "Failed to start watch mode service", e)
                }
            },
            onDismiss = { showRiskDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("监测模式") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("启用监测模式") },
                        supportingContent = {
                            Text("打开名单里的应用并停留 5 秒后，AI 会收到一条监测提醒并回复你。同一个应用 10 分钟最多提醒一次。\n\nAI 的回复以通知形式出现，不会打断你当前的操作，消息也会留在聊天记录里。\n\n这是一个常驻前台服务，会持续小幅耗电。需要开启使用情况访问权限。")
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.proactiveMessageSetting.watchModeEnabled,
                                onCheckedChange = { enabled ->
                                    val newSetting = settings.proactiveMessageSetting.copy(watchModeEnabled = enabled)
                                    vm.updateSettings(settings.copy(proactiveMessageSetting = newSetting))
                                    if (enabled) {
                                        showRiskDialog = true
                                    } else {
                                        WatchModeService.stop(context)
                                    }
                                }
                            )
                        }
                    )
                }
            }
            if (settings.proactiveMessageSetting.watchModeEnabled) {
                item {
                    CardGroup {
                        // 已装应用列表（展示应用名 + 选择器数据源）
                        val installedApps = remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val pm = context.packageManager
                                installedApps.value = runCatching {
                                    pm.getInstalledApplications(0)
                                        .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                                        .map { it.packageName to pm.getApplicationLabel(it).toString() }
                                        .filter { it.first != context.packageName }
                                        .sortedBy { it.second }
                                }.getOrDefault(emptyList())
                            }
                        }
                        // 监测名单
                        settings.proactiveMessageSetting.watchPackages.forEach { pkg ->
                            item(
                                headlineContent = { Text(installedApps.value.find { it.first == pkg }?.second ?: pkg) },
                                supportingContent = { Text(pkg) },
                                trailingContent = {
                                    IconButton(onClick = {
                                        vm.updateSettings(
                                            settings.copy(
                                                proactiveMessageSetting = settings.proactiveMessageSetting.copy(
                                                    watchPackages = settings.proactiveMessageSetting.watchPackages - pkg
                                                )
                                            )
                                        )
                                    }) {
                                        Icon(HugeIcons.Delete01, contentDescription = "删除")
                                    }
                                }
                            )
                        }
                        // 添加应用
                        var showPicker by remember { mutableStateOf(false) }
                        val listFull = settings.proactiveMessageSetting.watchPackages.size >= 10
                        item(
                            headlineContent = { Text(if (listFull) "名单已满（最多 10 个）" else "添加应用") },
                            onClick = if (listFull) null else ({ showPicker = true })
                        )
                        if (showPicker) {
                            WatchAppPickerDialog(
                                installedApps = installedApps.value,
                                selected = settings.proactiveMessageSetting.watchPackages,
                                onPick = { pkg ->
                                    if (!listFull && !settings.proactiveMessageSetting.watchPackages.contains(pkg)) {
                                        vm.updateSettings(
                                            settings.copy(
                                                proactiveMessageSetting = settings.proactiveMessageSetting.copy(
                                                    watchPackages = settings.proactiveMessageSetting.watchPackages + pkg
                                                )
                                            )
                                        )
                                    }
                                },
                                onDismiss = { showPicker = false }
                            )
                        }
                    }
                }
            }
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("说明") },
                        supportingContent = {
                            Text("监测模式独立于激进模式和主动消息，可以单独使用，也可以同时开启。\n\n你也可以直接在聊天里让 AI 添加或删除监测名单里的应用（比如\"帮我监测微博\"）。\n\n提醒格式：[监测提醒] 宝宝打开了「XX」。AI 收到后一定会回复你。")
                        },
                    )
                }
            }
        }
    }
}

/**
 * 监测名单选应用弹窗：从已安装的可启动应用里挑选，支持搜索。
 * 点击即加入名单（已添加的重复点击无副作用），不自动关闭方便连续添加。
 */
@Composable
private fun WatchAppPickerDialog(
    installedApps: List<Pair<String, String>>,
    selected: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(installedApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) installedApps
        else installedApps.filter {
            it.second.lowercase().contains(q) || it.first.lowercase().contains(q)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        title = { Text("选择要监测的应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索应用") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (installedApps.isEmpty()) {
                    Text("正在加载应用列表...")
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered) { (pkg, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pkg) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, modifier = Modifier.weight(1f))
                            if (selected.contains(pkg)) {
                                Text(
                                    "已添加",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}
