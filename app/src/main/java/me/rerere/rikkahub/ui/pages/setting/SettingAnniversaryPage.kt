/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.dates.AnniversaryCalendar
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject

// 本页专属色板, 取值来自视觉定稿 (rely-fix-v4 第三标签), 不走主题色
private object AnniversaryColors {
    val Ink = Color(0xFF3B3735)      // 主文字
    val Ink2 = Color(0xFF8A8480)     // 次级文字 (单位字)
    val Ink3 = Color(0xFFB8B2AD)     // 弱化文字 (分组标签/副标题)
    val Rose = Color(0xFFD3A9A3)     // 玫瑰粉 (开关/农历标签)
    val RoseDeep = Color(0xFFC4958E) // 深玫瑰 (预览标题)
    val RoseHot = Color(0xFFB0736B)  // 高亮数值
    val BoxBg = Color(0xFFF3EFEC)    // 数字方块/日期 chip 底色
    val Divider = Color(0xFFF3EEEA)  // 行分隔线 (极浅)
    val FestCn = Color(0xFFC9A29E)   // 节日标签: 国内
    val FestIntl = Color(0xFFA9B4C9) // 节日标签: 国外
    val SwitchOff = Color(0xFFDDD6D0)
    val DashLine = Color(0xFFC8B4AA).copy(alpha = 0.3f)   // 预览虚线
    val PreviewBorder = Color(0xFFD3A9A3).copy(alpha = 0.25f)
}

