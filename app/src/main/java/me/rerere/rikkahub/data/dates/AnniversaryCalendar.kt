/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.dates

import android.icu.util.ChineseCalendar
import android.icu.util.Calendar
import android.icu.util.TimeZone
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import me.rerere.rikkahub.data.datastore.DisplaySetting

/**
 * 时间感知: 农历/节日/节气/纪念日计算
 *
 * 农历换算基于安卓自带的 android.icu.util.ChineseCalendar, 无需第三方库。
 * 节气使用寿星通式近似日期表(21世纪, 个别年份可能有 1 天误差)。
 */
object AnniversaryCalendar {

    /** 农历日期 */
    data class LunarDate(
        val year: Int,
        val month: Int,
        val day: Int,
        val isLeapMonth: Boolean,
    )

    private val LUNAR_MONTH_NAMES = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月",
    )

    private val LUNAR_DIGITS = arrayOf(
        "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
    )

    private val WEEK_NAMES = arrayOf("一", "二", "三", "四", "五", "六", "日")

    // 公历节日表 (月-日 -> 名称)
    private val SOLAR_FESTIVALS: Map<Pair<Int, Int>, String> = mapOf(
        (1 to 1) to "元旦",
        (2 to 14) to "情人节",
        (3 to 8) to "妇女节",
        (3 to 12) to "植树节",
        (5 to 1) to "劳动节",
        (5 to 4) to "青年节",
        (6 to 1) to "儿童节",
        (7 to 1) to "建党节",
        (8 to 1) to "建军节",
        (9 to 10) to "教师节",
        (10 to 1) to "国庆节",
        (12 to 24) to "平安夜",
        (12 to 25) to "圣诞节",
    )

    // 农历节日表 (农历月-日 -> 名称), 闰月不过节
    private val LUNAR_FESTIVALS: Map<Pair<Int, Int>, String> = mapOf(
        (1 to 1) to "春节",
        (1 to 15) to "元宵节",
        (2 to 2) to "龙头节",
        (5 to 5) to "端午节",
        (7 to 7) to "七夕",
        (7 to 15) to "中元节",
        (8 to 15) to "中秋节",
        (9 to 9) to "重阳节",
        (12 to 8) to "腊八节",
    )

    // 24 节气名称, 从小寒开始, 按公历月份每两月一组的顺序排列
    private val SOLAR_TERM_NAMES = arrayOf(
        "小寒", "大寒", "立春", "雨水", "惊蛰", "春分",
        "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
        "小暑", "大暑", "立秋", "处暑", "白露", "秋分",
        "寒露", "霜降", "立冬", "小雪", "大雪", "冬至",
    )

    // 24 节气寿星通式 C 值 (21 世纪), 与 SOLAR_TERM_NAMES 一一对应
    private val SOLAR_TERM_C = doubleArrayOf(
        5.4055, 20.12, 3.87, 18.73, 5.63, 20.646,
        4.81, 20.1, 5.52, 21.04, 5.678, 21.37,
        7.108, 22.83, 7.5, 23.13, 7.646, 23.042,
        8.318, 23.438, 7.438, 22.36, 7.18, 21.94,
    )

    // 公历 -> 农历
    fun lunarOf(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): LunarDate {
        val cal = ChineseCalendar(TimeZone.getTimeZone(zone.id))
        cal.time = java.util.Date.from(date.atStartOfDay(zone).toInstant())
        return LunarDate(
            year = cal.get(Calendar.EXTENDED_YEAR) - 2697,
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH),
            isLeapMonth = cal.get(ChineseCalendar.IS_LEAP_MONTH) == 1,
        )
    }

    /**
     * 农历日期 -> 对应公历日期 (农历年为基准, 日期可能落在次年公历年初)
     *
     * 农历日超出当月天数时取该月最后一天 (如腊月三十不存在的年份按腊月廿九, 即除夕语义)。
     * 若该年不存在目标农历月 (极端边界), 换算结果经 round-trip 校验后返回 null。
     */
    fun solarOfLunar(
        lunarYear: Int,
        lunarMonth: Int,
        lunarDay: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate? {
        return runCatching {
            val cal = ChineseCalendar(TimeZone.getTimeZone(zone.id))
            cal.clear()
            cal.set(Calendar.EXTENDED_YEAR, lunarYear + 2697)
            cal.set(Calendar.MONTH, lunarMonth - 1)
            cal.set(ChineseCalendar.IS_LEAP_MONTH, 0)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val targetDay = lunarDay.coerceAtMost(maxDay)
            cal.set(Calendar.DAY_OF_MONTH, targetDay)
            val solar = java.time.Instant.ofEpochMilli(cal.time.time)
                .atZone(zone).toLocalDate()
            // round-trip 校验: 换算回农历必须还是目标农历月的同一天
            val back = lunarOf(solar, zone)
            if (!back.isLeapMonth && back.month == lunarMonth && back.day == targetDay) {
                solar
            } else {
                null
            }
        }.getOrNull()
    }

    // 农历月名称: 正月..腊月 (1-12)
    fun lunarMonthName(month: Int): String {
        return LUNAR_MONTH_NAMES.getOrElse(month - 1) { "$month 月" }
    }

    // 农历日名称: 初一..初十 / 十一..十九 / 二十 / 廿一..廿九 / 三十
    fun lunarDayName(day: Int): String = when {
        day == 30 -> "三十"
        day == 20 -> "二十"
        day == 10 -> "初十"
        day in 1..9 -> "初" + LUNAR_DIGITS[day - 1]
        day in 11..19 -> "十" + LUNAR_DIGITS[day - 11]
        day in 21..29 -> "廿" + LUNAR_DIGITS[day - 21]
        else -> "$day"
    }

    // 农历日期文本, 如 "农历十月初三" / "农历闰四月十五"
    fun lunarDateText(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        return runCatching {
            val lunar = lunarOf(today, zone)
            val leap = if (lunar.isLeapMonth) "闰" else ""
            "农历$leap${LUNAR_MONTH_NAMES[lunar.month - 1]}${lunarDayName(lunar.day)}"
        }.getOrDefault("")
    }

    // 当天节日列表 (公历节日 + 规则节日 + 农历节日 + 除夕), 无节日返回空列表
    fun festivalsOn(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val result = mutableListOf<String>()
        SOLAR_FESTIVALS[today.monthValue to today.dayOfMonth]?.let { result.add(it) }
        // 规则节日
        if (today == nthWeekdayOfMonth(today.year, 5, DayOfWeek.SUNDAY, 2)) result.add("母亲节")
        if (today == nthWeekdayOfMonth(today.year, 6, DayOfWeek.SUNDAY, 3)) result.add("父亲节")
        if (today == nthWeekdayOfMonth(today.year, 11, DayOfWeek.THURSDAY, 4)) result.add("感恩节")
        // 农历节日 (闰月不过节) 与除夕
        runCatching {
            val lunar = lunarOf(today, zone)
            if (!lunar.isLeapMonth) {
                LUNAR_FESTIVALS[lunar.month to lunar.day]?.let { result.add(it) }
                // 除夕 = 腊月最后一天, 即明天是正月初一
                val tomorrow = lunarOf(today.plusDays(1), zone)
                if (!tomorrow.isLeapMonth && tomorrow.month == 1 && tomorrow.day == 1) {
                    result.add("除夕")
                }
            }
        }
        return result
    }

    // 某月第 n 个指定星期几 (如 5 月第 2 个周日 = 母亲节)
    private fun nthWeekdayOfMonth(
        year: Int,
        month: Int,
        dayOfWeek: DayOfWeek,
        n: Int,
    ): LocalDate {
        val first = LocalDate.of(year, month, 1)
        val offset = (dayOfWeek.value - first.dayOfWeek.value + 7) % 7
        return first.plusDays(offset.toLong() + (n - 1) * 7L)
    }

    // 某节气在某年的公历日期 (寿星通式, 21 世纪近似)
    private fun solarTermDate(year: Int, termIndex: Int): LocalDate {
        val month = termIndex / 2 + 1
        val y = year % 100
        // 1、2 月的节气闰年修正取 (Y-1)/4, 其余取 Y/4
        val leapFix = if (termIndex <= 3) (y - 1) / 4 else y / 4
        val day = (y * 0.2422 + SOLAR_TERM_C[termIndex]).toInt() - leapFix
        return LocalDate.of(year, month, day.coerceIn(1, 31))
    }

    // 当天所处节气名 (最近一个已到的节气), 异常时返回 null
    fun solarTermOn(today: LocalDate): String? {
        return runCatching {
            var best: Pair<LocalDate, String>? = null
            // 跨年兜底: 1 月上旬的当前节气是去年的冬至/大雪
            for (year in today.year - 1..today.year + 1) {
                for (i in SOLAR_TERM_NAMES.indices) {
                    val date = solarTermDate(year, i)
                    if (!date.isAfter(today)) {
                        val current = best
                        if (current == null || date.isAfter(current.first)) {
                            best = date to SOLAR_TERM_NAMES[i]
                        }
                    }
                }
            }
            best?.second
        }.getOrNull()
    }

    // 在一起第 N 天 (相识当天算第 1 天), 未设置或起始日在未来时返回 null
    fun daysTogether(startDate: String?, today: LocalDate = LocalDate.now()): Int? {
        if (startDate.isNullOrBlank()) return null
        val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return null
        if (start.isAfter(today)) return null
        return ChronoUnit.DAYS.between(start, today).toInt() + 1
    }

    /**
     * 距离下一个生日还有几天
     *
     * 返回 (距离天数, 是否就是今天); 优先使用农历生日, 其次阳历生日, 都未设置返回 null。
     */
    fun daysUntilBirthday(display: DisplaySetting, today: LocalDate): Pair<Int, Boolean>? {
        val zone = ZoneId.systemDefault()
        // 农历生日优先: 逐年换算成公历日期再取最近的一个
        val lunarMonth = display.anniversaryLunarBirthdayMonth
        val lunarDay = display.anniversaryLunarBirthdayDay
        if (lunarMonth in 1..12 && lunarDay in 1..30) {
            val next = (today.year..today.year + 2)
                .mapNotNull { solarOfLunar(it, lunarMonth, lunarDay, zone) }
                .filter { !it.isBefore(today) }
                .minOrNull()
            if (next != null) {
                val days = ChronoUnit.DAYS.between(today, next).toInt()
                return days to (days == 0)
            }
            return null
        }
        // 阳历生日
        val solarMonth = display.anniversarySolarBirthdayMonth
        val solarDay = display.anniversarySolarBirthdayDay
        if (solarMonth in 1..12 && solarDay in 1..31) {
            val next = (today.year..today.year + 2)
                .mapNotNull { year ->
                    val month = java.time.YearMonth.of(year, solarMonth)
                    month.atDay(solarDay.coerceAtMost(month.lengthOfMonth()))
                }
                .filter { !it.isBefore(today) }
                .minOrNull()
            if (next != null) {
                val days = ChronoUnit.DAYS.between(today, next).toInt()
                return days to (days == 0)
            }
        }
        return null
    }

    // 生成注入给 AI 的日期上下文卡片, 没有任何可注入内容时返回 null
    fun buildDateCard(display: DisplaySetting, today: LocalDate): String? {
        val zone = ZoneId.systemDefault()
        val lines = mutableListOf<String>()

        // 第一行: 公历日期 + 星期 + 农历
        val weekCn = WEEK_NAMES[today.dayOfWeek.value - 1]
        val lunarText = lunarDateText(today, zone)
        lines.add(
            buildString {
                append("今天是${today.year}年${today.monthValue}月${today.dayOfMonth}日 星期$weekCn")
                if (lunarText.isNotEmpty()) {
                    append("，$lunarText")
                }
            }
        )

        // 节日行
        val festivals = festivalsOn(today, zone)
        if (festivals.isNotEmpty()) {
            lines.add("今日节日：" + festivals.joinToString("、"))
        }

        // 节气行
        solarTermOn(today)?.let { lines.add("今日节气：$it") }

        // 在一起天数行
        daysTogether(display.anniversaryStartDate, today)?.let {
            lines.add("我们在一起第${it}天")
        }

        // 生日行
        daysUntilBirthday(display, today)?.let { (days, isToday) ->
            if (isToday) {
                lines.add("今天是TA的生日")
            } else {
                lines.add("距离TA的生日还有${days}天")
            }
        }

        if (lines.isEmpty()) return null
        return "<date_context>\n${lines.joinToString("\n")}\n</date_context>"
    }
}
