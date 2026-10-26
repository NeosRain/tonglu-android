package cn.tonglu.app.ui

import org.junit.Assert.*
import org.junit.Test

class CourseCellTextLayoutTest {
    @Test fun largeFontKeepsNameBeforeMetadata() {
        val normal = courseCellTextLayout(64f, 1f)
        val large = courseCellTextLayout(64f, 1.6f)
        assertTrue(normal.showPeriod)
        assertFalse(large.showLocation)
        assertFalse(large.showPeriod)
        assertEquals(2, large.titleLines)
    }
    @Test fun tallCourseCanShowMoreNameAndDetails() {
        val tall = courseCellTextLayout(140f, 1f)
        assertEquals(4, tall.titleLines)
        assertTrue(tall.showLocation)
        assertTrue(tall.showPeriod)
    }
    @Test fun tinyCellNeverRemovesTheCourseName() {
        assertEquals(1, courseCellTextLayout(5f, 2f).titleLines)
        assertFalse(courseCellTextLayout(5f, 2f).showLocation)
    }
}
