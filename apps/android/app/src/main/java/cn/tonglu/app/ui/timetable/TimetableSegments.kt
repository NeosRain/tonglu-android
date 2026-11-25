package cn.tonglu.app.ui.timetable

import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.scheduleOverlapGroups

internal const val courseColumnGapDp = 2f
internal const val courseColumnInsetDp = 2f

internal data class TimetableSegment(val startPeriod: Int, val endPeriod: Int, val courses: List<CourseDto>) {
    fun visibleCount(widthDp: Float): Int =
        if (courses.size == 2 && (widthDp - courseColumnGapDp) / 2 >= 36f) 2 else 1
}

/** 输入是已按周筛选的同一天课程；边界之间的在场课程保持不变。 */
internal fun timetableSegments(courses: List<CourseDto>): List<TimetableSegment> =
    scheduleOverlapGroups(courses).flatMap { group ->
        val boundaries = group.courses.flatMap { listOf(it.startPeriod, it.endPeriod + 1) }.distinct().sorted()
        boundaries.zipWithNext { start, end ->
            TimetableSegment(start, end - 1, group.courses.filter { start in it.startPeriod..it.endPeriod })
        }
    }
