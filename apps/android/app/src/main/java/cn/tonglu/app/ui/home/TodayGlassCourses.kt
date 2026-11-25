package cn.tonglu.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.PeriodTable
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.ContainerOrigin
import cn.tonglu.app.ui.CourseOverlapDialog
import cn.tonglu.app.ui.CourseOverlapSelection
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource
import cn.tonglu.app.ui.shortPlace
import cn.tonglu.app.ui.timetable.*

internal fun courseTiming(course: CourseDto, now: Int): TodayCourseTiming = todayCourseTiming(
    PeriodTable.slot(course.startPeriod)?.let { PeriodTable.parseMinutes(it.start) },
    PeriodTable.slot(course.endPeriod)?.let { PeriodTable.parseMinutes(it.end) }, now,
)

/** 全天列表保持稳定，只有首课摘要和每行状态各自订阅分钟。 */
@Composable
internal fun TodayGlassCourses(courses: List<CourseDto>, onTap: (CourseDto, ContainerOrigin?) -> Unit,
    onLongPress: (CourseDto) -> Unit, forceFinished: Boolean = false) {
    val segments = remember(courses) { timetableSegments(courses) }
    var overlapping by remember(courses) { mutableStateOf<CourseOverlapSelection?>(null) }
    val onChoose: (CourseOverlapSelection) -> Unit = { overlapping = it }
    if (!forceFinished) NextGlassCourse(courses, segments, onTap, onLongPress, onChoose)
    val grouped = segments.groupBy { segment ->
        val start = PeriodTable.slot(segment.startPeriod)?.let { PeriodTable.parseMinutes(it.start) }
        todayCourseTiming(start, null, 0).group
    }
    TodayCourseGroup.entries.forEach { group ->
        val items = grouped[group].orEmpty()
        if (items.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(group.label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary,
                modifier = Modifier.background(AppColors.Accent.copy(alpha = .09f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp))
            items.forEach { segment -> key(segment.startPeriod) {
                val course = segment.courses.singleOrNull()
                if (course != null && course.startPeriod == segment.startPeriod && course.endPeriod == segment.endPeriod) {
                    TodayGlassCourseRow(course, onTap, onLongPress, forceFinished)
                } else {
                    TodayCourseSegment(segment, onTap, onLongPress, onChoose, forceFinished)
                }
            } }
        }
    }
    overlapping?.let { selection ->
        CourseOverlapDialog(selection, onSelect = onTap, onDismiss = { overlapping = null })
    }
}

@Composable
private fun NextGlassCourse(courses: List<CourseDto>, segments: List<TimetableSegment>,
    onTap: (CourseDto, ContainerOrigin?) -> Unit, onLongPress: (CourseDto) -> Unit,
    onChoose: (CourseOverlapSelection) -> Unit) {
    val now by rememberTimetableMinute()
    val course = courses.sortedBy { it.startPeriod }.firstOrNull {
        courseTiming(it, now).state == TodayCourseState.Ongoing
    } ?: courses.sortedBy { it.startPeriod }.firstOrNull { courseTiming(it, now).state == TodayCourseState.Upcoming }
    if (course == null) return
    val timing = courseTiming(course, now)
    val label = if (timing.state == TodayCourseState.Ongoing) "正在上课" else "下节课"
    val currentSegment = segments.lastOrNull {
        course in it.courses && PeriodTable.slot(it.startPeriod)?.let { slot -> PeriodTable.parseMinutes(slot.start) <= now } == true
    } ?: segments.firstOrNull { course in it.courses }
    if (currentSegment != null && currentSegment.courses.size > 1) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            Text(timing.countdown, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        TodayCourseSegment(currentSegment, onTap, onLongPress, onChoose, forceFinished = false)
        return
    }
    val origin = rememberLaunchSource(course.id, 18.dp)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)
        .background(AppColors.Accent.copy(alpha = .08f), RoundedCornerShape(18.dp))
        .launchOrigin(origin)
        .pointerInput(course) { detectTapGestures(onTap = { onTap(course, origin.capture()) }, onLongPress = { onLongPress(course) }) }
        .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            Text(course.name, color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(coursePeople(course), color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(timing.countdown, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 116.dp))
    }
}

@Composable
private fun TodayCourseSegment(segment: TimetableSegment, onTap: (CourseDto, ContainerOrigin?) -> Unit,
    onLongPress: (CourseDto) -> Unit, onChoose: (CourseOverlapSelection) -> Unit, forceFinished: Boolean) {
    val periods = if (segment.startPeriod == segment.endPeriod) "第 ${segment.startPeriod} 节"
        else "第 ${segment.startPeriod}–${segment.endPeriod} 节"
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(periods, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        val time = PeriodTable.timeText(segment.startPeriod, segment.endPeriod)
        if (time.isNotBlank()) Text(time, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
    val height = (20f + 64f * LocalDensity.current.fontScale).dp
    TimetableSegmentRow(segment, Modifier.fillMaxWidth().height(height)) { course, cell, hiddenCount ->
        ReadOnlyCourseTile(course, hiddenCount, cell.fillMaxHeight(),
            onTap = { origin ->
                if (hiddenCount > 0) onChoose(CourseOverlapSelection(segment.courses, origin))
                else onTap(course, origin)
            },
            onLongPress = if (hiddenCount == 0) ({ onLongPress(course) }) else null,
        ) { ink ->
            Column(
                Modifier.fillMaxWidth().padding(
                    start = 8.dp, end = 8.dp, top = if (hiddenCount > 0) 18.dp else 8.dp, bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(course.name, color = ink, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                shortPlace(course.location)?.let {
                    Text(it, color = ink, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TodayGlassStatus(course, forceFinished, color = ink)
            }
        }
    }
}

private fun coursePeople(course: CourseDto): String = listOfNotNull(shortPlace(course.location),
    course.teacher?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "教室与老师待补充" }

@Composable
private fun TodayGlassCourseRow(course: CourseDto, onTap: (CourseDto, ContainerOrigin?) -> Unit,
    onLongPress: (CourseDto) -> Unit, forceFinished: Boolean) {
    val origin = rememberLaunchSource(course.id)
    Row(Modifier.fillMaxWidth()
        .launchOrigin(origin)
        .pointerInput(course) { detectTapGestures(onTap = { onTap(course, origin.capture()) }, onLongPress = { onLongPress(course) }) }
        .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        // 完成透明度在绘制层读取，正文不因分钟变化重新排版。
        val now = rememberTimetableMinute()
        Column(Modifier.weight(1f).graphicsFinished(course, now, forceFinished)) {
            Text(course.name, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("第 ${course.startPeriod}–${course.endPeriod} 节 · ${coursePeople(course)}",
                color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.widthIn(max = 120.dp).graphicsFinished(course, now, forceFinished), horizontalAlignment = Alignment.End) {
            Text(course.timeText().replace("-", " – ").ifBlank { "时间待定" }, color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall, maxLines = 1)
            TodayGlassStatus(course, forceFinished)
        }
    }
}

private fun Modifier.graphicsFinished(course: CourseDto, now: State<Int>, forceFinished: Boolean): Modifier =
    this.then(Modifier.graphicsLayer {
        alpha = if (forceFinished || courseTiming(course, now.value).state == TodayCourseState.Finished) .45f else 1f
    })

@Composable
private fun TodayGlassStatus(course: CourseDto, forceFinished: Boolean, color: Color = AppColors.TextSecondary) {
    val now by rememberTimetableMinute()
    Text(if (forceFinished) TodayCourseState.Finished.label else courseTiming(course, now).state.label,
        style = MaterialTheme.typography.labelSmall, color = color)
}
