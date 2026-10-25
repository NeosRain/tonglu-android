package cn.tonglu.app.widget

import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.ScheduleBoard

/**
 * 「完整课表」小部件的**整周网格** —— 纯函数，不碰 Context / Compose / Android 时间。
 *
 * ## 为什么单独一层（同 [TodayWidgetModel] 的理由）
 *
 * 小部件没有 UI 可点、没有 dump 可读，走查只能靠肉眼截图。
 * 「哪节课放在哪一格、格子跨几行、重叠了怎么办」如果写在画的一侧，
 * 错了只能肉眼盯；抽成纯函数才能在 JVM 单测里逐格断言（含对照组）。
 *
 * ## 网格的四个决定（都有单测钉住，改之前先看测试）
 *
 * 1. **行 = 节次**（不是大节）：第 1 行是第 1 节、第 2 行是第 2 节……
 *    一门「1-2 节」的课占两行。行高 [WeekWidgetLayout.rowUnit] 是按小节定的，
 *    这样「1-3 节」「单节」这种不规则课也能对齐。
 * 2. **格子的行号就是节次**：`startRow = startPeriod`，`span = endPeriod - startPeriod + 1`。
 *    跨列不跨列不存在 —— 一天一列，列内纵向排。
 * 3. 🔴 **重叠的课往下堆**：同一天两门课节次交叠时（选修课冲突、教务数据脏），
 *    后来者放到「前面那门结束之后」的第一个空行，跨度按剩余行数截断。
 *    这是**有损的**（画不出真正的并排），但并排在 7 列的格子里根本放不下 ——
 *    宁可堆下去，也不能两门课叠成一格互相盖住。
 * 4. 🔴 **超出 [MAX_PERIODS] 的直接丢**（计入 [Grid.dropped]）：一节第 31 节以后的课
 *    在网格里没有行可放。丢掉是显示层的决定，**不改数据**；丢了几条要在
 *    界面上有痕迹（底部「还有 N 节」），不许静默消失 —— 那是「静默裁掉」的另一种形态。
 */
object WeekWidgetModel {

    /** 与多校文件及大课表统一支持30节。超出按丢弃处理（见 [Grid.dropped]）。 */
    const val MAX_PERIODS = 30

    /** 网格里的一格：一天列内的一个课程块。 */
    data class Cell(
        /** 第几节开始（= 网格行号，1 起） */
        val startRow: Int,
        /** 跨几行（几节） */
        val span: Int,
        /** 课名（画的时候取前两个字，这里存全名 —— 颜色键也要用它） */
        val name: String,
        val location: String? = null,
    ) {
        val endRow: Int get() = startRow + span - 1
    }

    /**
     * 整周网格。`days[index]` 对应周 (index+1)（周一 = 0 号位）。
     * [rows] = 本周用到的最大行数（0 = 一节课都没有）。
     */
    data class Grid(
        val rows: Int,
        val days: List<List<Cell>>,
        /** 因超出 [MAX_PERIODS] 而丢弃的课数（界面上要报「还有 N 节」） */
        val dropped: Int = 0,
    )

    /**
     * 由全部课程 + 当前教学周算出网格。
     *
     * @param courses 全部课程（不过滤 —— 周次过滤交给 [ScheduleBoard.ofDay]，
     *   那边已经处理了 `weeks` 单双周与 `startWeek..endWeek` 区间两种口径）
     * @param week 当前教学周，null = 没设开学日期（此时不做周次过滤，全部课都画）
     */
    fun build(courses: List<CourseDto>, week: Int?): Grid {
        if (courses.isEmpty()) return Grid(rows = 0, days = List(7) { emptyList() })

        var dropped = 0
        val days = (1..7).map { day ->
            // ofDay 已按 startPeriod 排序；同节次的再按课名排一次，保证「同一天谁先谁后」
            // 是确定的（不依赖数据库返回顺序）—— 否则同一份课表两次构建可能摆法不同。
            ScheduleBoard.ofDay(courses, day, week)
                .sortedWith(compareBy({ it.startPeriod }, { it.name }))
                .fold(mutableListOf<Cell>() to 0) { (cells, lastEnd), course ->
                    val span = course.endPeriod - course.startPeriod + 1
                    val start = maxOf(course.startPeriod, lastEnd + 1)
                    if (start > MAX_PERIODS) {
                        dropped += 1
                        cells to lastEnd
                    } else {
                        val capped = minOf(span, MAX_PERIODS - start + 1)
                        cells.add(Cell(startRow = start, span = capped, name = course.name, location = course.location))
                        cells to (start + capped - 1)
                    }
                }.let { (cells, lastEnd) -> cells to lastEnd }
        }

        val rows = days.maxOfOrNull { it.second } ?: 0
        return Grid(rows = rows, days = days.map { it.first }, dropped = dropped)
    }
}
