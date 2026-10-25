package cn.tonglu.app.widget

import cn.tonglu.app.data.CourseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [TodayWidgetSource] 里**纯函数部分**的单测。
 *
 * ## 为什么这里能测、且必须测
 *
 * [TodayWidgetSource.load] 需要 Context（读 Room + SharedPreferences），在 JVM 里跑不了。
 * 但「下一次该在什么时候刷新」和「\"08:00-09:40\" 怎么拆成分钟」这两件事
 * **完全不依赖 Context** —— 所以把它们抽成了
 * [TodayWidgetSource.nextRefreshAt]（state 重载）/ `startMinutes` / `endMinutes`。
 *
 * 这里测错的实际后果：小部件会「停在上一节课的状态」——
 * 08:45 下课了标题还写「正在上课」、上午课上完了还显示上午的课。
 * 这种错**没有崩溃、没有日志**，只有用户盯久了才发现，所以必须靠单测钉住。
 *
 * ## 对照组
 *
 * [control_用截图固定时间才会算错] 证明「不是随便给个时间都得出同一个答案」。
 */
class TodayWidgetSourceTest {

    private fun course(
        id: String, name: String, weekday: Int, start: Int, end: Int,
    ) = CourseDto(
        id = id, name = name, weekday = weekday,
        startPeriod = start, endPeriod = end,
        startWeek = 1, endWeek = 20,
    )

    /** 本地时区某时刻 → millis（不用 java.time，minSdk 24） */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun stateAt(courses: List<CourseDto>, now: Long) =
        TodayWidgetModel.build(courses, week = 6, nowMillis = now)

    // ---------- 下课那一刻：标题要从「正在上课」变回来 ----------

    @Test
    fun `正在上课时 - 下次刷新点是这节课下课`() {
        val courses = listOf(course("a", "高等数学", weekday = 1, start = 1, end = 2))
        // 2026-10-12 周一 08:30 → 1-2 节 08:00-09:40 正在上 → 09:40 下课
        val now = at(2026, 10, 12, 8, 30)
        val next = TodayWidgetSource.nextRefreshAtState(stateAt(courses, now), now)

        val expected = at(2026, 10, 12, 9, 40) + 1000
        assertEquals("应在 09:40 下课那一刻刷新（+1s 防边界）", expected, next)
    }

    // ---------- 没在上课：指向下一节的上课时刻 ----------

    @Test
    fun `课间空档 - 下次刷新点是下一节上课`() {
        // 1-2 节块 = 08:00~09:40、3-4 节块 = 10:10~11:50
        val courses = listOf(
            course("a", "高等数学", weekday = 1, start = 1, end = 2),
            course("b", "大学英语", weekday = 1, start = 3, end = 4),
        )
        // 🔴 09:45 才是真正的「空档」。
        //    不能拿 08:50 当课间 —— ScheduleBoard.current 判定的是**整个节的块**
        //    （第 1 节的 08:00 到第 2 节的 09:40 是连续的），08:50 落在块内 = 正在上课。
        //    这个坑第一次写测试时就踩了：以为「节与节之间」有缝，其实没有。
        val now = at(2026, 10, 12, 9, 45)
        val next = TodayWidgetSource.nextRefreshAtState(stateAt(courses, now), now)

        val expected = at(2026, 10, 12, 10, 10) + 1000
        assertEquals("空档里应指向下一节的 10:10", expected, next)
    }

    @Test
    fun `早上没课 - 指向今天第一节`() {
        val courses = listOf(course("a", "体育", weekday = 1, start = 5, end = 6))
        // 07:00 到校，第 5 节 13:20 才上课
        val now = at(2026, 10, 12, 7, 0)
        val next = TodayWidgetSource.nextRefreshAtState(stateAt(courses, now), now)

        val expected = at(2026, 10, 12, 13, 20) + 1000
        assertEquals("应指向 13:20", expected, next)
    }

    // ---------- 今天没课：明天 0 点 ----------

