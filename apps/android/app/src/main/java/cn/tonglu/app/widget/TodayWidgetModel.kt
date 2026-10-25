package cn.tonglu.app.widget

import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.Holidays
import cn.tonglu.app.data.PeriodTable
import cn.tonglu.app.data.ScheduleBoard
import cn.tonglu.app.data.ScheduleText

/**
 * 「今日课表」小部件的取数逻辑 —— **纯函数，不碰 Context / Compose / Android 时间**。
 *
 * 为什么单独抽出来（而不是写在小部件的 `provideGlance` 里）：
 * 小部件是**没有 UI 可点、没有 dump 可读**的表面 —— 走查只能靠肉眼截图，
 * 一旦数据显示错了（课次算错、跨天没翻、假期还报「有课」）非常难定位。
 * 所以把「算要显示什么」和「怎么画」彻底分开，前者可以在 JVM 单测里
 * 用固定时间戳穷举验证（含对照组）。**改这里一定要跑 [TodayWidgetModelTest]。**
 *
 * ## 三种状态（互斥）
 *
 * 1. [State.Courses] —— 今天有课，列出全部（按节次排序）
 * 2. [State.Rest]    —— 今天没课，但未来 7 天内有（显示最近一次 + 「x 天后」）
 * 3. [State.Empty]   —— 压根没有课表数据（没导入/被清空），提示去导入
 *
 * ## 两条硬规则（都是线上踩过的）
 *
 * - 🔴 **国定假期直接跳过**：复用 [ScheduleBoard.nextInDays] 的逻辑 ——
 *   放假期间报「明天有课」是错的（2026-10-05 用户报告过同一个 bug，见 ScheduleStore 注释）。
 * - 🔴 **跨天判定必须走 [ScheduleText.todayEpochDay]**，它已经处理了东八区时区偏移。
 *   直接 `millis / 86400000` 会「提前 8 小时跨天」（minSdk 24 没有 java.time，别引 `LocalDate`）。
 */
object TodayWidgetModel {

    /** 小部件最多列几节课 —— 再多就溢出了，超出部分显示「还有 N 节」。 */
    const val MAX_ROWS = 4

    /** 一节课要显示的一行 */
    data class Row(
        /** 第几节 ~ 第几节，如 "1-2"；单节就是 "3" */
        val periods: String,
        /** "08:00-09:40" */
        val time: String,
        val name: String,
        /** 教室，可能为空 */
        val location: String?,
        /** 教师，可能为空 */
        val teacher: String?,
    )

    sealed interface State {
        /** 今天有课 */
        data class Courses(
            val rows: List<Row>,
            /** 今天总节数（用于「还有 N 节」） */
            val total: Int,
            /** 正在上的那一节（没在上课就是 null）—— 用来给标题加「正在上课」 */
            val ongoing: Row?,
        ) : State

        /** 今天没课，最近的一次在未来 [gapDays] 天 */
        data class Rest(val next: Row, val gapDays: Int, val weekdayLabel: String) : State

        /** 没有课表数据 */
        data object Empty : State

        /** 有数据、今天没课、未来 7 天也没课（寒暑假/学期结束） */
        data object NoUpcoming : State
    }

    /**
     * 由课表 + 时间算出小部件要显示什么。
     *
     * @param courses 全部课程（不筛选）
     * @param week    当前教学周，null = 未设置开学日期（此时不做周次过滤）
     * @param nowMillis 当前时刻，**测试必须传固定值**
     */
    fun build(courses: List<CourseDto>, week: Int?, nowMillis: Long = System.currentTimeMillis()): State {
        if (courses.isEmpty()) return State.Empty

        val weekday = ScheduleText.weekdayOf(nowMillis)
        val nowMinutes = minutesOfDay(nowMillis)
        val today = ScheduleBoard.ofDay(courses, weekday, week)

        if (today.isNotEmpty()) {
            val all = today.map { it.toRow() }
            val ongoingCourse = ScheduleBoard.current(courses, weekday, week, nowMinutes)
            // 「正在上」按课程身份找回对应行（CourseDto.id 唯一）
            val ongoing = ongoingCourse?.let { c -> all.getOrNull(today.indexOfFirst { it.id == c.id }) }
            return State.Courses(rows = all.take(MAX_ROWS), total = all.size, ongoing = ongoing)
        }

        // 今天没课 → 找未来 7 天内最近的一次（内部已跳过国定假期）
        val upcoming = ScheduleBoard.nextInDays(courses, weekday, week, nowMinutes, ScheduleText.startOfDay(nowMillis))
            ?: return State.NoUpcoming
        val (course, gapMinutes) = upcoming
        // 🔴 gapMinutes 是「距上课还有多少分钟」，要按**就近取整**成天数 ——
        // 整除是错的：周一九点看周三早八的课，实际差 47 小时，整除得 1 天（显示「1 天后」）。
        // 这里必须与 ScheduleBoard.gapText 的算法一致，否则小部件说「1 天后」、
        // 点进 App 却显示「2 天后」。
        val gapDays = Math.round(gapMinutes / (24 * 60.0)).toInt()
        return State.Rest(
            next = course.toRow(),
            gapDays = gapDays,
            weekdayLabel = ScheduleText.shortWeekday(course.weekday),
        )
    }

    /** 一行文案：「8:00」「1-2 节」这类，供 XHTML 层直接用 */
    private fun CourseDto.toRow(): Row = Row(
        periods = if (startPeriod == endPeriod) "$startPeriod" else "$startPeriod-$endPeriod",
        time = PeriodTable.timeText(startPeriod, endPeriod),
        name = name,
        location = location?.takeIf { it.isNotBlank() },
        teacher = teacher?.takeIf { it.isNotBlank() },
    )

    /** 当前时刻是当天的第几分钟（本地时区）。0:00 → 0，23:59 → 1439 */
    internal fun minutesOfDay(millis: Long): Int {
        val text = ScheduleText.timeText(millis) // "HH:mm"
        return PeriodTable.parseMinutes(text)
    }

    /**
     * 今天是否是国定假期（**只用于文案**：标题显示「假期」而不是星期几）。
     * 判定与 [ScheduleBoard.nextInDays] 同源，避免"一处跳假期、一处不跳"的不一致。
     */
    fun isHolidayToday(nowMillis: Long = System.currentTimeMillis()): Boolean =
        Holidays.isHoliday(ScheduleText.startOfDay(nowMillis))
}
