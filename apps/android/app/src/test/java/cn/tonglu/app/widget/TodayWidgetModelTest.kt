package cn.tonglu.app.widget

import cn.tonglu.app.data.CourseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [TodayWidgetModel] 的单测。
 *
 * ## 为什么这个测试值得写
 *
 * 小部件是**没有 uiautomator 树、只能肉眼截图**的表面 —— 上线后如果「今天没课」
 * 却显示有课、或者跨天没翻，走查很难抓到。所以算错的风险全部压在纯函数这一层，
 * 这里必须穷举掉。
 *
 * ## 对照组（项目要求：破坏性用例必须能变红）
 *
 * 每个关键断言旁边都配一个「故意用错输入 / 用错算法」的对照例，证明断言**真的在测东西**：
 * - [control_wrongWeekdayGivesNoCourses] —— 拿错星期几就该是「没课」，证明
 *   [build] 真的按 weekday 过滤，而不是「有课表就返回有课」。
 * - [control_unfilteredWouldIncludeOtherDays] —— 不做周次/星期过滤的话
 *   `ofDay` 会多出课，证明过滤生效。
 *
 * 时间一律用固定 `millis`（`at(2026, 10, 12, 9, 0)` 这种），**不让测试依赖「今天」** ——
 * 否则测试会在周一绿、周日红。
 */
class TodayWidgetModelTest {

    /** 造一节课。默认落在第 1-2 节、第 1..20 周（覆盖全学期）。 */
    private fun course(
        id: String,
        name: String,
        weekday: Int,
        start: Int,
        end: Int,
        location: String? = "教学楼 A101",
        teacher: String? = "张老师",
        startWeek: Int = 1,
        endWeek: Int = 20,
    ) = CourseDto(
        id = id, name = name, weekday = weekday,
        startPeriod = start, endPeriod = end,
        startWeek = startWeek, endWeek = endWeek,
        location = location, teacher = teacher,
    )

