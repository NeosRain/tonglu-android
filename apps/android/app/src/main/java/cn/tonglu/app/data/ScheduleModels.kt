package cn.tonglu.app.data

import java.util.Calendar

/**
 * 解析出来的课程（教务系统一行 = 一门课的一次上课）。
 * weeks 为空时用 startWeek..endWeek 表示连续周次。
 */
data class ParsedCourse(
    val name: String,
    val teacher: String?,
    val location: String?,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startWeek: Int = 1,
    val endWeek: Int = 30,
    val weeks: List<Int> = emptyList(),
    val color: String = "blue",
    val source: String = "jwapp",
) {
    /** 该课程在第 [week] 周是否上课 */
    fun activeInWeek(week: Int): Boolean =
        if (weeks.isNotEmpty()) weeks.contains(week) else week in startWeek..endWeek

    fun weekLabel(): String {
        if (weeks.isNotEmpty()) {
            val sorted = weeks.sorted()
            val odd = sorted.all { it % 2 == 1 }
            val even = sorted.all { it % 2 == 0 }
            return when {
                sorted == (sorted.first()..sorted.last()).toList() && odd -> "${sorted.first()}-${sorted.last()}周(单)"
                sorted == (sorted.first()..sorted.last()).toList() && even -> "${sorted.first()}-${sorted.last()}周(双)"
                sorted == (sorted.first()..sorted.last()).toList() -> "${sorted.first()}-${sorted.last()}周"
                else -> sorted.joinToString(",") + "周"
            }
        }
        return "${startWeek}-${endWeek}周"
    }

    fun timeText(): String = PeriodTable.timeText(startPeriod, endPeriod)

    fun toRequest(): CreateCourseRequest = CreateCourseRequest(
        name = name,
        teacher = teacher,
        location = location,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        startWeek = startWeek,
        endWeek = endWeek,
        weeks = weeks,
        color = color,
        source = source,
    )
}

/** 一节课的作息时间（教务系统的"节次时间表"） */
data class PeriodSlot(val period: Int, val start: String, val end: String)

object PeriodTable {
    /**
     * 辽东学院默认作息（教务系统 getMySectionList.do 原样返回，10 节制）。
     * 用户可在「作息时间表」里改成自己的上下课时间，DEFAULT 只作默认值与重置目标。
     */
    val DEFAULT: List<PeriodSlot> = listOf(
        PeriodSlot(1, "08:00", "08:45"),
        PeriodSlot(2, "08:55", "09:40"),
        PeriodSlot(3, "10:10", "10:55"),
        PeriodSlot(4, "11:05", "11:50"),
        PeriodSlot(5, "13:20", "14:05"),
        PeriodSlot(6, "14:15", "15:00"),
        PeriodSlot(7, "15:20", "16:05"),
        PeriodSlot(8, "16:15", "17:00"),
        PeriodSlot(9, "17:30", "18:15"),
        PeriodSlot(10, "18:25", "19:10"),
    )

    /** 用户自定义作息；null = 用默认。由 ScheduleStore 在启动时灌进来 */
    private var customSlots: List<PeriodSlot>? = null

    /** 当前生效的作息表 */
    fun slots(): List<PeriodSlot> = customSlots ?: DEFAULT

    fun isCustom(): Boolean = customSlots != null

    fun setCustom(slots: List<PeriodSlot>?) {
        customSlots = slots
            ?.filter { it.period > 0 && isValidHhmm(it.start) && isValidHhmm(it.end) }
            ?.sortedBy { it.period }
            ?.takeIf { it.isNotEmpty() }
    }

    fun slot(period: Int): PeriodSlot? = slots().firstOrNull { it.period == period }

    /** 作息表里最大的节次（课表网格用它决定画多少行） */
    fun maxPeriod(): Int = slots().maxOfOrNull { it.period } ?: DEFAULT.size

    fun timeText(startPeriod: Int, endPeriod: Int): String {
        val start = slot(startPeriod)?.start ?: return ""
        val end = slot(endPeriod)?.end ?: return ""
        return "$start-$end"
    }

    /** 当前时间是否已经过了第 [period] 节 */
    fun hasPassed(period: Int, nowMinutes: Int): Boolean {
        val end = slot(period)?.end ?: return false
        return parseMinutes(end) < nowMinutes
    }

    fun parseMinutes(hhmm: String): Int {
        val parts = hhmm.split(":")
        if (parts.size != 2) return 0
        return (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
    }

    /** "08:00" 格式校验（HH:mm，00:00-23:59） */
    fun isValidHhmm(text: String): Boolean {
        val parts = text.trim().split(":")
        if (parts.size != 2 || parts[0].length !in 1..2 || parts[1].length != 2) return false
        val hour = parts[0].toIntOrNull() ?: return false
        val minute = parts[1].toIntOrNull() ?: return false
        return hour in 0..23 && minute in 0..59
    }

    fun normalizeHhmm(text: String): String? {
        val trimmed = text.trim()
        return if (isValidHhmm(trimmed)) {
            val parts = trimmed.split(":")
            "%02d:%02d".format(parts[0].toInt(), parts[1].toInt())
        } else null
    }
}

/**
 * 周次表达：每周 / 单周 / 双周 / 自定义。
 * 单双周和跳周都统一落到 `weeks: List<Int>`（后端与展示都已支持）。
 */
object WeekSpec {
    const val EVERY = "EVERY"
    const val ODD = "ODD"
    const val EVEN = "EVEN"
    const val CUSTOM = "CUSTOM"

    val allModes = listOf(EVERY, ODD, EVEN, CUSTOM)

    fun modeLabel(mode: String): String = when (mode) {
        ODD -> "单周"
        EVEN -> "双周"
        CUSTOM -> "自定义"
        else -> "每周"
    }

