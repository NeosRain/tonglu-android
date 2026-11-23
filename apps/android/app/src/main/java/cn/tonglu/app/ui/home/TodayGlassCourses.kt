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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.PeriodTable
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.ContainerOrigin
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
    if (!forceFinished) NextGlassCourse(courses, onTap, onLongPress)
    val grouped = courses.sortedBy { it.startPeriod }.groupBy { courseTiming(it, 0).group }
    TodayCourseGroup.entries.forEach { group ->
        val items = grouped[group].orEmpty()
        if (items.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(group.label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary,
                modifier = Modifier.background(AppColors.Accent.copy(alpha = .09f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp))
            items.forEach { course -> key(course.id) {
                TodayGlassCourseRow(course, onTap, onLongPress, forceFinished)
            } }
        }
    }
}

@Composable
private fun NextGlassCourse(courses: List<CourseDto>, onTap: (CourseDto, ContainerOrigin?) -> Unit, onLongPress: (CourseDto) -> Unit) {
    val now by rememberTimetableMinute()
    val course = courses.sortedBy { it.startPeriod }.firstOrNull {
        courseTiming(it, now).state == TodayCourseState.Ongoing
    } ?: courses.sortedBy { it.startPeriod }.firstOrNull { courseTiming(it, now).state == TodayCourseState.Upcoming }
    if (course == null) return
    val timing = courseTiming(course, now)
    val origin = rememberLaunchSource(course.id, 18.dp)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)
        .background(AppColors.Accent.copy(alpha = .08f), RoundedCornerShape(18.dp))
        .launchOrigin(origin)
        .pointerInput(course) { detectTapGestures(onTap = { onTap(course, origin.capture()) }, onLongPress = { onLongPress(course) }) }
        .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(if (timing.state == TodayCourseState.Ongoing) "正在上课" else "下节课",
                color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            Text(course.name, color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(coursePeople(course), color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(timing.countdown, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 116.dp))
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
private fun TodayGlassStatus(course: CourseDto, forceFinished: Boolean) {
    val now by rememberTimetableMinute()
    Text(if (forceFinished) TodayCourseState.Finished.label else courseTiming(course, now).state.label,
        style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
}
