package cn.tonglu.app.widget

import cn.tonglu.app.data.CourseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeekWidgetModel] 的单测 —— 整周网格的「谁放哪格、跨几行、重叠怎么办」。
 *
 * 这个部件 2026-10-16 才有，网格规则（[WeekWidgetModel] 类注释的 4 条）每条都要有判据：
 * 小部件没有 dump 可读，网格摆错了只能靠肉眼盯，所以全部抽成纯函数逐格断言。
 */
class WeekWidgetModelTest {

    /** 造一门课：id 自动编号，其余字段给最常用的值。 */
    private fun course(
        weekday: Int,
        start: Int,
        end: Int,
        name: String = "高等数学",
        startWeek: Int = 1,
        endWeek: Int = 16,
        weeks: List<Int> = emptyList(),
    ) = CourseDto(
        id = "c-$weekday-$start-$name",
        name = name,
        weekday = weekday,
        startPeriod = start,
        endPeriod = end,
        startWeek = startWeek,
        endWeek = endWeek,
        weeks = weeks,
    )

    // ── 基本摆格 ────────────────────────────────────────────────

    @Test
    fun `一格课 - 行号就是节次`() {
        val grid = WeekWidgetModel.build(listOf(course(weekday = 3, start = 3, end = 4)), week = 2)
        assertEquals(4, grid.rows)
        val cell = grid.days[2].single()
        assertEquals(3, cell.startRow)
        assertEquals(2, cell.span)
        assertEquals(4, cell.endRow)
    }

    @Test
    fun `单节课 - 跨度是 1`() {
        val grid = WeekWidgetModel.build(listOf(course(weekday = 1, start = 5, end = 5)), week = null)
        assertEquals(1, grid.days[0].single().span)
    }

    @Test
    fun `行数取整周最大 - 别的天短也照常`() {
        val grid = WeekWidgetModel.build(
            listOf(
                course(weekday = 1, start = 1, end = 2),
                course(weekday = 5, start = 9, end = 10),
            ),
            week = null,
        )
        assertEquals("行数 = 本周最大节次", 10, grid.rows)
        assertEquals("周一只有那门 1-2 节的课", 1, grid.days[0].size)
        assertEquals("周五只有那门 9-10 节的课", 1, grid.days[4].size)
    }

    @Test
    fun `周次过滤 - 单双周只画本周的`() {
        val odd = course(weekday = 2, start = 1, end = 2, name = "单周课", weeks = listOf(1, 3, 5))
        val grid = WeekWidgetModel.build(listOf(odd), week = 2)
        assertEquals("第 2 周不该画单周课", 0, grid.days[1].size)
        val gridOdd = WeekWidgetModel.build(listOf(odd), week = 3)
        assertEquals("第 3 周该画单周课", 1, gridOdd.days[1].size)
    }

    @Test
    fun `week 为 null - 不做周次过滤（没设开学日期时全画）`() {
        val c = course(weekday = 2, start = 1, end = 2, startWeek = 3, endWeek = 4)
        val grid = WeekWidgetModel.build(listOf(c), week = null)
        assertEquals(1, grid.days[1].size)
    }

    // ── 重叠与越界（这个网格最容易错的两处）────────────────────

    @Test
    fun `同一天重叠的课 - 后来者往下堆，不互相盖住`() {
        val grid = WeekWidgetModel.build(
            listOf(
                course(weekday = 1, start = 1, end = 4, name = "占用者"),
                course(weekday = 1, start = 3, end = 6, name = "被挤者"),
            ),
            week = null,
        )
        val cells = grid.days[0]
        assertEquals(2, cells.size)
        assertEquals(1, cells[0].startRow)
        assertEquals("起点被顶到第 5 行（前一节 4 结束之后）", 5, cells[1].startRow)
        assertEquals("被挤者是 3-6 节、跨度 4，堆下去后占 5-8 行", 4, cells[1].span)
        assertEquals("行数跟着被堆下去的那节走", 8, grid.rows)
    }

    @Test
    fun `重叠堆到超出 30 节 - 丢弃并计数，不静默`() {
        val grid = WeekWidgetModel.build(
            listOf(
                course(weekday = 1, start = 1, end = 30, name = "长课"),
                course(weekday = 1, start = 2, end = 3, name = "塞不下的"),
            ),
            week = null,
        )
        assertEquals(1, grid.days[0].size)
        assertEquals("被丢的课必须计数（界面上要报「还有 N 节」）", 1, grid.dropped)
    }

    @Test
    fun `课本身超出 30 节 - 跨度按上限截断`() {
        // 第31节超出统一上限；29–31节只显示到30。
        val beyond = WeekWidgetModel.build(
            listOf(course(weekday = 1, start = 31, end = 32)),
            week = null,
        )
        assertEquals(1, beyond.dropped)

        val crossing = WeekWidgetModel.build(
            listOf(course(weekday = 1, start = 29, end = 31)),
            week = null,
        )
        val cell = crossing.days[0].single()
        assertEquals(29, cell.startRow)
        assertEquals("跨度必须截到 MAX_PERIODS", 2, cell.span)
    }

    @Test
    fun `同一天同节次两门课 - 顺序按课名定，两次构建结果一致`() {
        val a = course(weekday = 1, start = 1, end = 2, name = "甲课")
        val b = course(weekday = 1, start = 1, end = 2, name = "乙课")
        val one = WeekWidgetModel.build(listOf(a, b), week = null)
        val two = WeekWidgetModel.build(listOf(b, a), week = null)
        assertEquals("输入顺序不同，摆法必须相同（不依赖数据返回顺序）", one, two)
        assertEquals("后者被堆到第 3 行", 3, two.days[0][1].startRow)
    }

    @Test fun `外校13到30节不丢失并保留原节次`() {
        val grid = WeekWidgetModel.build(listOf(course(1, 13, 16), course(5, 29, 30)), week = null)
        assertEquals(30, grid.rows)
        assertEquals(0, grid.dropped)
        assertEquals(13, grid.days[0].single().startRow)
        assertEquals(4, grid.days[0].single().span)
        assertEquals(29, grid.days[4].single().startRow)
    }

    // ── 空态与对照组 ────────────────────────────────────────────

    @Test
    fun `空课表 - 全空网格不炸`() {
        val grid = WeekWidgetModel.build(emptyList(), week = null)
        assertEquals(0, grid.rows)
        assertEquals(7, grid.days.size)
        assertEquals(0, grid.dropped)
    }

    @Test
    fun `control_周次过滤真的在过滤`() {
        // 对照：证明 week 参数不是摆设 —— 若过滤被写成「恒不过滤」，
        // 上面几条会照样通过。
        val only = course(weekday = 2, start = 1, end = 2, weeks = listOf(9))
        val inWeek = WeekWidgetModel.build(listOf(only), week = 9)
        val outWeek = WeekWidgetModel.build(listOf(only), week = 1)
        assertEquals(1, inWeek.days[1].size)
        assertEquals(0, outWeek.days[1].size)
        assertTrue("两种周次必须给出不同结果", inWeek.days[1] != outWeek.days[1])
    }
}
