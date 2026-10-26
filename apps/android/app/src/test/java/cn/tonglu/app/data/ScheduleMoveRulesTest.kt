package cn.tonglu.app.data

import org.junit.Assert.*
import org.junit.Test

class ScheduleMoveRulesTest {
    private val original = CourseDto("one", "课程", "老师", "教室", 2, 3, 5, 1, 19, listOf(1, 3, 7), "#1971C2", "school_import")
    private fun move(mode: CourseDragMode = CourseDragMode.Move, x: Float = 0f, y: Float = 0f) = ScheduleMoveRules.drag(original, mode, x, y, 100f, 80f, 30)
    @Test fun snapsAtHalfRowAndColumn() {
        assertEquals(original, move(x = 49f, y = 39f))
        assertEquals(original.copy(weekday = 3, startPeriod = 4, endPeriod = 6), move(x = 51f, y = 41f))
    }
    @Test fun movingPreservesDurationAndAllOtherMetadata() {
        assertEquals(original.copy(weekday = 7, startPeriod = 28, endPeriod = 30), move(x = 9999f, y = 9999f))
        assertEquals(original.copy(weekday = 1, startPeriod = 1, endPeriod = 3), move(x = -9999f, y = -9999f))
    }
    @Test fun resizeCannotInvertOrChangeDay() {
        assertEquals(original.copy(startPeriod = 5), move(CourseDragMode.Start, x = 300f, y = 999f))
        assertEquals(original.copy(endPeriod = 3), move(CourseDragMode.End, y = -999f))
        assertEquals(original.copy(endPeriod = 30), move(CourseDragMode.End, y = 9999f))
    }
    @Test fun laneReuseKeepsOverlappingCoursesAndIsStable() {
        val second = original.copy(id = "two", startPeriod = 4, endPeriod = 6)
        val later = original.copy(id = "three", startPeriod = 7, endPeriod = 8)
        val a = ScheduleMoveRules.lanes(listOf(later, second, original))
        assertEquals(mapOf("one" to 0, "two" to 1, "three" to 0), a)
        assertEquals(a, ScheduleMoveRules.lanes(listOf(original, later, second)))
    }
    @Test fun conflictsRespectDiscreteWeeksAndIdentity() {
        assertEquals(0, ScheduleMoveRules.conflicts(original, listOf(original, original.copy(id="even", weeks=listOf(2,4)))))
        assertEquals(1, ScheduleMoveRules.conflicts(original, listOf(original.copy(id="odd", weeks=listOf(3)))))
        assertEquals(0, ScheduleMoveRules.conflicts(original, listOf(original.copy(id="later", startPeriod=6, endPeriod=8))))
    }
    @Test fun fullDayCourseCanMoveBetweenDaysWithoutShrinking() {
        val full = original.copy(startPeriod=1, endPeriod=30)
        assertEquals(full.copy(weekday=3), ScheduleMoveRules.drag(full, CourseDragMode.Move, 100f, 999f, 100f, 80f, 30))
    }
    @Test fun topAndBottomGripsResizeButMiddleMoves() {
        assertEquals(CourseDragMode.Start, ScheduleMoveRules.gripMode(4f, 152f, 14f))
        assertEquals(CourseDragMode.End, ScheduleMoveRules.gripMode(148f, 152f, 14f))
        assertEquals(CourseDragMode.Move, ScheduleMoveRules.gripMode(76f, 152f, 14f))
    }
    @Test fun shortCourseStillHasAMoveRegion() {
        assertEquals(CourseDragMode.Move, ScheduleMoveRules.gripMode(10f, 20f, 14f))
        assertEquals(CourseDragMode.Start, ScheduleMoveRules.gripMode(2f, 20f, 14f))
        assertEquals(CourseDragMode.End, ScheduleMoveRules.gripMode(19f, 20f, 14f))
    }
    @Test fun viewportAutoScrollIsIncludedInDropPosition() {
        val moved = ScheduleMoveRules.dragWithScroll(original, CourseDragMode.Move,
            0f, 0f, 100, 260, 100f, 80f, 30)
        assertEquals(original.copy(startPeriod = 5, endPeriod = 7), moved)
        assertEquals(original, ScheduleMoveRules.dragWithScroll(original, CourseDragMode.Move,
            0f, -160f, 100, 260, 100f, 80f, 30))
    }

    @Test fun unrelatedCoursesDoNotLoseWidthToAnEarlierOverlap() {
        val overlapping = original.copy(id = "overlap", startPeriod = 4, endPeriod = 6)
        val later = original.copy(id = "later", startPeriod = 7, endPeriod = 8)
        val otherDay = original.copy(id = "other-day", weekday = 3)
        val layout = ScheduleMoveRules.groupedLanes(listOf(later, otherDay, overlapping, original))
        assertEquals(0 to 2, layout[original.id])
        assertEquals(1 to 2, layout[overlapping.id])
        assertEquals(0 to 1, layout[later.id])
        assertEquals(0 to 1, layout[otherDay.id])
    }

}
