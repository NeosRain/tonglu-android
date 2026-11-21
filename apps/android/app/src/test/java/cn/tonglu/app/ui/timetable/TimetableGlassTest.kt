package cn.tonglu.app.ui.timetable

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import cn.tonglu.app.data.PeriodSlot
import cn.tonglu.app.data.PeriodTable
import cn.tonglu.app.ui.DarkPalette
import cn.tonglu.app.ui.courseFallbackColor
import cn.tonglu.app.ui.coursePalette
import org.junit.Assert.*
import org.junit.Test

class TimetableGlassTest {
    @Test fun `拖拽压平休息上方内部和下方坐标`() {
        val breaks = listOf(TimetableBreak(4, "午休"), TimetableBreak(8, "晚休"))
        assertEquals(300f, timetableDragY(300f, 76f, breaks, 22f), .001f)
        assertEquals(304f, timetableDragY(304f, 76f, breaks, 22f), .001f)
        assertEquals(304f, timetableDragY(310f, 76f, breaks, 22f), .001f)
        assertEquals(304f, timetableDragY(324f, 76f, breaks, 22f), .001f)
        assertEquals(304f, timetableDragY(326f, 76f, breaks, 22f), .001f)
        assertEquals(380f, timetableDragY(402f, 76f, breaks, 22f), .001f)
        assertEquals(608f, timetableDragY(641f, 76f, breaks, 22f), .001f)
        assertEquals(684f, timetableDragY(728f, 76f, breaks, 22f), .001f)
        // 自动滚动和指针移动合成后再映射，正反方向都不能多出休息高度。
        assertEquals(152f, timetableDragY(402f, 76f, breaks, 22f) - timetableDragY(228f, 76f, breaks, 22f), .001f)
        assertEquals(-152f, timetableDragY(228f, 76f, breaks, 22f) - timetableDragY(402f, 76f, breaks, 22f), .001f)
        assertEquals(-10f, timetableDragY(-10f, 76f, breaks, 22f), .001f)
        assertEquals(400f, timetableDragY(400f, 76f, emptyList(), 22f), .001f)
    }

    @Test fun `默认只有午休而非猜测第八节后晚休`() {
        assertEquals(listOf(TimetableBreak(4, "午休")), timetableBreaks(PeriodTable.DEFAULT))
        assertEquals(listOf(TimetableBreak(8, "晚休")), timetableBreaks(listOf(
            PeriodSlot(8, "16:15", "17:00"), PeriodSlot(9, "17:50", "18:35"))))
        assertTrue(timetableBreaks(listOf(PeriodSlot(1, "08:00", "08:45"), PeriodSlot(2, "09:34", "10:19"))).isEmpty())
    }

    @Test fun `跨休息块与后续行共用同一坐标`() {
        val breaks = timetableBreaks(PeriodTable.DEFAULT)
        assertEquals(326f, periodTop(5, 76f, breaks, 22f), 0.001f)
        assertEquals(174f, courseGridHeight(4, 5, 76f, breaks, 22f), 0.001f)
        assertEquals(76f, courseGridHeight(4, 4, 76f, breaks, 22f), 0.001f)
    }

    @Test fun `时间线覆盖铃声课间休息与范围边界`() {
        val slots = PeriodTable.DEFAULT
        assertNull(timetableTimeOffset(slots, 76f, 22f, 479))
        assertEquals(0f, timetableTimeOffset(slots, 76f, 22f, 480)!!, 0.001f)
        assertEquals(76f, timetableTimeOffset(slots, 76f, 22f, 530)!!, 0.001f)
        assertEquals(315f, timetableTimeOffset(slots, 76f, 22f, 755)!!, 0.001f)
        assertEquals(326f, timetableTimeOffset(slots, 76f, 22f, 800)!!, 0.001f)
        assertEquals(782f, timetableTimeOffset(slots, 76f, 22f, 1150)!!, 0.001f)
        assertNull(timetableTimeOffset(slots, 76f, 22f, 1151))
        assertNull(timetableTimeOffset(emptyList(), 76f, 22f, 600))
    }

    @Test fun `色板和所有兜底色相在浅深真实叠色上满足正文对比度`() {
        // 兜底按黄金角产生连续色相，逐度并加上实际分配序列覆盖查表边界。
        val colors = coursePalette + (0..359).map { Color.hsv(it.toFloat(), .72f, .68f) } +
            (0..720).map { courseFallbackColor(it) }
        listOf(false, true).forEach { dark ->
            val canvas = if (dark) DarkPalette.Canvas else Color(0xFFF5F6F8)
            val surface = if (dark) DarkPalette.Surface else Color.White
            listOf(false, true).forEach { wallpaper ->
                colors.forEach { color ->
                    val glass = courseGlassColors(color, dark, canvas, surface, wallpaper)
                    val background = glass.tint.compositeOver(glass.underlay.compositeOver(canvas))
                    assertTrue("dark=$dark wallpaper=$wallpaper color=$color", contrastRatio(glass.ink, background) >= 4.5f)
                }
            }
        }
    }

    @Test fun `倒计时跨整点与开始结束边界`() {
        assertEquals("1 小时 5 分钟后", todayCourseTiming(605, 695, 540).countdown)
        assertEquals("5 分钟后", todayCourseTiming(605, 695, 600).countdown)
        assertEquals(TodayCourseState.Ongoing, todayCourseTiming(605, 695, 605).state)
        assertEquals("还剩 1 分钟下课", todayCourseTiming(605, 695, 694).countdown)
        assertEquals(TodayCourseState.Finished, todayCourseTiming(605, 695, 695).state)
        assertEquals(0, todayCourseTiming(605, 695, 800).remainingMinutes)
        assertTrue(listOf(480 to 525, 605 to 695).all { todayCourseTiming(it.first, it.second, 800).state == TodayCourseState.Finished })
    }

    @Test fun `分组取课程开始时间并保留未知作息`() {
        assertEquals(TodayCourseGroup.Morning, todayCourseTiming(719, 760, 600).group)
        assertEquals(TodayCourseGroup.Afternoon, todayCourseTiming(720, 800, 600).group)
        assertEquals(TodayCourseGroup.Evening, todayCourseTiming(1080, 1125, 600).group)
        assertEquals(TodayCourseState.Unknown, todayCourseTiming(null, null, 600).state)
    }
}