    @Test
    fun `今天没课 - 下次刷新是明天 0 点`() {
        // 只在周三有课，今天是周一
        val courses = listOf(course("w", "数据结构", weekday = 3, start = 1, end = 2))
        val now = at(2026, 10, 12, 9, 0)
        val next = TodayWidgetSource.nextRefreshAtState(stateAt(courses, now), now)

        val expected = at(2026, 10, 13, 0, 0)
        assertEquals("跨天才可能有课，应排到明天 0 点", expected, next)
    }

    @Test
    fun `没有数据 - 下次刷新是明天 0 点而不是崩溃`() {
        val now = at(2026, 10, 12, 9, 0)
        val next = TodayWidgetSource.nextRefreshAtState(TodayWidgetModel.State.Empty, now)
        assertEquals(at(2026, 10, 13, 0, 0), next)
    }

    @Test
    fun `今天课全上完了 - 明天 0 点`() {
        val courses = listOf(course("a", "早八", weekday = 1, start = 1, end = 2))
        // 22:00，今天的课早结束了 → 没有「下一节」→ 明天 0 点
        val now = at(2026, 10, 12, 22, 0)
        val next = TodayWidgetSource.nextRefreshAtState(stateAt(courses, now), now)
        assertEquals(at(2026, 10, 13, 0, 0), next)
    }

    /**
     * 3-4 节块内 11:00 —— **还在上课，刷新点是 11:50 下课**。
     *
     * 这条专门钉住「节与节之间的 10 分钟不是空档」。
     * 默认作息 3 节 = 10:10-10:55、4 节 = 11:05-11:50，
     * [cn.tonglu.app.ui.schedule] 的「正在上」判定和这里一样，
     * 看的是**整个连堂块** [10:10, 11:50]，中间那 10 分钟不算下课。
     *
     * 我写这条测试前已经在同一个坑里摔了两次（08:50、11:00），
     * 所以把它独立成用例：以后谁再想「拆开每节算」会立刻红。
     */
    @Test
    fun `连堂块内 11点00 仍算正在上 - 刷新点是 11点50`() {
        val courses = listOf(
            course("a", "高等数学", weekday = 1, start = 1, end = 2),
            course("b", "大学英语", weekday = 1, start = 3, end = 4),
        )
        val now = at(2026, 10, 12, 11, 0)
        val next = TodayWidgetSource.nextRefreshAtState(stateAt(courses, now), now)
        assertEquals("11:00 在 3-4 节块内，应等 11:50 下课", at(2026, 10, 12, 11, 50) + 1000, next)
    }

    // ---------- 时间字符串拆分 ----------

    @Test
    fun `startMinutes 和 endMinutes 正确拆出连堂的两端`() {
        // 1-2 连堂：08:00 开始、09:40 结束
        assertEquals(8 * 60, TodayWidgetSource.startMinutes("08:00-09:40"))
        assertEquals(9 * 60 + 40, TodayWidgetSource.endMinutes("08:00-09:40"))
    }

    @Test
    fun `时间格式不对时返回 null 而不是 0`() {
        // 🔴 返回 0 会被当成「0 点整」这个**合法**时间，于是小部件会在半夜莫名刷新；
        //    返回 null 才能让上层退回「明天 0 点」这个安全默认。
        assertNull(TodayWidgetSource.startMinutes(""))
        assertNull(TodayWidgetSource.startMinutes("08:00"))
        assertNull(TodayWidgetSource.endMinutes("08:00"))
        assertNull(TodayWidgetSource.endMinutes("08:00-"))
    }

    // ---------- 对照组 ----------

