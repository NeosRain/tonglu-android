package cn.tonglu.app.data

import java.util.Calendar

/**
 * 中国法定节假日（国务院办公厅年度放假安排公告）。
 * 新一年安排公布后，在 TABLE 里加一行即可；查询接口不变。
 */
object Holidays {

    private const val DAY_MS = 24L * 3600 * 1000

    data class HolidaySpan(val name: String, val start: Long, val end: Long) {
        /** 「9月25日-9月27日」 */
        fun rangeLabel(): String {
            val startLabel = labelOf(start)
            val endLabel = labelOf(end)
            val sameMonth = monthOf(start) == monthOf(end)
            return if (sameMonth) "${startLabel.substringBefore("月")}月${startLabel.substringAfter("月")}-$endLabel"
            else "$startLabel-$endLabel"
        }
    }

    /**
     * 一条假期原始记录。刻意用带字段名的类型，不用 Triple——
     * Triple 三个字段都是 String，靠位置解构时极易把「名字」和「日期」搞混
     * （本项目就因此崩过一次：parseDate("元旦") → NumberFormatException）。
     */
    private data class RawHoliday(val name: String, val start: String, val end: String)

    // 2026 年（国办发明电〔2025〕7号）：
    //  元旦 1.1-1.3（1.4 周日上班）｜春节 2.15-2.23（2.14、2.28 上班）｜清明 4.4-4.6
    //  劳动节 5.1-5.5（5.9 上班）｜端午 6.19-6.21｜中秋 9.25-9.27｜国庆 10.1-10.7（9.20、10.10 上班）
    // 2025 年备查（跨年学期用）
    private val TABLE: List<RawHoliday> = listOf(
        RawHoliday("元旦", "2025-01-01", "2025-01-01"),
        RawHoliday("春节", "2025-01-28", "2025-02-04"),
        RawHoliday("清明节", "2025-04-04", "2025-04-06"),
        RawHoliday("劳动节", "2025-05-01", "2025-05-05"),
        RawHoliday("端午节", "2025-05-31", "2025-06-02"),
        RawHoliday("中秋·国庆", "2025-10-01", "2025-10-08"),
        RawHoliday("元旦", "2026-01-01", "2026-01-03"),
        RawHoliday("春节", "2026-02-15", "2026-02-23"),
        RawHoliday("清明节", "2026-04-04", "2026-04-06"),
        RawHoliday("劳动节", "2026-05-01", "2026-05-05"),
        RawHoliday("端午节", "2026-06-19", "2026-06-21"),
        RawHoliday("中秋节", "2026-09-25", "2026-09-27"),
        RawHoliday("国庆节", "2026-10-01", "2026-10-07"),
    )

    /** 调休上班日（周末但要上班） */
    private val MAKEUP_WORKDAYS: Set<String> = setOf(
        "2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10",
        "2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11",
    )

    /** 解析 "yyyy-MM-dd"；格式不对返回 null（宁可少一个假期，也不能整页崩） */
    private fun parseDate(text: String): Long? {
        val parts = text.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis
    }

    private fun dateKey(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return "%04d-%02d-%02d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun monthOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.MONTH) + 1

    private fun labelOf(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return "${calendar.get(Calendar.MONTH) + 1}月${calendar.get(Calendar.DAY_OF_MONTH)}日"
    }

    /** 解析失败的记录会被跳过，静态初始化永远不会抛异常 */
    private val SPANS: List<HolidaySpan> = TABLE.mapNotNull { raw ->
        val start = parseDate(raw.start) ?: return@mapNotNull null
        val end = parseDate(raw.end) ?: return@mapNotNull null
        if (end < start) return@mapNotNull null
        HolidaySpan(raw.name, start, end + DAY_MS - 1)
    }

    /** 表里一共有多少条假期（用于自检/调试） */
    val count: Int get() = SPANS.size

    /** 给定时刻（按自然日）落在哪个假期里 */
    fun holidayAt(millis: Long): HolidaySpan? {
        val day = ScheduleText.startOfDay(millis)
        return SPANS.firstOrNull { day in it.start..it.end }
    }

    fun isHoliday(millis: Long): Boolean = holidayAt(millis) != null

    /** 调休上班日（该周末正常上课/上班） */
    fun isMakeupWorkday(millis: Long): Boolean = MAKEUP_WORKDAYS.contains(dateKey(millis))

    /** 当前所处或下一个假期 */
    fun currentOrNext(now: Long = System.currentTimeMillis()): HolidaySpan? {
        val today = ScheduleText.startOfDay(now)
        return SPANS.firstOrNull { today in it.start..it.end }
            ?: SPANS.firstOrNull { it.start > today }
    }

    /** 「距下一个假期还有 N 天」；正在假期中返回 null */
    fun daysToNextHoliday(now: Long = System.currentTimeMillis()): Pair<HolidaySpan, Int>? {
        val today = ScheduleText.startOfDay(now)
        val next = SPANS.firstOrNull { it.start > today } ?: return null
        return next to (((next.start - today) / DAY_MS).toInt())
    }
}
