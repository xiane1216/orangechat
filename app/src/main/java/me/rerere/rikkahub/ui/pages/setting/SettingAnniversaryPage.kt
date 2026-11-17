/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.dates.AnniversaryCalendar
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingAnniversaryPage(vm: SettingVM = koinInject()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val anniversary = settings.displaySetting
    var showStartDatePicker by remember { mutableStateOf(false) }

    fun updateDisplay(transform: (DisplaySetting) -> DisplaySetting) {
        vm.updateSettings(settings.copy(displaySetting = transform(settings.displaySetting)))
    }

    // 底部预览: 设置变化时实时重算
    val previewCard = remember(anniversary) {
        AnniversaryCalendar.buildDateCard(anniversary, LocalDate.now())
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("纪念日与生日") },
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
                        headlineContent = { Text("启用时间感知") },
                        supportingContent = { Text("开启后 AI 每次回复前都会收到今天的日期、农历、节日、节气、在一起天数和生日提醒") },
                        trailingContent = {
                            Switch(
                                checked = anniversary.anniversaryEnabled,
                                onCheckedChange = { enabled ->
                                    updateDisplay { it.copy(anniversaryEnabled = enabled) }
                                }
                            )
                        }
                    )
                }
            }

            if (anniversary.anniversaryEnabled) {
                // 农历生日 (优先使用)
                item {
                    CardGroup(
                        title = { Text("农历生日") },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        item(
                            headlineContent = { Text("月") },
                            supportingContent = { Text("按农历算的生日，每年随农历变化，优先于阳历生日") },
                        )
                        item {
                            Select(
                                options = (1..12).toList(),
                                selectedOption = anniversary.anniversaryLunarBirthdayMonth.coerceIn(1, 12),
                                onOptionSelected = { month ->
                                    updateDisplay { it.copy(anniversaryLunarBirthdayMonth = month) }
                                },
                                optionToString = { month -> "$month 月（${AnniversaryCalendar.lunarMonthName(month)}）" },
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        item(
                            headlineContent = { Text("日") },
                        )
                        item {
                            Select(
                                options = (1..30).toList(),
                                selectedOption = anniversary.anniversaryLunarBirthdayDay.coerceIn(1, 30),
                                onOptionSelected = { day ->
                                    updateDisplay { it.copy(anniversaryLunarBirthdayDay = day) }
                                },
                                optionToString = { day -> "$day 日（${AnniversaryCalendar.lunarDayName(day)}）" },
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // 阳历生日 (可选)
                item {
                    CardGroup(
                        title = { Text("阳历生日（可选）") },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        item(
                            headlineContent = { Text("月") },
                            supportingContent = { Text("没设置农历生日时按阳历生日提醒") },
                        )
                        item {
                            Select(
                                options = listOf(0) + (1..12).toList(),
                                selectedOption = anniversary.anniversarySolarBirthdayMonth.coerceIn(0, 12),
                                onOptionSelected = { month ->
                                    updateDisplay { it.copy(anniversarySolarBirthdayMonth = month) }
                                },
                                optionToString = { month -> if (month == 0) "未设置" else "$month 月" },
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        item(
                            headlineContent = { Text("日") },
                        )
                        item {
                            Select(
                                options = listOf(0) + (1..31).toList(),
                                selectedOption = anniversary.anniversarySolarBirthdayDay.coerceIn(0, 31),
                                onOptionSelected = { day ->
                                    updateDisplay { it.copy(anniversarySolarBirthdayDay = day) }
                                },
                                optionToString = { day -> if (day == 0) "未设置" else "$day 日" },
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // 在一起的日子
                item {
                    CardGroup(
                        title = { Text("在一起的日子") },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        item(
                            onClick = { showStartDatePicker = true },
                            headlineContent = { Text("选择日期") },
                            supportingContent = {
                                Text(
                                    anniversary.anniversaryStartDate
                                        ?: "没设置，选一个日子后 AI 会记得你们在一起多少天"
                                )
                            },
                        )
                        if (!anniversary.anniversaryStartDate.isNullOrBlank()) {
                            item(
                                onClick = { updateDisplay { it.copy(anniversaryStartDate = null) } },
                                headlineContent = { Text("清除") },
                            )
                        }
                    }
                }

                // 说明
                item {
                    CardGroup(modifier = Modifier.padding(top = 8.dp)) {
                        item(
                            headlineContent = { Text("说明") },
                            supportingContent = {
                                Text(
                                    "• 农历生日优先，其次阳历生日，都会提前提醒 AI\n" +
                                        "• 农历生日选腊月三十时，没有三十的年份自动按腊月最后一天过\n" +
                                        "• 节日和节气由 App 自动识别，包括春节、七夕、中秋和二十四节气等\n" +
                                        "• 下方是 AI 实际收到的注入卡片预览"
                                )
                            }
                        )
                    }
                }

                // 实时预览
                item {
                    CardGroup(
                        title = { Text("注入卡片预览") },
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    ) {
                        item(
                            headlineContent = {
                                Text(previewCard ?: "暂无可展示内容，请先设置生日或在一起的日子")
                            }
                        )
                    }
                }
            }
        }
    }

    // 在一起的日子选择弹窗
    if (showStartDatePicker) {
        val initialMillis = anniversary.anniversaryStartDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            updateDisplay { it.copy(anniversaryStartDate = date.toString()) }
                        }
                        showStartDatePicker = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