    /**
     * 对照：**换个时刻结论必须不同**。
     *
     * 如果 `nextRefreshAt` 忽略了 nowMillis（比如错写成「永远返回今天 24 点」），
     * 上面那些「应等于某具体时刻」的断言就会全红。
     * 这条把它单独钉出来，方便一眼看出是「算法没用到时间」还是「算错了」。
     */
    @Test
    fun `对照组 - 用截图固定时间才会算错`() {
        val courses = listOf(
            course("a", "高等数学", weekday = 1, start = 1, end = 2),
            course("b", "大学英语", weekday = 1, start = 3, end = 4),
        )
        val duringClass = TodayWidgetSource.nextRefreshAtState(
            stateAt(courses, at(2026, 10, 12, 8, 30)), at(2026, 10, 12, 8, 30),
        )
        // 🔴 别拿 11:00 当「课全上完了」——默认作息里 3-4 节是 10:10~11:50 的**连续块**
        //    （3 节 10:10-10:55、4 节 11:05-11:50），11:00 正卡在块内 = 还在上课，
        //    刷新点应是 11:50 下课，不是明天 0 点。这里第三次踩同一个坑：
        //    把两节之间那 10 分钟当成了「空档」。
        //    要测「上完了」必须用 11:50 之后，比如 12:30。
        val afterClass = TodayWidgetSource.nextRefreshAtState(
            stateAt(courses, at(2026, 10, 12, 12, 30)), at(2026, 10, 12, 12, 30),
        )
        // 08:30（正在上 1-2 节）→ 09:40 下课；12:30（3-4 节已下课）→ 明天 0 点
        assertEquals(at(2026, 10, 12, 9, 40) + 1000, duringClass)
        assertEquals(at(2026, 10, 13, 0, 0), afterClass)
        org.junit.Assert.assertNotEquals(
            "同一份课表在不同时刻必须得出不同的刷新点（否则 nowMillis 被忽略了）",
            duringClass, afterClass,
        )
    }

    // ---------- 无数据时：标题不能出现「今天 · null」 ----------

    /**
     * 为什么这条要钉住：小部件标题是 `周几 + 日期 + 周次` 拼出来的，
     * 而 [TodayWidgetSource.empty] 是**所有 early return 的统一出口**（没登录 /
     * 读 Store 抛异常 / 课表为空）。它一旦漏填某个字段，界面上写出来的就是
     * "今天 · null" 或 "今天 ·  · "，而这种脏字符串在编译期和 logcat 里都看不出来。
     */
    @Test
    fun `没有数据时 - 日期与星期不能为空`() {
        val now = at(2026, 10, 16, 8, 30)
        val snapshot = TodayWidgetSource.empty(now)

        assertTrue("weekdayLabel 漏填会把标题写成『今天 · null』", snapshot.weekdayLabel.isNotBlank())
        assertTrue("dateLabel 漏填会让副标题缺一截", snapshot.dateLabel.isNotBlank())
        assertNull("没数据时不该凭空给个周次", snapshot.week)
        assertEquals(TodayWidgetModel.State.Empty, snapshot.state)
        assertEquals(false, snapshot.hasData)
    }

    // ---------- 倒计时：没有课表的日子也要能带上 ----------

    /**
     * 为什么这条要钉住：`load()` 里有两条 early return（读不到课表 / 课表是空的）会走
     * [TodayWidgetSource.empty]。倒计时是**先取**再判课表的，如果哪天有人把取数挪到
     * 判空之后、又忘了往 `empty()` 里传，症状是「寒暑假整块小部件只剩一句『假期中』，
     * 考研倒计时凭空消失」—— 而这恰恰是最需要倒计时的日子。
     */
    @Test
    fun `empty 要把倒计时带出去，不能吞掉`() {
        val now = at(2026, 10, 16, 8, 30)
        val item = TodayWidgetDeadlines.Item(
            kind = TodayWidgetDeadlines.Item.Kind.EXAM,
            title = "考研",
            label = "还有 65 天",
            days = 65,
            urgent = false,
        )
        val snapshot = TodayWidgetSource.empty(now, listOf(item))

        assertEquals(1, snapshot.deadlines.size)
        assertEquals("考研", snapshot.deadlines.first().title)
        assertEquals(TodayWidgetModel.State.Empty, snapshot.state)
        // 对照：不传就是空 —— 否则上面的断言可能被「empty 里写死了某条数据」蒙过去。
        assertTrue("不传倒计时时必须是空的", TodayWidgetSource.empty(now).deadlines.isEmpty())
    }
}
