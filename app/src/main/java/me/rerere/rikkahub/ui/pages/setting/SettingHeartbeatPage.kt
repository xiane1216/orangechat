/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.service.HeartbeatService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingHeartbeatPage(vm: SettingVM = koinInject()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val heartbeat = settings.heartbeatSetting

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("心跳机制") },
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
                        headlineContent = { Text("启用心跳机制") },
                        supportingContent = { Text("你连续 N 分钟没回消息时，AI 主动关心你（可调用工具/发消息/保持沉默）") },
                        trailingContent = {
                            Switch(
                                checked = heartbeat.enabled,
                                onCheckedChange = { enabled ->
                                    val newSetting = heartbeat.copy(enabled = enabled)
                                    vm.updateSettings(settings.copy(heartbeatSetting = newSetting))
                                    if (enabled) {
                                        HeartbeatService.scheduleNext(context, newSetting)
                                    } else {
                                        HeartbeatService.cancel(context)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            if (heartbeat.enabled) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("闲置触发时长（分钟）") },
                            supportingContent = { Text("你多久没发消息后触发心跳，默认 30 分钟") },
                        )
                        item {
                            OutlinedTextField(
                                value = heartbeat.idleMinutes.toString(),
                                onValueChange = { value ->
                                    val minutes = value.toIntOrNull()?.coerceIn(1, 1440) ?: 30
                                    val newSetting = heartbeat.copy(idleMinutes = minutes)
                                    vm.updateSettings(settings.copy(heartbeatSetting = newSetting))
                                    HeartbeatService.scheduleNext(context, newSetting)
                                },
                                label = { Text("分钟") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("说明") },
                            supportingContent = {
                                Text(
                                    "• 只有你连续 ${heartbeat.idleMinutes} 分钟没发新消息时才会触发\n" +
                                        "• 正常聊天过程中（你持续发消息）不会触发\n" +
                                        "• 触发后 AI 可自主决定：调用工具（如查监控）、主动说话、或保持沉默\n" +
                                        "• AI 觉得没话说可回复 [PASS] 保持安静"
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
