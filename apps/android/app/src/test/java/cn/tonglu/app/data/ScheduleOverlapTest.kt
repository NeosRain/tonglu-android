package cn.tonglu.app.data

import org.junit.Assert.*
import org.junit.Test

class ScheduleOverlapTest {
    private fun course(id: String, start: Int, end: Int, weeks: List<Int> = emptyList()) =
        CourseDto(id, "课程$id", weekday = 1, startPeriod = start, endPeriod = end,
            startWeek = 1, endWeek = 20, weeks = weeks)

    @Test fun sameStartKeepsBothCourses() {
        val groups = scheduleOverlapGroups(listOf(course("b", 1, 2), course("a", 1, 2)))
        assertEquals(listOf("a", "b"), groups.single().courses.map { it.id })
        assertEquals(1, groups.single().startPeriod)
        assertEquals(2, groups.single().endPeriod)
    }
    @Test fun partialAndTransitiveOverlapKeepEveryCourse() {
        val groups = scheduleOverlapGroups(listOf(course("c", 4, 5), course("a", 1, 2), course("b", 2, 4)))
        assertEquals(listOf("a", "b", "c"), groups.single().courses.map { it.id })
        assertEquals(5, groups.single().endPeriod)
    }
    @Test fun adjacentPeriodsStaySeparate() {
        assertEquals(2, scheduleOverlapGroups(listOf(course("a", 1, 2), course("b", 3, 4))).size)
    }
    @Test fun containedAndIdenticallyNamedCoursesAreNotDropped() {
        val a = course("a", 1, 6)
        val b = course("b", 2, 3).copy(name = a.name)
        assertEquals(2, scheduleOverlapGroups(listOf(b, a)).single().courses.size)
    }
    @Test fun weekFilteringHappensBeforeGrouping() {
        val courses = listOf(course("odd", 1, 2, listOf(1, 3)), course("even", 1, 2, listOf(2, 4)))
        assertEquals("odd", scheduleOverlapGroups(courses.filter { it.activeInWeek(1) }).single().courses.single().id)
        assertEquals(2, scheduleOverlapGroups(courses).single().courses.size)
    }
    @Test fun emptyInputAndInputOrderArePreserved() {
        assertTrue(scheduleOverlapGroups(emptyList()).isEmpty())
        val input = listOf(course("late", 7, 8), course("early", 1, 2))
        assertEquals(listOf("early", "late"), scheduleOverlapGroups(input).flatMap { it.courses }.map { it.id })
        assertEquals("late", input.first().id)
    }
}
