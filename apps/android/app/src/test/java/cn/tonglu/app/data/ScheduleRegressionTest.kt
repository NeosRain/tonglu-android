package cn.tonglu.app.data

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class ScheduleRegressionTest {
    private fun day(year: Int, month: Int, date: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, date, 12, 0)
    }.timeInMillis

    @Test fun teachingWeeksStartOnMondayEvenWhenTermStartsOnTuesday() {
        val term = "2026-09-01"
        assertEquals(1, ScheduleText.weekOf(term, day(2026, 8, 31)))
        assertEquals(1, ScheduleText.weekOf(term, day(2026, 9, 6)))
        assertEquals(2, ScheduleText.weekOf(term, day(2026, 9, 7)))
        assertEquals(5, ScheduleText.weekOf(term, day(2026, 9, 30)))
        assertNull(ScheduleText.weekOf(term, day(2026, 8, 30)))
        for (week in 1..20) {
            val monday = ScheduleText.weekStartOfTerm(term, week)!!
            assertEquals(week, ScheduleText.weekOf(term, monday))
        }
    }

    @Test fun selectedWeeksUseTheirOwnDatesAndMissingTermHasNoInventedDate() {
        assertNull(ScheduleText.weekStartOfTerm(null, 1))
        val first = ScheduleText.weekStartOfTerm("2026-09-01", 1)!!
        val second = ScheduleText.weekStartOfTerm("2026-09-01", 2)!!
        assertEquals(7L * 24 * 3600 * 1000, second - first)
        assertNull(Holidays.holidayAt(first + 3L * 24 * 3600 * 1000))
        assertNotNull(Holidays.holidayAt(day(2026, 10, 1)))
    }

    private fun course(id: String, weekday: Int, weeks: List<Int>) = CourseDto(
        id, id, weekday = weekday, startPeriod = 1, endPeriod = 2,
        startWeek = 1, endWeek = 20, weeks = weeks,
    )

    @Test fun switchingWeeksChangesWednesdayCoursesAndKeepsHolidayCourses() {
        val odd = course("单周课", 3, listOf(1, 3, 5))
        val even = course("双周课", 3, listOf(2, 4, 6))
        val thursday = course("周四课", 4, listOf(5))
        val courses = listOf(odd, even, thursday)
        assertEquals(listOf(odd), ScheduleBoard.ofDay(courses, 3, 1))
        assertEquals(listOf(even), ScheduleBoard.ofDay(courses, 3, 2))
        assertEquals(listOf(thursday), ScheduleBoard.ofDay(courses, 4, 5))
    }

    @Test fun nextCourseAfterSundayUsesNextTeachingWeek() {
        val monday = course("下周一", 1, listOf(2))
        // nextInDays 是真实日历感知的（国定假期要跳过），所以「今天」必须传一个非假期的周日
        assertEquals(monday, ScheduleBoard.nextInDays(listOf(monday), 7, 1, 600, todayStart = day(2026, 10, 11))?.first)
    }

    /** 2026-10-05 用户报告：国庆 10-05 还在放假，首页却数「还剩 4 节课」。 */
    @Test fun nextInDaysSkipsNationalHolidays() {
        val monday = course("周一课", 1, listOf(1, 2, 3))
        val thursday = course("周四课", 4, listOf(1, 2, 3))
        // 前提：10-05 确实在假期里（10-01~10-07），10-08 周四不在
        assertNotNull(Holidays.holidayAt(day(2026, 10, 5)))
        assertNull(Holidays.holidayAt(day(2026, 10, 8)))

        // 假期里问「下次课」：06/07 都在假期里，必须跳到 10-08 周四
        val next = ScheduleBoard.nextInDays(listOf(monday, thursday), 1, 1, 600, todayStart = day(2026, 10, 5))
        assertEquals(thursday, next?.first)
        // gap 至少跨了 2 天（10-08 相对 10-05 隔着 06/07 两个假期日）
        assertTrue("gap 应至少 2 天", next!!.second >= 2 * 24 * 60)

        // 对照：非假期的周一，今天还没上的课直接就是「下次课」
        val normal = ScheduleBoard.nextInDays(listOf(monday), 1, 1, 400, todayStart = day(2026, 10, 12))
        assertEquals(monday, normal?.first)
    }
}
