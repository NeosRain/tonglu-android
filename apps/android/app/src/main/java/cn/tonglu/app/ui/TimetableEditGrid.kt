package cn.tonglu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cn.tonglu.app.data.*
import kotlinx.coroutines.delay

/** The normal expanded timetable is editable: middle moves, top/bottom grips change its duration. */
@Composable
internal fun TimetableEditGrid(courses: List<CourseDto>, allCourses: List<CourseDto>, modifier: Modifier = Modifier,
    weekStartMillis: Long? = null, today: Int? = null,
    onTapCourse: (CourseDto, Rect) -> Unit = { _, _ -> }, onDragging: (Boolean) -> Unit = {},
    onSave: (CourseDto, CourseDto, (Boolean, String) -> Unit) -> Unit) {
    var active by remember { mutableStateOf<CourseDto?>(null) }
    var proposed by remember { mutableStateOf<CourseDto?>(null) }
    var mode by remember { mutableStateOf(CourseDragMode.Move) }
    var pending by remember { mutableStateOf<Triple<CourseDto, CourseDto, Rect>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportBottom by remember { mutableFloatStateOf(0f) }
    var scrollAtStart by remember { mutableIntStateOf(0) }
    var sourceBounds by remember { mutableStateOf(Rect.Zero) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val latestDragging by rememberUpdatedState(onDragging)
    val latestTap by rememberUpdatedState(onTapCourse)
    val rows = maxOf(PeriodTable.maxPeriod(), allCourses.maxOfOrNull { it.endPeriod } ?: 8, 8).coerceAtMost(30)
    val lanes = remember(courses) { ScheduleMoveRules.groupedLanes(courses) }
    val rowHeight = 76.dp
    val rowPx = with(density) { rowHeight.toPx() }
    val edgePx = with(density) { 14.dp.toPx() }
    val scrollEdge = with(density) { 64.dp.toPx() }
    val scrollStep = with(density) { 8.dp.toPx() }
    DisposableEffect(Unit) { onDispose { latestDragging(false) } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val timeWidth = 38.dp
        // Do not squeeze overlapping classes into unreadable strips. The header and grid scroll together.
        val laneCount = lanes.values.maxOfOrNull { it.second } ?: 1
        val readableWidth = 30.dp * laneCount * density.fontScale.coerceIn(1f, 1.6f)
        val dayWidth = ((maxWidth - timeWidth) / 7).coerceAtLeast(readableWidth)
        val horizontal = rememberScrollState()
        val dayPx = with(density) { dayWidth.toPx() }
        fun updateProposal() {
            active?.let { proposed = ScheduleMoveRules.dragWithScroll(it, mode, dx, dy,
                scrollAtStart, scroll.value, dayPx, rowPx, rows) }
        }
        LaunchedEffect(active?.id) {
            while (active != null) {
                val speed = when {
                    fingerY < viewportTop + scrollEdge -> -scrollStep
                    fingerY > viewportBottom - scrollEdge -> scrollStep
                    else -> 0f
                }
                if (speed != 0f) { scroll.scrollBy(speed); updateProposal() }
                delay(16)
            }
        }
        Column(Modifier.fillMaxHeight().horizontalScroll(horizontal, enabled = active == null).width(timeWidth + dayWidth * 7)) {
            Text("点按查看 · 长按拖动 · 拖上下边缘改节次 · 左右滑动看全周",
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(timeWidth))
                (1..7).forEach { day ->
                    Column(Modifier.width(dayWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(listOf("一", "二", "三", "四", "五", "六", "日")[day - 1], fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1,
                            color = if (day == today) AppColors.Accent else AppColors.TextSecondary)
                        weekStartMillis?.let {
                            Text(ScheduleText.dateLabelAt(it + (day - 1) * 86400000L).removeSuffix("日").replace("月", "/"),
                                fontSize = 9.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = AppColors.TextMuted)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).onGloballyPositioned {
                viewportTop = it.positionInRoot().y; viewportBottom = viewportTop + it.size.height
            }.verticalScroll(scroll)) {
                Box(Modifier.fillMaxWidth().height(rowHeight * rows)) {
                    Column {
                        (1..rows).forEach { period ->
                            Row(Modifier.height(rowHeight)) {
                                Column(Modifier.width(timeWidth).fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    Text("$period", fontSize = 11.sp, color = AppColors.TextSecondary)
                                    Text(PeriodTable.slot(period)?.start.orEmpty(), fontSize = 8.sp, color = AppColors.TextMuted)
                                }
                                (1..7).forEach {
                                    Box(Modifier.width(dayWidth).fillMaxHeight().padding(1.5.dp)
                                        .clip(RoundedCornerShape(12.dp)).background(AppColors.Canvas))
                                }
                            }
                        }
                    }
                    courses.forEach { course -> key(course.id) {
                        var bounds by remember { mutableStateOf(Rect.Zero) }
                        var rootY by remember { mutableFloatStateOf(0f) }
                        val shown = if (active?.id == course.id) proposed ?: course else course
                        val placement = lanes[course.id] ?: (0 to 1)
                        val lane = placement.first
                        val count = placement.second
                        val width = dayWidth / count
                        Box(Modifier.offset(x = timeWidth + dayWidth * (shown.weekday - 1) + width * lane,
                            y = rowHeight * (shown.startPeriod - 1)).width(width)
                            .height(rowHeight * (shown.endPeriod - shown.startPeriod + 1)).padding(1.5.dp)
                            .zIndex(if (active?.id == course.id) 2f else 0f)
                            .onGloballyPositioned {
                                val at = it.positionInWindow()
                                bounds = Rect(at.x, at.y, at.x + it.size.width, at.y + it.size.height)
                                rootY = it.positionInRoot().y
                            }.shadow(if (active?.id == course.id) 8.dp else 0.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp)).background(courseColor(course.name))
                            .semantics { contentDescription = "${course.name}，${ScheduleText.weekdayLabel(shown.weekday)}，${shown.startPeriod}–${shown.endPeriod}节；长按中间移动，上下边缘调整时长" }
                            .pointerInput(course, busy) { detectTapGestures(onTap = { if (!busy) latestTap(course, bounds) }) }
                            .pointerInput(course, dayPx, rowPx, busy) {
                                if (!busy && course.startPeriod in 1..course.endPeriod && course.endPeriod <= rows)
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { point ->
                                            dx = 0f; dy = 0f; scrollAtStart = scroll.value
                                            mode = ScheduleMoveRules.gripMode(point.y, size.height.toFloat(), edgePx)
                                            fingerY = rootY + point.y; sourceBounds = bounds
                                            active = course; proposed = course; latestDragging(true)
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragCancel = { active = null; proposed = null; latestDragging(false) },
                                        onDragEnd = {
                                            val next = proposed
                                            active = null; proposed = null; latestDragging(false)
                                            if (next != null && next != course) { pending = Triple(course, next, sourceBounds); error = null }
                                        },
                                        onDrag = { change, amount ->
                                            change.consume(); dx += amount.x; dy += amount.y; fingerY += amount.y; updateProposal()
                                        })
                            }) {
                            CourseCardContent(course, shown)
                            Box(Modifier.align(Alignment.TopCenter).padding(top = 3.dp).width(16.dp).height(2.dp)
                                .background(AppColors.OnAccent.copy(alpha = .65f), RoundedCornerShape(2.dp)))
                            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).width(16.dp).height(2.dp)
                                .background(AppColors.OnAccent.copy(alpha = .65f), RoundedCornerShape(2.dp)))
                        }
                    } }
                }
            }
        }
    }
    pending?.let { (before, after, origin) ->
        CoursePopup(origin, onDismiss = { pending = null; busy = false; error = null }, dismissEnabled = !busy) { close ->
            Column(Modifier.fillMaxWidth().background(courseColor(before.name)).padding(20.dp)) {
                Text(before.name, color = AppColors.OnAccent, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text("确认调整课程", color = AppColors.OnAccent, modifier = Modifier.padding(top = 8.dp))
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("原来：${ScheduleText.weekdayLabel(before.weekday)} · 第 ${before.startPeriod}–${before.endPeriod} 节")
                Text("改为：${ScheduleText.weekdayLabel(after.weekday)} · 第 ${after.startPeriod}–${after.endPeriod} 节", fontWeight = FontWeight.SemiBold)
                Text("${PeriodTable.slot(after.startPeriod)?.start ?: "未设时间"}–${PeriodTable.slot(after.endPeriod)?.end ?: "未设时间"} · ${after.weekLabel()}")
                Text("应用于该课程的全部上课周次，只修改本机课表，不修改学校教务。", color = AppColors.TextSecondary)
                val conflicts = ScheduleMoveRules.conflicts(after, allCourses)
                if (conflicts > 0) Text("与 $conflicts 条课程时间重叠，仍会保留全部课程。", color = AppColors.Danger)
                error?.let { Text(it, color = AppColors.Danger) }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !busy, onClick = close) { Text("取消") }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    onSave(before, after) { ok, message ->
                        if (ok) close() else { busy = false; error = message }
                    }
                }) { Text(if (busy) "保存中…" else "保存调整") }
            }
        }
    }
}