// 生日字段选择弹窗的目标字段
private enum class BirthdayField(val title: String) {
    LunarMonth("农历生日 · 选月"),
    LunarDay("农历生日 · 选日"),
    SolarMonth("阳历生日 · 选月"),
    SolarDay("阳历生日 · 选日"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingAnniversaryPage(vm: SettingVM = koinInject()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val anniversary = settings.displaySetting
    var showStartDatePicker by remember { mutableStateOf(false) }
    var birthdayField by remember { mutableStateOf<BirthdayField?>(null) }

    fun updateDisplay(transform: (DisplaySetting) -> DisplaySetting) {
        vm.updateSettings(settings.copy(displaySetting = transform(settings.displaySetting)))
    }

    // "现在"预览: 设置变化时实时由 AnniversaryCalendar 重算
    val today = remember { LocalDate.now() }
    val togetherDays = AnniversaryCalendar.daysTogether(anniversary.anniversaryStartDate, today)
    val birthday = AnniversaryCalendar.daysUntilBirthday(anniversary, today)
    val birthdayNote = run {
        val lm = anniversary.anniversaryLunarBirthdayMonth
        val ld = anniversary.anniversaryLunarBirthdayDay
        val sm = anniversary.anniversarySolarBirthdayMonth
        val sd = anniversary.anniversarySolarBirthdayDay
        when {
            lm in 1..12 && ld in 1..30 ->
                "农历${AnniversaryCalendar.lunarMonthName(lm)}${AnniversaryCalendar.lunarDayName(ld)}"
            sm in 1..12 && sd in 1..31 -> "${sm}月${sd}日"
            else -> null
        }
    }

    // 在一起的日子 chip 文案
    val startDate = anniversary.anniversaryStartDate
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val startChipText = startDate
        ?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" }
        ?: "选择日期"

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
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            // 总开关
            item { GroupLabel("总开关", isFirst = true) }
            item {
                AnniversaryCard {
                    SettingRow(
                        title = "让他记住这些日子",
                        subtitle = "每次回复前，把今天的日子悄悄告诉他",
                        trailing = {
                            Switch(
                                checked = anniversary.anniversaryEnabled,
                                onCheckedChange = { enabled ->
                                    updateDisplay { it.copy(anniversaryEnabled = enabled) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = AnniversaryColors.Rose,
                                    checkedThumbColor = Color.White,
                                    uncheckedTrackColor = AnniversaryColors.SwitchOff,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedBorderColor = Color.Transparent,
                                ),
                            )
                        }
                    )
                }
            }

            // 宝宝的信息
            item { GroupLabel("宝宝的信息") }
            item {
                AnniversaryCard {
                    val lm = anniversary.anniversaryLunarBirthdayMonth
                    val ld = anniversary.anniversaryLunarBirthdayDay
                    val sm = anniversary.anniversarySolarBirthdayMonth
                    val sd = anniversary.anniversarySolarBirthdayDay
                    SettingRow(
                        title = "农历生日",
                        subtitle = "没有年份，每年按农历算",
                        titleLeading = { LunarTag() },
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                NumBox(
                                    text = if (lm in 1..12) "$lm" else "-",
                                    empty = lm !in 1..12,
                                    onClick = { birthdayField = BirthdayField.LunarMonth },
                                )
                                Text("月", fontSize = 11.sp, color = AnniversaryColors.Ink2)
                                NumBox(
                                    text = if (ld in 1..30) AnniversaryCalendar.lunarDayName(ld) else "-",
                                    empty = ld !in 1..30,
                                    onClick = { birthdayField = BirthdayField.LunarDay },
                                )
                                Text("日", fontSize = 11.sp, color = AnniversaryColors.Ink2)
                            }
                        }
                    )
                    RowDivider()
                    SettingRow(
                        title = "阳历生日（可选）",
                        subtitle = "没设置农历生日时按阳历生日提醒",
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                NumBox(
                                    text = if (sm in 1..12) "$sm" else "-",
                                    empty = sm !in 1..12,
                                    onClick = { birthdayField = BirthdayField.SolarMonth },
                                )
                                Text("月", fontSize = 11.sp, color = AnniversaryColors.Ink2)
                                NumBox(
                                    text = if (sd in 1..31) "$sd" else "-",
                                    empty = sd !in 1..31,
                                    onClick = { birthdayField = BirthdayField.SolarDay },
                                )
                                Text("日", fontSize = 11.sp, color = AnniversaryColors.Ink2)
                            }
                        }
                    )
                }
            }

            // 我们
            item { GroupLabel("我们") }
            item {
                AnniversaryCard {
                    SettingRow(
                        title = "在一起的日子",
                        subtitle = "算了多少天就从这天开始",
                        onClick = { showStartDatePicker = true },
                        trailing = {
                            DateChip(
                                text = startChipText,
                                empty = startDate == null,
                                onClick = { showStartDatePicker = true },
                            )
                        }
                    )
                }
            }

            // 现在: 实时预览
            item { GroupLabel("现在") }
            item {
                PreviewCard(
                    together = togetherDays,
                    birthday = birthday,
                    birthdayNote = birthdayNote,
                    today = today,
                )
            }

            // 他知道的节日（部分）
            item { GroupLabel("他知道的节日（部分）") }
            item {
                AnniversaryCard {
                    AnniversaryCalendar.festivalSamples().forEachIndexed { index, (name, origin, date) ->
                        if (index > 0) RowDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FestTag(origin)
                            Spacer(Modifier.width(8.dp))
                            Text(name, fontSize = 12.sp, color = AnniversaryColors.Ink)
                            Spacer(Modifier.weight(1f))
                            Text(date, fontSize = 10.sp, color = AnniversaryColors.Ink3)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // 生日月/日选择弹窗
    birthdayField?.let { field ->
        val current = when (field) {
            BirthdayField.LunarMonth -> anniversary.anniversaryLunarBirthdayMonth
            BirthdayField.LunarDay -> anniversary.anniversaryLunarBirthdayDay
            BirthdayField.SolarMonth -> anniversary.anniversarySolarBirthdayMonth
            BirthdayField.SolarDay -> anniversary.anniversarySolarBirthdayDay
        }
        // 选项 (值, 文案); 阳历的 0 表示未设置
        val options: List<Pair<Int, String>> = when (field) {
            BirthdayField.LunarMonth -> (1..12).map { it to "$it 月（${AnniversaryCalendar.lunarMonthName(it)}）" }
            BirthdayField.LunarDay -> (1..30).map { it to "$it 日（${AnniversaryCalendar.lunarDayName(it)}）" }
            BirthdayField.SolarMonth -> (0..12).map { it to if (it == 0) "未设置" else "$it 月" }
            BirthdayField.SolarDay -> (0..31).map { it to if (it == 0) "未设置" else "$it 日" }
        }
        AlertDialog(
            onDismissRequest = { birthdayField = null },
            confirmButton = {
                TextButton(onClick = { birthdayField = null }) { Text("关闭") }
            },
            title = { Text(field.title) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                ) {
                    items(options.size) { index ->
                        val (value, label) = options[index]
                        val selected = value == current
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            color = if (selected) AnniversaryColors.RoseHot else AnniversaryColors.Ink,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    when (field) {
                                        BirthdayField.LunarMonth -> updateDisplay { it.copy(anniversaryLunarBirthdayMonth = value) }
                                        BirthdayField.LunarDay -> updateDisplay { it.copy(anniversaryLunarBirthdayDay = value) }
                                        BirthdayField.SolarMonth -> updateDisplay { it.copy(anniversarySolarBirthdayMonth = value) }
                                        BirthdayField.SolarDay -> updateDisplay { it.copy(anniversarySolarBirthdayDay = value) }
                                    }
                                    birthdayField = null
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        )
                    }
                }
            },
        )
    }

    // 在一起的日子选择弹窗
    if (showStartDatePicker) {
        val initialMillis = startDate
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
                Row {
                    if (startDate != null) {
                        TextButton(
                            onClick = {
                                updateDisplay { it.copy(anniversaryStartDate = null) }
                                showStartDatePicker = false
                            }
                        ) { Text("清除") }
                    }
                    TextButton(onClick = { showStartDatePicker = false }) { Text("取消") }
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

// 分组小标签 (10sp 灰、字距宽)
@Composable
private fun GroupLabel(text: String, isFirst: Boolean = false) {
    Text(
        text = text,
        color = AnniversaryColors.Ink3,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(
            start = 3.dp,
            top = if (isFirst) 4.dp else 15.dp,
            bottom = 7.dp,
        ),
    )
}

// 白底圆角 15dp 卡片
@Composable
private fun AnniversaryCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(15.dp), clip = true)
            .background(Color.White.copy(alpha = 0.92f)),
        content = content,
    )
}