    /** 按模式算出具体周次；每周返回空列表（让后端用 startWeek..endWeek 表示） */
    fun build(mode: String, startWeek: Int, endWeek: Int, customText: String = ""): List<Int> {
        val from = minOf(startWeek, endWeek).coerceAtLeast(1)
        val to = maxOf(startWeek, endWeek).coerceAtLeast(from)
        return when (mode) {
            ODD -> (from..to).filter { it % 2 == 1 }
            EVEN -> (from..to).filter { it % 2 == 0 }
            CUSTOM -> parse(customText)
            else -> emptyList()
        }
    }

    /** 解析 "1-8,10,12-16"（容忍中文逗号/顿号/~） → 去重升序周次 */
    fun parse(text: String): List<Int> {
        val out = mutableListOf<Int>()
        text.split(',', '，', '、', ';', '；', ' ', '\n', '\t').forEach { piece ->
            val token = piece.trim()
            if (token.isEmpty()) return@forEach
            val range = token.split('-', '~', '～', '－').filter { it.isNotBlank() }
            if (range.size >= 2) {
                val from = range[0].trim().toIntOrNull()
                val to = range[1].trim().toIntOrNull()
                if (from != null && to != null) {
                    for (week in minOf(from, to)..maxOf(from, to)) if (week in 1..40) out += week
                }
            } else {
                token.toIntOrNull()?.let { if (it in 1..40) out += it }
            }
        }
        return out.distinct().sorted()
    }

    /** 反向压缩成文案：1-8,10,12-16 周 */
    fun describe(weeks: List<Int>): String {
        if (weeks.isEmpty()) return "未选择周次"
        val sorted = weeks.distinct().sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (i in 1 until sorted.size) {
            val week = sorted[i]
            if (week == prev + 1) {
                prev = week
            } else {
                parts += if (start == prev) "$start" else "$start-$prev"
                start = week
                prev = week
            }
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        return parts.joinToString(",") + " 周"
    }
}

object ScheduleText {
    private val weekdayNames = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    fun weekdayLabel(weekday: Int): String = weekdayNames.getOrElse(weekday - 1) { "星期$weekday" }

    fun shortWeekday(weekday: Int): String = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        .getOrElse(weekday - 1) { "周$weekday" }

    fun todayWeekday(): Int = ((Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

    /**
     * 今天在**本地时区**的第几天（1970 起算）。变了就是跨天了。
     *
     * 不用 java.time：minSdk 24，没有 coreLibraryDesugaring，`LocalDate` 在低版本上会炸。
     * 直接拿 `timeInMillis / 86400000` 是 UTC 的日界 —— 东八区会"提前 8 小时跨天"，
     * 必须把时区偏移加回去。
     */
    fun todayEpochDay(millis: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        val offset = calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)
        return (millis + offset) / 86_400_000L
    }

    /** 给定时刻是星期几（周一=1 … 周日=7） */
    fun weekdayOf(millis: Long): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    }

    /** 给定时刻所在自然日的 00:00 */
    fun startOfDay(millis: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun timeText(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return "%02d:%02d".format(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
    }

    fun nowMinutes(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    /**
     * 第几教学周。termStart 为空时返回 null（课表按"全部周次"展示）。
     * termStart 形如 2026-09-01，且约定那一周为第 1 周。
     */
    fun weekOf(termStart: String?, now: Long = System.currentTimeMillis()): Int? {
        if (termStart.isNullOrBlank()) return null
        val start = runCatching {
            val parts = termStart.split("-")
            val calendar = Calendar.getInstance().apply {
                clear()
                set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            }
            calendar.timeInMillis
        }.getOrNull() ?: return null
        val monday = weekStartMillis(start)
        if (now < monday) return null
        val days = (startOfDay(now) - monday) / (24L * 3600 * 1000)
        return (days / 7).toInt() + 1
    }

    /** 星期一为一周起点，返回该周星期一的 00:00 */
    fun weekStartMillis(base: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = base
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val delta = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
        calendar.add(Calendar.DAY_OF_MONTH, -delta)
        return calendar.timeInMillis
    }

    fun dateLabel(weekday: Int, base: Long = System.currentTimeMillis()): String {
        val millis = weekStartMillis(base) + (weekday - 1) * 24L * 3600 * 1000
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return "${calendar.get(Calendar.MONTH) + 1}月${calendar.get(Calendar.DAY_OF_MONTH)}日"
    }

    /** 任意时刻的 “M月D日” 标签（列头按所选周显示日期用） */
    fun dateLabelAt(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return "${calendar.get(Calendar.MONTH) + 1}月${calendar.get(Calendar.DAY_OF_MONTH)}日"
    }

    /**
     * 学期第 [week] 周的周一 00:00（与 weekOf 的周划分保持一致：
     * termStart 所在周为第 1 周）。termStart 缺失或无法解析时返回 null。
     */
    fun weekStartOfTerm(termStart: String?, week: Int): Long? {
        if (termStart.isNullOrBlank() || week < 1) return null
        val parts = termStart.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        val start = runCatching {
            Calendar.getInstance().apply {
                clear()
                set(year, month - 1, day)
            }.timeInMillis
        }.getOrNull() ?: return null
        return weekStartMillis(start) + (week - 1) * 7L * 24 * 3600 * 1000
    }

    fun todayLabel(): String {
        val calendar = Calendar.getInstance()
        val names = listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        return "${calendar.get(Calendar.MONTH) + 1}月${calendar.get(Calendar.DAY_OF_MONTH)}日 ${names[calendar.get(Calendar.DAY_OF_WEEK) - 1]}"
    }
}
