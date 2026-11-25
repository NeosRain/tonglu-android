package cn.tonglu.app.ui.timetable

import cn.tonglu.app.data.CourseDto
import org.junit.Assert.assertEquals
import org.junit.Test

class TimetableSegmentsTest {
    @Test fun `两门同节次的课达到最小卡宽并排`() {
        val first = demoCourse("a", "高等数学", 1, 2)
        val second = demoCourse("b", "大学英语", 1, 2)
        val segment = timetableSegments(listOf(second, first)).single()

        assertEquals(listOf(first, second), segment.courses)
        assertEquals(2, segment.visibleCount(74f))
    }

    @Test fun `宽度不足时只显示首课且只隐藏一门`() {
        val segment = timetableSegments(listOf(
            demoCourse("a", "高等数学", 1, 2), demoCourse("b", "大学英语", 1, 2),
        )).single()

        assertEquals(1, segment.visibleCount(73.9f))
        assertEquals(1, segment.courses.size - segment.visibleCount(73.9f))
    }

    @Test fun `三门同节次的课在宽屏也收起后两门`() {
        val segment = timetableSegments(listOf(
            demoCourse("a", "高等数学", 1, 2), demoCourse("b", "大学英语", 1, 2),
            demoCourse("c", "程序设计", 1, 2),
        )).single()

        assertEquals(1, segment.visibleCount(150f))
        assertEquals(2, segment.courses.size - segment.visibleCount(150f))
    }

    @Test fun `部分重叠只在相交节次并排`() {
        val first = demoCourse("a", "高等数学", 1, 3)
        val second = demoCourse("b", "大学英语", 2, 4)

        val segments = timetableSegments(listOf(second, first))

        assertEquals(listOf(
            TimetableSegment(1, 1, listOf(first)),
            TimetableSegment(2, 3, listOf(first, second)),
            TimetableSegment(4, 4, listOf(second)),
        ), segments)
        assertEquals(listOf(1, 2, 1), segments.map { it.visibleCount(74f) })
    }

    @Test fun `链式重叠不把同一天的其他课并入选择器`() {
        val first = demoCourse("a", "高等数学", 1, 2)
        val second = demoCourse("b", "大学英语", 2, 4)
        val third = demoCourse("c", "程序设计", 4, 5)
        val standalone = demoCourse("d", "大学体育", 7, 8)

        assertEquals(listOf(
            TimetableSegment(1, 1, listOf(first)),
            TimetableSegment(2, 2, listOf(first, second)),
            TimetableSegment(3, 3, listOf(second)),
            TimetableSegment(4, 4, listOf(second, third)),
            TimetableSegment(5, 5, listOf(third)),
            TimetableSegment(7, 8, listOf(standalone)),
        ), timetableSegments(listOf(third, standalone, second, first)))
    }

    private fun demoCourse(id: String, name: String, start: Int, end: Int) = CourseDto(
        id = "demo-$id", name = "$name（示例）", location = "文萃楼 402",
        weekday = 3, startPeriod = start, endPeriod = end, startWeek = 1, endWeek = 16, source = "demo",
    )
}
