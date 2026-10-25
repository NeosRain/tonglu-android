package cn.tonglu.app.data.exam

import java.util.Calendar
import java.util.TimeZone

/**
 * 规则日期库（`docs/plan/54` M5 的 L1 层 + 附录 D）。
 *
 * **为什么要有它**：有些国家考试的**规律是稳定的**（CET = 每年 6 月/12 月第二个周六），
 * 这类**能算出来，就不必联网抓**。内置表（L2）和服务端抓取（L3）都可能没有当年数据，
 * 但规则永远能给出一个「按往年规律推算」的日期 —— 这是地基，不是装饰。
 *
 * 只支持三类表达式，**别做成通用规则引擎**（做过头只会变成没人敢改的黑盒）：
 *  · [Fixed] 固定月日（高考 6-7）
 *  · [NthWeekday] 第 N 个星期几（CET 6 月第二个周六）
 *  · [LastWeekend] 某月最后一个周末（NCRE 3 月）
 */
object ExamRules {

    data class Fixed(val month: Int, val day: Int)

    data class NthWeekday(
        val month: Int,
        /** `Calendar.SATURDAY` 这一类的常量（1=周日 … 7=周六） */
        val weekday: Int,
        /** 第几个，从 1 开始 */
        val nth: Int,
    )

    /** 某月最后一个周末的**周六** */
    data class LastWeekend(val month: Int)

    private fun utcCalendar(year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }

    private fun format(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(year, month, day)
    }

    /** 该月有多少天（闰年 2 月要靠 Calendar 自己算，别手写 28/29） */
    private fun daysInMonth(year: Int, month: Int): Int =
        utcCalendar(year, month, 1).getActualMaximum(Calendar.DAY_OF_MONTH)

    fun resolve(rule: Any, year: Int): String? = when (rule) {
        is Fixed -> {
            if (rule.month !in 1..12) null
            else if (rule.day !in 1..daysInMonth(year, rule.month)) null
            else format(utcCalendar(year, rule.month, rule.day))
        }

        is NthWeekday -> {
            if (rule.month !in 1..12 || rule.nth < 1) null
            else {
                val first = utcCalendar(year, rule.month, 1)
                val firstDow = first.get(Calendar.DAY_OF_WEEK)
                var delta = rule.weekday - firstDow
                if (delta < 0) delta += 7
                val day = 1 + delta + 7 * (rule.nth - 1)
                if (day > daysInMonth(year, rule.month)) null else format(utcCalendar(year, rule.month, day))
            }
        }

        is LastWeekend -> {
            if (rule.month !in 1..12) null
            else {
                // 从月末往回找第一个周六
                var day = daysInMonth(year, rule.month)
                while (day > 0) {
                    val calendar = utcCalendar(year, rule.month, day)
                    if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) return format(calendar)
                    day -= 1
                }
                null
            }
        }

        else -> null
    }

    /**
     * 按规则算出**不早于今天**的那一场。
     *
     * 今年这场已经过了就自动滚到下一年（与 `dsh-exam-countdown` 的做法一致：
     * 用户看到的是「下一场」，不是「刚考完那场」）。
     * 最多往后滚 3 年，滚不出来返回 null（说明规则写错了，宁可不给日期）。
     */
    fun nextFrom(rule: Any, todayEpochDay: Long, fromYear: Int): String? {
        for (offset in 0..3) {
            val date = resolve(rule, fromYear + offset) ?: continue
            val epochDay = ExamCountdown.dateToEpochDay(date) ?: continue
            if (epochDay >= todayEpochDay) return date
        }
        return null
    }
}
