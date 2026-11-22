/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.service.WakeWordEngine
import me.rerere.rikkahub.service.WakeWordService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingWakeWordPage(vm: SettingVM = koinInject()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val wakeWord = settings.wakeWordSetting

    // 昵称实时校验反馈 (空串 = 通过)
    var validationError by remember { mutableStateOf("") }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.updateSettings(settings.copy(wakeWordSetting = wakeWord.copy(enabled = true)))
            WakeWordService.start(context)
        }
    }

    fun updateSetting(newSetting: me.rerere.rikkahub.data.datastore.WakeWordSetting) {
        vm.updateSettings(settings.copy(wakeWordSetting = newSetting))
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("语音唤醒") },
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
                        headlineContent = { Text("启用语音唤醒") },
                        supportingContent = { Text("全天候离线监听唤醒词（本地识别，不联网、不上传录音），喊昵称即可弹出聊天页") },
                        trailingContent = {
                            Switch(
                                checked = wakeWord.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            updateSetting(wakeWord.copy(enabled = true))
                                            WakeWordService.start(context)
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    } else {
                                        updateSetting(wakeWord.copy(enabled = false))
                                        WakeWordService.stop(context)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            if (wakeWord.enabled) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("唤醒词（昵称）") },
                            supportingContent = { Text("只支持 2~6 个汉字，改完自动生效，喊它就会弹出聊天页") },
                        )
                        item {
                            OutlinedTextField(
                                value = wakeWord.wakeWord,
                                onValueChange = { value ->
                                    validationError = runCatching {
                                        WakeWordEngine.buildKeywordLine(context, value)
                                        ""
                                    }.getOrElse { it.message ?: "唤醒词不可用" }
                                    updateSetting(wakeWord.copy(wakeWord = value))
                                },
                                label = { Text("昵称") },
                                isError = validationError.isNotBlank(),
                                supportingText = {
                                    if (validationError.isNotBlank()) {
                                        Text(validationError, color = MaterialTheme.colorScheme.error)
                                    } else {
                                        Text("默认「小橘」，常用如：小橙、小豆、宝宝")
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("识别灵敏度") },
                            supportingContent = { Text("被环境声误触发就调低，喊不动就调高；改完立即生效") },
                        )
                        item {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                val options = listOf(
                                    0 to "高灵敏（易误触）",
                                    1 to "均衡",
                                    2 to "抗噪（防误触）",
                                )
                                options.forEach { (value, label) ->
                                    androidx.compose.material3.FilterChip(
                                        selected = wakeWord.sensitivity == value,
                                        onClick = {
                                            updateSetting(wakeWord.copy(sensitivity = value))
                                        },
                                        label = { Text(label) },
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("触发冷却（秒）") },
                            supportingContent = { Text("两次唤醒的最小间隔，防止连喊发多条消息，默认 30 秒") },
                        )
                        item {
                            OutlinedTextField(
                                value = wakeWord.cooldownSeconds.toString(),
                                onValueChange = { value ->
                                    val seconds = value.toIntOrNull()?.coerceIn(5, 300) ?: 30
                                    updateSetting(wakeWord.copy(cooldownSeconds = seconds))
                                },
                                label = { Text("秒") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                    "• 识别完全在手机本地完成，不联网、不保存任何录音\n" +
                                        "• 喊唤醒词后会震动一下并自动弹出聊天页，以你的名义发一条昵称消息，AI 自动回复\n" +
                                        "• 语音通话期间会暂停监听让出麦克风，挂断后自动恢复\n" +
                                        "• 全天候常驻后台（通知栏常驻），建议把 App 加入电池优化白名单以保证不被杀"
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
