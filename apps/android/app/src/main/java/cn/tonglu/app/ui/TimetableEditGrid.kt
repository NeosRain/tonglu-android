package cn.tonglu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cn.tonglu.app.data.*

/** 编辑手势只在显式模式启用。长按接管拖动，普通滑动仍可平移整张表。 */
@Composable
internal fun TimetableEditGrid(courses: List<CourseDto>, allCourses: List<CourseDto>, modifier: Modifier = Modifier,
    onSave: (CourseDto, CourseDto, (Boolean, String) -> Unit) -> Unit) {
    var modeName by rememberSaveable { mutableStateOf(CourseDragMode.Move.name) }
    val mode = CourseDragMode.valueOf(modeName)
    var active by remember { mutableStateOf<String?>(null) }
    var proposed by remember { mutableStateOf<CourseDto?>(null) }
    var pending by remember { mutableStateOf<Pair<CourseDto, CourseDto>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val rows = maxOf(PeriodTable.maxPeriod(), allCourses.maxOfOrNull { it.endPeriod } ?: 8, 8).coerceAtMost(30)
    val lanes = remember(courses) { courses.groupBy { it.weekday }.mapValues { ScheduleMoveRules.lanes(it.value) } }
    val laneCount = lanes.values.maxOfOrNull { (it.values.maxOrNull() ?: 0) + 1 } ?: 1
    val dayWidth = maxOf(112, laneCount * 84).dp
    val rowHeight = 76.dp
    val dayPx = with(density) { dayWidth.toPx() }
    val rowPx = with(density) { rowHeight.toPx() }
    Column(modifier) {
        Text("长按课程后拖动，松手确认。左右、上下滑动可查看整张表。", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CourseDragMode.entries.forEach { option -> FilterChip(selected = mode == option, enabled = !busy && active == null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.Accent.copy(alpha = .15f), selectedLabelColor = AppColors.Accent, labelColor = AppColors.TextSecondary),
                onClick = { modeName = option.name }, label = { Text(option.label) }) }
        }
        if (courses.isEmpty()) Text("当前周没有课程，请选择有课的周次", modifier = Modifier.padding(16.dp))
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
            Column(Modifier.width(42.dp + dayWidth * 7)) {
                Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(42.dp))
                    (1..7).forEach { day -> Box(Modifier.width(dayWidth), contentAlignment = Alignment.Center) { Text(ScheduleText.weekdayLabel(day)) } }
                }
                Box(Modifier.height(rowHeight * rows).fillMaxWidth()) {
                    Column {
                        (1..rows).forEach { period -> Row(Modifier.height(rowHeight)) {
                            Box(Modifier.width(42.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) { Text("$period", color = AppColors.TextSecondary) }
                            (1..7).forEach { Box(Modifier.width(dayWidth).fillMaxHeight().padding(1.dp).background(AppColors.ChipIdle.copy(alpha = .4f))) }
                        } }
                    }
                    courses.forEach { course -> key(course.id) {
                        var dx by remember { mutableFloatStateOf(0f) }
                        var dy by remember { mutableFloatStateOf(0f) }
                        val shown = if (active == course.id) proposed ?: course else course
                        val lane = lanes[course.weekday]?.get(course.id) ?: 0
                        val count = (lanes[course.weekday]?.values?.maxOrNull() ?: 0) + 1
                        val width = dayWidth / count
                        Column(Modifier.offset(x = 42.dp + dayWidth * (shown.weekday - 1) + width * lane, y = rowHeight * (shown.startPeriod - 1))
                            .width(width).height(rowHeight * (shown.endPeriod - shown.startPeriod + 1)).padding(3.dp)
                            .zIndex(if (active == course.id) 1f else 0f)
                            .clip(RoundedCornerShape(12.dp)).background(courseColor(course.name).copy(alpha = if (active == course.id) .8f else 1f))
                            .semantics { contentDescription = "调整${course.name}，${ScheduleText.weekdayLabel(shown.weekday)}，${shown.startPeriod}-${shown.endPeriod}节" }
                            .pointerInput(course, mode, dayPx, rowPx, busy) {
                                if (!busy && course.startPeriod in 1..course.endPeriod && course.endPeriod <= rows) detectDragGesturesAfterLongPress(
                                    onDragStart = { dx = 0f; dy = 0f; active = course.id; proposed = course; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDragCancel = { active = null; proposed = null },
                                    onDragEnd = {
                                        val next = proposed
                                        active = null; proposed = null
                                        if (next != null && next != course) { pending = course to next; error = null }
                                    },
                                    onDrag = { change, amount -> change.consume(); dx += amount.x; dy += amount.y
                                        proposed = ScheduleMoveRules.drag(course, mode, dx, dy, dayPx, rowPx, rows) },
                                )
                            }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(course.name, color = AppColors.OnAccent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            Text("${shown.startPeriod}–${shown.endPeriod}节", color = AppColors.OnAccent, style = MaterialTheme.typography.labelSmall)
                            course.location?.let { Text(it, color = AppColors.OnAccent, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                        }
                    } }
                }
            }
        }
    }
    pending?.let { (before, after) ->
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text("确认调整课程") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(before.name, fontWeight = FontWeight.SemiBold)
                Text("${ScheduleText.weekdayLabel(before.weekday)} ${before.startPeriod}–${before.endPeriod}节 → ${ScheduleText.weekdayLabel(after.weekday)} ${after.startPeriod}–${after.endPeriod}节")
                Text("${PeriodTable.slot(after.startPeriod)?.start ?: "未设时间"}–${PeriodTable.slot(after.endPeriod)?.end ?: "未设时间"} · ${after.weekLabel()}")
                Text("调整该课程的全部上课周次；只修改同路中的课表，不修改学校教务。")
                val conflicts = ScheduleMoveRules.conflicts(after, allCourses)
                if (conflicts > 0) Text("与 $conflicts 条课程时间重叠，保存后仍保留全部课程。", color = AppColors.Danger)
                error?.let { Text(it, color = AppColors.Danger) }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; onSave(before, after) { ok, message -> busy = false; if (ok) pending = null else error = message } }) { Text(if (busy) "保存中…" else "保存调整") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") } })
    }
}