    /** 本地时区的某年某月某日某时 → millis（**不用 java.time**，minSdk 24）。 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // 2026-10-12 是星期一（用 Calendar 断言一下，避免我记错导致整个测试语义错位）
    @Test
    fun `sanity - 2026-10-12 是星期一`() {
        assertEquals(1, cn.tonglu.app.data.ScheduleText.weekdayOf(at(2026, 10, 12, 9, 0)))
    }

    // ---------- 1. 今天有课 ----------

    @Test
    fun `今天有课 - 列出全部并标注正在上`() {
        val courses = listOf(
            course("a", "高等数学", weekday = 1, start = 1, end = 2),
            course("b", "大学英语", weekday = 1, start = 3, end = 4),
        )
        // 08:30 落在第 1 节的 08:00-08:45 里 → 应该有 ongoing
        val state = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 12, 8, 30))
        assertTrue("应为 Courses，实际 $state", state is TodayWidgetModel.State.Courses)
        state as TodayWidgetModel.State.Courses
        assertEquals(2, state.total)
        assertEquals(2, state.rows.size)
        assertEquals("1-2", state.rows[0].periods)
        // 辽东默认作息：1 节 08:00-08:45、2 节 08:55-09:40
        // → 1-2 连堂的展示时间是 "08:00-09:40"（**不是** "08:00-08:45"）
        assertEquals("08:00-09:40", state.rows[0].time)
        assertEquals("高等数学", state.rows[0].name)
        assertNotNull("08:30 应识别出正在上课", state.ongoing)
        assertEquals("高等数学", state.ongoing!!.name)
    }

    @Test
    fun `今天有课 - 空档里不算正在上`() {
        val courses = listOf(course("a", "高等数学", weekday = 1, start = 1, end = 2))
        // 🔴 09:45 才是空档（1-2 节块 08:00~09:40 已结束、下一节没课）。
        //    不能用 08:50 举例：ScheduleBoard.current 判的是**整块的连续区间**
        //    （第 1 节开始 → 第 2 节结束），块内都算「正在上课」。
        val state = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 12, 9, 45))
        state as TodayWidgetModel.State.Courses
        assertNull("空档不应有正在上的课", state.ongoing)
        assertEquals(1, state.total)
    }

    @Test
    fun `今天有课 - 连堂块内中间时刻仍算正在上`() {
        // 对照上面那条：同样是「1-2 节」，08:50 在块内 → 必须判成正在上课。
        // 两条一起看，才说明「正在上」的边界是按块算的、不是按单节算的。
        val courses = listOf(course("a", "高等数学", weekday = 1, start = 1, end = 2))
        val state = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 12, 8, 50))
        state as TodayWidgetModel.State.Courses
        assertNotNull("08:50 在 1-2 节块内，应算正在上课", state.ongoing)
    }

    @Test
    fun `今天有课 - 超过 4 节只显示 4 行但 total 是全量`() {
        val courses = (1..6).map { course("c$it", "课$it", weekday = 1, start = it, end = it) }
        val state = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 12, 7, 0))
        state as TodayWidgetModel.State.Courses
        assertEquals("行数应被截到 MAX_ROWS", TodayWidgetModel.MAX_ROWS, state.rows.size)
        assertEquals("total 必须是全量（用于「还有 N 节」）", 6, state.total)
    }

    @Test
    fun `今天有课 - 按节次排序而不是按传入顺序`() {
        val courses = listOf(
            course("late", "下午课", weekday = 1, start = 7, end = 8),
            course("early", "早八", weekday = 1, start = 1, end = 2),
        )
        val state = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 12, 7, 0))
        state as TodayWidgetModel.State.Courses
        assertEquals("早八", state.rows[0].name)
        assertEquals("下午课", state.rows[1].name)
    }

    // ---------- 2. 今天没课 ----------

    @Test
    fun `今天没课 - 显示未来最近一次和天数`() {
        // 只在周三有课；周一看 → 2 天后
        val courses = listOf(course("w", "数据结构", weekday = 3, start = 1, end = 2))
        val state = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 12, 9, 0))
        assertTrue("应为 Rest，实际 $state", state is TodayWidgetModel.State.Rest)
        state as TodayWidgetModel.State.Rest
        assertEquals("数据结构", state.next.name)
        assertEquals(2, state.gapDays)
        assertEquals("周三", state.weekdayLabel)
    }

    @Test
    fun `今天没课 - 没有课表数据时是 Empty 而不是 Rest`() {
        val state = TodayWidgetModel.build(emptyList(), week = 6, nowMillis = at(2026, 10, 12, 9, 0))
        assertEquals(TodayWidgetModel.State.Empty, state)
    }

    @Test
    fun `今天没课 - 未来 7 天都没有时是 NoUpcoming`() {
        // 只有第 1 周的课，而当前是第 6 周 → 周次过滤后全空
        val courses = listOf(course("old", "上学期课", weekday = 3, start = 1, end = 2, startWeek = 1, endWeek = 1))
        val state = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 12, 9, 0))
        assertTrue("应为 NoUpcoming，实际 $state", state is TodayWidgetModel.State.NoUpcoming)
    }

    // ---------- 3. 周次过滤 ----------

    @Test
    fun `周次过滤 - 单双周课不匹配时不出现`() {
        // 只在奇数周上的课（weeks 明确列出），当前第 6 周（偶数）→ 不该出现
        val oddOnly = CourseDto(
            id = "odd", name = "单周课", weekday = 1,
            startPeriod = 1, endPeriod = 2, startWeek = 1, endWeek = 15,
            weeks = listOf(1, 3, 5, 7, 9, 11, 13, 15),
        )
        val state = TodayWidgetModel.build(listOf(oddOnly), week = 6, nowMillis = at(2026, 10, 12, 9, 0))
        assertTrue("第 6 周不该有单周课，实际 $state", state is TodayWidgetModel.State.NoUpcoming)
    }

    @Test
    fun `周次过滤 - week 为 null 时不做周次过滤（未设开学日期也能显示）`() {
        val courses = listOf(course("a", "高等数学", weekday = 1, start = 1, end = 2))
        val state = TodayWidgetModel.build(courses, week = null, nowMillis = at(2026, 10, 12, 8, 30))
        assertTrue("week=null 时应该照样显示今天的课", state is TodayWidgetModel.State.Courses)
    }

    // ---------- 对照组：证明上面的断言真的在测东西 ----------

    /**
     * 对照 1：**拿错星期几就该没课**。
     *
     * 如果 [TodayWidgetModel.build] 根本没按 weekday 过滤（例如误写成
     * `courses.filter { week == null || it.activeInWeek(week) }`），
     * 那么周二来问「周一的课」也会返回有课 —— 这个对照就会红。
     */
    @Test
    fun `对照组 - 拿错星期几时不该有课`() {
        val courses = listOf(course("mon", "周一课", weekday = 1, start = 1, end = 2))
        // 2026-10-13 是星期二
        val tuesday = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 13, 9, 0))
        assertTrue(
            "周二不该看到周一的课（若模型不按 weekday 过滤，这条会红）",
            tuesday is TodayWidgetModel.State.Rest,
        )
    }

    /**
     * 对照 2：**不做星期过滤会多出课**。
     *
     * 直接调底层 [cn.tonglu.app.data.ScheduleBoard.ofDay] 传「不过滤」的等价输入，
     * 证明「过滤前后数量确实不同」—— 否则上面的测试可能只是因为
     * 「反正只有一节课，过滤不过滤都是 1」而恰好通过。
     */
    @Test
    fun `对照组 - 不过滤会多出别的天的课（证明过滤真的起作用）`() {
        val courses = listOf(
            course("mon", "周一课", weekday = 1, start = 1, end = 2),
            course("tue", "周二课", weekday = 2, start = 1, end = 2),
        )
        val mondayOnly = cn.tonglu.app.data.ScheduleBoard.ofDay(courses, weekday = 1, week = 6)
        val unfiltered = courses
        assertEquals("周一应该只有 1 节课", 1, mondayOnly.size)
        assertEquals("全部课程有 2 节", 2, unfiltered.size)
        assertTrue("两者数量必须不同，否则过滤没生效", mondayOnly.size != unfiltered.size)
    }

    /**
     * 对照 3：**同一份课表，换个时间点结论就该不同**。
     *
     * 防止「build 忽略了 nowMillis、永远返回同一结果」这种最隐蔽的错
     * （小部件刷新后画面不变，肉眼极难发现）。
     */
    @Test
    fun `对照组 - 换个时间点结论必须不同`() {
        val courses = listOf(course("mon", "周一课", weekday = 1, start = 1, end = 2))
        val onMonday = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 12, 9, 0))
        val onTuesday = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 13, 9, 0))
        assertTrue("周一应有课", onMonday is TodayWidgetModel.State.Courses)
        assertTrue("周二应没课", onTuesday is TodayWidgetModel.State.Rest)
        assertTrue(
            "同一份课表在不同时间必须得出不同结论（否则 nowMillis 被忽略了）",
            onMonday::class != onTuesday::class,
        )
    }

    // ---------- 工具函数 ----------

    @Test
    fun `minutesOfDay 边界`() {
        assertEquals(0, TodayWidgetModel.minutesOfDay(at(2026, 10, 12, 0, 0)))
        assertEquals(8 * 60, TodayWidgetModel.minutesOfDay(at(2026, 10, 12, 8, 0)))
        assertEquals(23 * 60 + 59, TodayWidgetModel.minutesOfDay(at(2026, 10, 12, 23, 59)))
    }

    @Test
    fun `Row - 单节课时 periods 不写成 3-3`() {
        val courses = listOf(course("a", "体育", weekday = 1, start = 3, end = 3))
        val state = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 12, 7, 0))
        state as TodayWidgetModel.State.Courses
        assertEquals("3", state.rows[0].periods)
    }

    @Test
    fun `Row - 空字符串的教室和教师归一成 null`() {
        val courses = listOf(course("a", "自习", weekday = 1, start = 1, end = 2, location = "", teacher = "  "))
        val state = TodayWidgetModel.build(courses, week = 6, nowMillis = at(2026, 10, 12, 7, 0))
        state as TodayWidgetModel.State.Courses
        assertNull("空教室应为 null（小部件据此省略该行）", state.rows[0].location)
        assertNull("空白教师应为 null", state.rows[0].teacher)
    }
}
