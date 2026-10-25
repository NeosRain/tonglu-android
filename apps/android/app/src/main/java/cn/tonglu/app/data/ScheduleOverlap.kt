package cn.tonglu.app.data

/** 已经过周次过滤的同一天课程，按相交节次聚合；不去重课程或改动原始数据。 */
internal data class ScheduleOverlapGroup(val courses: List<CourseDto>) {
    val startPeriod: Int get() = courses.minOf { it.startPeriod }
    val endPeriod: Int get() = courses.maxOf { it.endPeriod }
}

internal fun scheduleOverlapGroups(courses: List<CourseDto>): List<ScheduleOverlapGroup> {
    val groups = mutableListOf<ScheduleOverlapGroup>()
    var pending = mutableListOf<CourseDto>()
    var end = 0
    for (course in courses.sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }, { it.id }))) {
        if (pending.isNotEmpty() && course.startPeriod > end) {
            groups += ScheduleOverlapGroup(pending.toList())
            pending = mutableListOf()
        }
        pending += course
        end = pending.maxOf { it.endPeriod }
    }
    if (pending.isNotEmpty()) groups += ScheduleOverlapGroup(pending.toList())
    return groups
}
