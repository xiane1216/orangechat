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
import java.time.MonthDay
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

    /**
     * ICU ChineseCalendar 的纪元偏移: 公历年 = EXTENDED_YEAR - 2637。
     *
     * 注意不要用 2697: 那是"黄帝纪年"的约定, ICU 并不采用。曾经错用 2697 导致
     * 公历年份被当成农历年换算时整体偏移 60 年, "距离生日"算出两万多天。
     */
    private const val ICU_CHINESE_EPOCH_OFFSET = 2637

    /** 节日来源: 国内传统/法定节日 或 国外传入节日 */
    enum class FestivalOrigin(val label: String) {
        CN("国内"),
        GLOBAL("国外"),
    }

    /** 节日 (名称 + 国内/国外标注) */
    data class Festival(
        val name: String,
        val origin: FestivalOrigin,
    )

    // 公历节日表 (月-日 -> 节日)
    private val SOLAR_FESTIVALS: Map<Pair<Int, Int>, Festival> = mapOf(
        // 国内
        (1 to 1) to Festival("元旦", FestivalOrigin.CN),
        (3 to 8) to Festival("妇女节", FestivalOrigin.CN),
        (3 to 12) to Festival("植树节", FestivalOrigin.CN),
        (5 to 1) to Festival("劳动节", FestivalOrigin.CN),
        (5 to 4) to Festival("青年节", FestivalOrigin.CN),
        (6 to 1) to Festival("儿童节", FestivalOrigin.CN),
        (7 to 1) to Festival("建党节", FestivalOrigin.CN),
        (8 to 1) to Festival("建军节", FestivalOrigin.CN),
        (9 to 10) to Festival("教师节", FestivalOrigin.CN),
        (10 to 1) to Festival("国庆节", FestivalOrigin.CN),
        // 国外
        (2 to 14) to Festival("情人节", FestivalOrigin.GLOBAL),
        (3 to 14) to Festival("白色情人节", FestivalOrigin.GLOBAL),
        (4 to 1) to Festival("愚人节", FestivalOrigin.GLOBAL),
        (10 to 31) to Festival("万圣节", FestivalOrigin.GLOBAL),
        (12 to 24) to Festival("平安夜", FestivalOrigin.GLOBAL),
        (12 to 25) to Festival("圣诞节", FestivalOrigin.GLOBAL),
    )

    // 农历节日表 (农历月-日 -> 节日), 闰月不过节, 均为国内节日
    private val LUNAR_FESTIVALS: Map<Pair<Int, Int>, Festival> = mapOf(
        (1 to 1) to Festival("春节", FestivalOrigin.CN),
        (1 to 15) to Festival("元宵节", FestivalOrigin.CN),
        (2 to 2) to Festival("龙头节", FestivalOrigin.CN),
        (5 to 5) to Festival("端午节", FestivalOrigin.CN),
        (7 to 7) to Festival("七夕", FestivalOrigin.CN),
        (7 to 15) to Festival("中元节", FestivalOrigin.CN),
        (8 to 15) to Festival("中秋节", FestivalOrigin.CN),
        (9 to 9) to Festival("重阳节", FestivalOrigin.CN),
        (12 to 8) to Festival("腊八节", FestivalOrigin.CN),
    )

    // 规则节日 (某月第 n 个星期几), 均为国外节日
    private data class RuleFestival(
        val name: String,
        val origin: FestivalOrigin,
        val month: Int,
        val dayOfWeek: DayOfWeek,
        val nth: Int,
    )

    private val RULE_FESTIVALS = listOf(
        RuleFestival("母亲节", FestivalOrigin.GLOBAL, 5, DayOfWeek.SUNDAY, 2),
        RuleFestival("父亲节", FestivalOrigin.GLOBAL, 6, DayOfWeek.SUNDAY, 3),
        RuleFestival("感恩节", FestivalOrigin.GLOBAL, 11, DayOfWeek.THURSDAY, 4),
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
            year = cal.get(Calendar.EXTENDED_YEAR) - ICU_CHINESE_EPOCH_OFFSET,
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
            cal.set(Calendar.EXTENDED_YEAR, lunarYear + ICU_CHINESE_EPOCH_OFFSET)
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

    // 当天节日列表 (公历节日 + 规则节日 + 农历节日 + 除夕, 每条带国内/国外标注), 无节日返回空列表
    fun festivalsOn(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Festival> {
        val result = mutableListOf<Festival>()
        SOLAR_FESTIVALS[today.monthValue to today.dayOfMonth]?.let { result.add(it) }
        // 规则节日 (如 5 月第 2 个周日 = 母亲节)
        RULE_FESTIVALS.forEach { rule ->
            if (today == nthWeekdayOfMonth(today.year, rule.month, rule.dayOfWeek, rule.nth)) {
                result.add(Festival(rule.name, rule.origin))
            }
        }
        // 农历节日 (闰月不过节) 与除夕
        runCatching {
            val lunar = lunarOf(today, zone)
            if (!lunar.isLeapMonth) {
                LUNAR_FESTIVALS[lunar.month to lunar.day]?.let { result.add(it) }
                // 除夕 = 腊月最后一天, 即明天是正月初一 (跨年边界: 腊月总是落在公历次年 1 月下旬前后)
                val tomorrow = lunarOf(today.plusDays(1), zone)
                if (!tomorrow.isLeapMonth && tomorrow.month == 1 && tomorrow.day == 1) {
                    result.add(Festival("除夕", FestivalOrigin.CN))
                }
            }
        }
        return result
    }

    // 设置页预览用: 代表性节日清单 (名称, 来源, 日期文案), 顺序即展示顺序
    fun festivalSamples(): List<Triple<String, FestivalOrigin, String>> = listOf(
        Triple("春节", FestivalOrigin.CN, "正月初一"),
        Triple("七夕", FestivalOrigin.CN, "七月初七"),
        Triple("中秋", FestivalOrigin.CN, "八月十五"),
        Triple("除夕", FestivalOrigin.CN, "腊月最后一天"),
        Triple("国庆节", FestivalOrigin.CN, "10月1日"),
        Triple("情人节", FestivalOrigin.GLOBAL, "2月14日"),
        Triple("白色情人节", FestivalOrigin.GLOBAL, "3月14日"),
        Triple("母亲节", FestivalOrigin.GLOBAL, "5月第2个周日"),
        Triple("感恩节", FestivalOrigin.GLOBAL, "11月第4个周四"),
        Triple("圣诞节", FestivalOrigin.GLOBAL, "12月25日"),
    )

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

    // 星期中文名, 如 "星期二"
    fun weekName(date: LocalDate): String = "星期" + WEEK_NAMES[date.dayOfWeek.value - 1]

    /**
     * 距离下一个生日还有几天, 返回 (距离天数, 是否就是今天); 优先农历生日, 其次阳历生日, 都未设置返回 null。
     *
     * 农历生日只有月/日没有年份。农历年与公历年的数字基本一一对应 (仅岁首落在公历 1 月下旬至 2 月中旬),
     * 因此把公历年份数值近似当作农历年份, 从今年开始逐年把"农历 m 月 d 日"换算成公历日期,
     * 取今天及以后最近的一个: 今年那次已经过了自然落到明年, 今天正好是生日则返回 0。
     * 闰月一律按平月算 (solarOfLunar 内部固定 IS_LEAP_MONTH = 0), 腊月三十缺失的年份自动取腊月廿九。
     */
    fun daysUntilBirthday(display: DisplaySetting, today: LocalDate): Pair<Int, Boolean>? {
        val zone = ZoneId.systemDefault()
        // 农历生日优先
        val lunarMonth = display.anniversaryLunarBirthdayMonth
        val lunarDay = display.anniversaryLunarBirthdayDay
        if (lunarMonth in 1..12 && lunarDay in 1..30) {
            // 农历年 G 的"某月某日"最晚落在公历 G+1 年 1 月下旬, 多查一年 (G+2) 兜底
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
        // 阳历生日: 用 MonthDay 表达"无年份的月日", 2月29日在非闰年自动顺延到下一个闰年
        val solarMonth = display.anniversarySolarBirthdayMonth
        val solarDay = display.anniversarySolarBirthdayDay
        if (solarMonth in 1..12 && solarDay in 1..31) {
            val monthDay = MonthDay.of(solarMonth, solarDay)
            val next = (today.year..today.year + 2)
                .mapNotNull { year -> runCatching { monthDay.atYear(year) }.getOrNull() }
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

        // 节日行: 每条都带"国内/国外"前缀, 多个用、分隔
        val festivals = festivalsOn(today, zone)
        if (festivals.isNotEmpty()) {
            lines.add("今日节日：" + festivals.joinToString("、") { "${it.origin.label} ${it.name}" })
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
                lines.add("今天是宝宝的生日")
            } else {
                lines.add("距离宝宝的生日还有${days}天")
            }
        }

        if (lines.isEmpty()) return null
        // 末尾附带使用说明: 不要求死记硬背式汇报, 但允许自然联想时提及
        lines.add("（以上是系统附带的背景信息，不是她说的话。不用刻意汇报这些数字；聊天中自然联想到时可以随意提起，比如话题相关、节日快到、她生日临近想问问她想要什么，都很自然。只需要避免毫无关联地突然报这些信息。）")
        return "<date_context>\n${lines.joinToString("\n")}\n</date_context>"
    }
}
