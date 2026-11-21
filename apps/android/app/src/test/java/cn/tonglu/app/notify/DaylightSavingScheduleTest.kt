package cn.tonglu.app.notify

import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.Holidays
import cn.tonglu.app.widget.TodayWidgetModel
import cn.tonglu.app.widget.TodayWidgetSource
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 夏令时地区按「+24 小时」推日期会整天偏一小时：提醒提前、星期错位、小组件在 23 点反复刷新。 */
class DaylightSavingScheduleTest {
    private val original = TimeZone.getDefault()

    @Before fun newYork() {
        // Holidays 首次使用时按当时时区缓存假期毫秒；先在原时区加载，免得污染后续用例。
        Holidays.isHoliday(0L)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }
    @After fun restore() = TimeZone.setDefault(original)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute)
    }.timeInMillis

    @Test fun remindersKeepWallClockTimeAfterDaylightSavingEnds() {
        val monday = CourseDto(id = "m", name = "高等数学", weekday = 1, startPeriod = 1, endPeriod = 2,
            startWeek = 1, endWeek = 20)
        // 10/30 周五，11/1 夏令时结束；之后每个周一 08:00 上课。
        val alarms = ClassReminder.plan(listOf(monday), "2026-09-07", 15, at(2026, 10, 30, 12, 0))
        assertTrue(alarms.isNotEmpty())
        alarms.forEach { alarm ->
            val start = Calendar.getInstance().apply { timeInMillis = alarm.fireAt + alarm.leadMinutes * 60_000L }
            assertEquals(Calendar.MONDAY, start.get(Calendar.DAY_OF_WEEK))
            assertEquals(8, start.get(Calendar.HOUR_OF_DAY))
            assertEquals(0, start.get(Calendar.MINUTE))
        }
        assertTrue(alarms.any { it.fireAt == at(2026, 11, 2, 7, 45) })
    }

    @Test fun widgetRefreshesAtRealMidnightOnTheLongDay() {
        val now = at(2026, 11, 1, 23, 30)
        assertEquals(at(2026, 11, 2, 0, 0), TodayWidgetSource.nextRefreshAtState(TodayWidgetModel.State.Empty, now))
    }
}