package cn.tonglu.app.data.timetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseRecognitionTest {
    @Test fun extractsCommonChineseCourseFields() {
        val draft = parseCourseText("概率论与数理统计 周四 1-2节 1-16周 教学楼A408 何丽老师")
        assertEquals("概率论与数理统计", draft.name)
        assertEquals(4, draft.weekday)
        assertEquals(1, draft.startPeriod)
        assertEquals(2, draft.endPeriod)
        assertEquals(1, draft.startWeek)
        assertEquals(16, draft.endWeek)
        assertEquals("教学楼A408", draft.location)
        assertEquals("何丽", draft.teacher)
    }

    @Test fun extractsSpokenChinesePeriodsAndOddWeeks() {
        val draft = parseCourseText("大学物理 星期二 第三到第四节 单周")
        assertEquals("大学物理", draft.name)
        assertEquals(2, draft.weekday)
        assertEquals(3, draft.startPeriod)
        assertEquals(4, draft.endPeriod)
        assertEquals("ODD", draft.weekMode)
    }

    @Test fun doesNotInventUnknownFields() {
        val draft = parseCourseText("下周找老师确认教室")
        assertNull(draft.name)
        assertNull(draft.weekday)
        assertNull(draft.startPeriod)
        assertNull(draft.location)
    }
}
