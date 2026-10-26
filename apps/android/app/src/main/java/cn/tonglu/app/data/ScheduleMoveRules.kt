package cn.tonglu.app.data

import kotlin.math.roundToInt

internal enum class CourseDragMode(val label: String) { Move("移动"), Start("改开始"), End("改结束") }

internal object ScheduleMoveRules {
    /** Top/bottom grips resize; the middle always moves, even on a one-period course. */
    fun gripMode(y: Float, height: Float, edge: Float): CourseDragMode {
        if (!y.isFinite() || !height.isFinite() || height <= 0 || y !in 0f..height) return CourseDragMode.Move
        val grip = edge.coerceAtLeast(0f).coerceAtMost(height / 4)
        return when { y < grip -> CourseDragMode.Start; y > height - grip -> CourseDragMode.End; else -> CourseDragMode.Move }
    }

    fun dragWithScroll(course: CourseDto, mode: CourseDragMode, dx: Float, dy: Float,
        scrollAtStart: Int, scrollNow: Int, dayWidth: Float, rowHeight: Float, maxPeriod: Int): CourseDto =
        drag(course, mode, dx, dy + scrollNow - scrollAtStart, dayWidth, rowHeight, maxPeriod)

    fun drag(course: CourseDto, mode: CourseDragMode, dx: Float, dy: Float, dayWidth: Float, rowHeight: Float, maxPeriod: Int): CourseDto {
        require(dayWidth > 0 && rowHeight > 0 && maxPeriod in 1..30)
        require(course.startPeriod in 1..course.endPeriod && course.endPeriod <= maxPeriod)
        val rows = (dy / rowHeight).roundToInt()
        return when (mode) {
            CourseDragMode.Move -> {
                val span = course.endPeriod - course.startPeriod
                val start = (course.startPeriod + rows).coerceIn(1, maxPeriod - span)
                course.copy(weekday = (course.weekday + (dx / dayWidth).roundToInt()).coerceIn(1, 7), startPeriod = start, endPeriod = start + span)
            }
            CourseDragMode.Start -> course.copy(startPeriod = (course.startPeriod + rows).coerceIn(1, course.endPeriod))
            CourseDragMode.End -> course.copy(endPeriod = (course.endPeriod + rows).coerceIn(course.startPeriod, maxPeriod))
        }
    }

    fun conflicts(target: CourseDto, courses: List<CourseDto>): Int = courses.count { other ->
        other.id != target.id && other.weekday == target.weekday && other.startPeriod <= target.endPeriod && other.endPeriod >= target.startPeriod &&
            (1..60).any { target.activeInWeek(it) && other.activeInWeek(it) }
    }

    /** Only connected overlap groups share lanes; an unrelated later course keeps the full day width. */
    fun groupedLanes(courses: List<CourseDto>): Map<String, Pair<Int, Int>> =
        courses.groupBy { it.weekday }.values.flatMap { day ->
            scheduleOverlapGroups(day).flatMap { group ->
                val positions = lanes(group.courses)
                val count = (positions.values.maxOrNull() ?: 0) + 1
                positions.map { (id, lane) -> id to (lane to count) }
            }
        }.toMap()

    /** 相交课程占独立列；只决定显示位置，绝不改真实节次。 */
    fun lanes(courses: List<CourseDto>): Map<String, Int> {
        val ends = mutableListOf<Int>()
        return courses.sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }, { it.id })).associate { course ->
            val lane = ends.indexOfFirst { it < course.startPeriod }.let { if (it < 0) ends.size else it }
            if (lane == ends.size) ends.add(course.endPeriod) else ends[lane] = course.endPeriod
            course.id to lane
        }
    }
}
