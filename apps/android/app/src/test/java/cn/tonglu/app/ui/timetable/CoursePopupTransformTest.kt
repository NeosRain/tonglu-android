package cn.tonglu.app.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class CoursePopupTransformTest {
    private val source = Rect(10f, 30f, 60f, 180f)
    private val target = Rect(20f, 200f, 340f, 650f)
    @Test fun startMatchesTheActualCourseCell() {
        val t = coursePopupTransform(source, target, 0f)
        assertEquals(source.width, target.width * t.scaleX, .001f)
        assertEquals(source.height, target.height * t.scaleY, .001f)
        assertEquals(source.center.x, target.center.x + t.x, .001f)
        assertEquals(source.center.y, target.center.y + t.y, .001f)
    }
    @Test fun endMatchesTheUntransformedDetailCard() {
        assertEquals(CoursePopupTransform(1f, 1f, 0f, 0f), coursePopupTransform(source, target, 1f))
    }
    @Test fun missingOrNotLaidOutOriginNeverCreatesInvalidScale() {
        val t = coursePopupTransform(Rect.Zero, Rect.Zero, 0f)
        assertTrue(t.scaleX.isFinite() && t.scaleY.isFinite())
        assertEquals(0f, t.x, 0f)
        assertEquals(0f, t.y, 0f)
    }
}
