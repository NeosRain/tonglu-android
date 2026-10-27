package cn.tonglu.app.data

/** Editing one course keeps its identity, import origin and reminder references. */
internal fun editedCourse(before: CourseDto, request: CreateCourseRequest): CourseDto {
    require(request.name.isNotBlank() && request.name.trim().length<=40) { "课程名称需为1–40字" }
    require(request.weekday in 1..7 && request.startPeriod in 1..request.endPeriod && request.endPeriod<=30) { "课程节次无效" }
    require(request.startWeek in 1..request.endWeek && request.endWeek<=40) { "课程周次需在1–40周内" }
    require(request.weeks.all { it in request.startWeek..request.endWeek }) { "具体周次超出课程范围" }
    return before.copy(name=request.name.trim(),teacher=request.teacher?.trim()?.ifBlank { null },
        location=request.location?.trim()?.ifBlank { null },weekday=request.weekday,startPeriod=request.startPeriod,
        endPeriod=request.endPeriod,startWeek=request.startWeek,endWeek=request.endWeek,weeks=request.weeks.distinct().sorted())
}