// 卡片内的行分隔线 (极浅)
@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AnniversaryColors.Divider)
    )
}

// 设置行: 左侧标题+副标题, 右侧自定义内容
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    titleLeading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = AnniversaryColors.Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                titleLeading?.invoke()
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = AnniversaryColors.Ink3,
                    fontSize = 10.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

// 40x31dp 圆角 8dp 浅灰底小方块 (月/日选择入口)
@Composable
private fun NumBox(text: String, empty: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(31.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AnniversaryColors.BoxBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            maxLines = 1,
            fontWeight = if (empty) FontWeight.Normal else FontWeight.SemiBold,
            color = if (empty) AnniversaryColors.Ink3 else AnniversaryColors.Ink,
        )
    }
}

// "农历"粉色小标签 (9sp 白字粉底圆角 5dp)
@Composable
private fun LunarTag() {
    Text(
        text = "农历",
        color = Color.White,
        fontSize = 9.sp,
        letterSpacing = 0.7.sp,
        modifier = Modifier
            .padding(start = 5.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(AnniversaryColors.Rose)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// 节日来源小标签 (国内粉底 / 国外蓝灰底)
@Composable
private fun FestTag(origin: AnniversaryCalendar.FestivalOrigin) {
    Text(
        text = origin.label,
        color = Color.White,
        fontSize = 9.sp,
        letterSpacing = 0.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (origin == AnniversaryCalendar.FestivalOrigin.CN) {
                    AnniversaryColors.FestCn
                } else {
                    AnniversaryColors.FestIntl
                }
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// 日期 chip (12sp 圆角 8dp 浅灰底)
@Composable
private fun DateChip(text: String, empty: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AnniversaryColors.BoxBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (empty) FontWeight.Normal else FontWeight.SemiBold,
            color = if (empty) AnniversaryColors.Ink3 else AnniversaryColors.Ink,
        )
    }
}

// 预览卡: 浅粉渐变底 + 细边框, 行式列表用虚线分隔
@Composable
private fun PreviewCard(
    together: Int?,
    birthday: Pair<Int, Boolean>?,
    birthdayNote: String?,
    today: LocalDate,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .border(1.dp, AnniversaryColors.PreviewBorder, RoundedCornerShape(15.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFFFBF6F4), Color(0xFFF6ECE9)))
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "他今天会知道的",
            color = AnniversaryColors.RoseDeep,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(10.dp))
        PreviewLine(
            key = "在一起",
            value = together?.let { "第 $it 天" } ?: "还没设置",
            hot = together != null,
            dashed = true,
        )
        PreviewLine(
            key = "宝宝生日",
            value = birthday
                ?.let { (days, isToday) ->
                    val note = birthdayNote?.let { "（$it）" } ?: ""
                    if (isToday) "就是今天$note" else "还有 $days 天$note"
                }
                ?: "还没设置",
            hot = birthday != null,
            dashed = true,
        )
        PreviewLine(
            key = "今天",
            value = "${today.monthValue}月${today.dayOfMonth}日 " +
                AnniversaryCalendar.weekName(today) +
                " · " + AnniversaryCalendar.lunarDateText(today),
            hot = false,
            dashed = false,
        )
    }
}

// 预览单行: k 固定 64dp 灰, v 右侧内容; dashed 为 true 时底部画虚线
@Composable
private fun PreviewLine(key: String, value: String, hot: Boolean, dashed: Boolean) {
    val dashColor = AnniversaryColors.DashLine
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (dashed) {
                    Modifier.drawBehind {
                        drawLine(
                            color = dashColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                        )
                    }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = key,
            color = AnniversaryColors.Ink3,
            fontSize = 12.sp,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            color = if (hot) AnniversaryColors.RoseHot else AnniversaryColors.Ink,
            fontSize = 12.sp,
            fontWeight = if (hot) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}
