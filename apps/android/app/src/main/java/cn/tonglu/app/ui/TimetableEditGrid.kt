package cn.tonglu.app.ui

import cn.tonglu.app.ui.timetable.*
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
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
    onTapCourse: (CourseDto, ContainerOrigin?) -> Unit = { _, _ -> }, onDragging: (Boolean) -> Unit = {},
    onSave: (CourseDto, CourseDto, (Boolean, String) -> Unit) -> Unit) {
    var active by remember { mutableStateOf<CourseDto?>(null) }
    var proposed by remember { mutableStateOf<CourseDto?>(null) }
    var mode by remember { mutableStateOf(CourseDragMode.Move) }
    var pending by remember { mutableStateOf<Triple<CourseDto, CourseDto, ContainerOrigin?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportBottom by remember { mutableFloatStateOf(0f) }
    var scrollAtStart by remember { mutableIntStateOf(0) }
    var dragOrigin by remember { mutableStateOf<ContainerOrigin?>(null) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val latestDragging by rememberUpdatedState(onDragging)
    val latestTap by rememberUpdatedState(onTapCourse)
    val rows = maxOf(PeriodTable.maxPeriod(), allCourses.maxOfOrNull { it.endPeriod } ?: 8, 8).coerceAtMost(30)
    val segmentsByDay = remember(courses) { courses.groupBy { it.weekday }.mapValues { (_, items) -> timetableSegments(items) } }
    var overlapping by remember(courses) { mutableStateOf<CourseOverlapSelection?>(null) }
    val slots = PeriodTable.slots()
    val breaks = timetableBreaks(slots).filter { it.afterPeriod < rows }
    val rowHeight = 76.dp
    var pointerStartY by remember { mutableFloatStateOf(0f) }
    val rowPx = with(density) { rowHeight.toPx() }
    val breakPx = with(density) { 22.dp.toPx() }
    val insetPx = with(density) { 2.dp.toPx() }
    val edgePx = with(density) { 14.dp.toPx() }
    val scrollEdge = with(density) { 64.dp.toPx() }
    val scrollStep = with(density) { 8.dp.toPx() }
    DisposableEffect(Unit) { onDispose { latestDragging(false) } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val timeWidth = 48.dp
        val readableWidth = 30.dp * density.fontScale.coerceIn(1f, 1.6f)
        val dayWidth = ((maxWidth - timeWidth) / 7).coerceAtLeast(readableWidth)
        // 可编辑时翻周滑动让给拖拽；只有七天放不下、网格能横向滚动时才提示左右滑。
        val overflows = dayWidth > (maxWidth - timeWidth) / 7
        val horizontal = rememberScrollState()
        val dayPx = with(density) { dayWidth.toPx() }
        fun updateProposal() {
            active?.let {
                val scrollDelta = (scroll.value - scrollAtStart).toFloat()
                val flatDelta = timetableDragY(pointerStartY + dy + scrollDelta, rowPx, breaks, breakPx) -
                    timetableDragY(pointerStartY, rowPx, breaks, breakPx)
                // 原规则仍叠加滚动量，因此只传扣除滚动后的压平位移。
                proposed = ScheduleMoveRules.dragWithScroll(it, mode, dx, flatDelta - scrollDelta,
                    scrollAtStart, scroll.value, dayPx, rowPx, rows)
            }
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
            Text("点按查看 · 长按拖动 · 拖上下边缘改节次 · " + if (overflows) "左右滑动看全周" else "点上方周次换周",
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(timeWidth))
                (1..7).forEach { day ->
                    Column(Modifier.width(dayWidth).background(if (day == today) AppColors.Accent.copy(alpha = .06f) else Color.Transparent), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(listOf("一", "二", "三", "四", "五", "六", "日")[day - 1], fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1,
                            fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                            color = if (day == today) AppColors.Accent else AppColors.TextSecondary)
                        weekStartMillis?.let {
                            Text(ScheduleText.dateLabelAt(ScheduleText.plusDays(it, day - 1)).removeSuffix("日").replace("月", "/"),
                                fontSize = 9.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                                color = if (day == today) AppColors.Accent else AppColors.TextMuted)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).onGloballyPositioned {
                viewportTop = it.positionInRoot().y; viewportBottom = viewportTop + it.size.height
            }.verticalScroll(scroll)) {
                Box(Modifier.fillMaxWidth().height(courseGridHeight(1, rows, rowHeight.value, breaks, 22f).dp)) {
                    if (today != null) Box(Modifier.offset(x = timeWidth + dayWidth * (today - 1))
                        .width(dayWidth).fillMaxHeight().background(AppColors.Accent.copy(alpha = .06f)))
                    (1..rows).forEach { period ->
                            val slot = PeriodTable.slot(period)
                            Row(Modifier.offset(y = periodTop(period, rowHeight.value, breaks, 22f).dp).height(rowHeight)) {
                                Column(Modifier.width(timeWidth).fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    Text("$period", fontSize = 11.sp, lineHeight = 13.sp, color = AppColors.TextSecondary)
                                    if (slot != null) {
                                        Text(slot.start, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, color = AppColors.TextSecondary)
                                        Text(slot.end, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, color = AppColors.TextMuted)
                                    }
                                }
                                (1..7).forEach {
                                    Box(Modifier.width(dayWidth).fillMaxHeight().padding(2.dp)
                                        .clip(RoundedCornerShape(14.dp)).background(AppColors.Canvas.copy(alpha = .35f)))
                                }
                            }
                    }
                    breaks.forEach { rest ->
                        val covered = (1..7).filter { day ->
                            segmentsByDay[day].orEmpty().any {
                                it.startPeriod <= rest.afterPeriod && it.endPeriod > rest.afterPeriod
                            } || proposed?.let {
                                it.weekday == day && it.startPeriod <= rest.afterPeriod && it.endPeriod > rest.afterPeriod
                            } == true
                        }.map { day -> (day - 1) / 7f to day / 7f }
                        TimetableBreakPill(rest.label, Modifier.offset(x = timeWidth,
                            y = (periodTop(rest.afterPeriod, rowHeight.value, breaks, 22f) + rowHeight.value).dp)
                            .width(dayWidth * 7).maskTimetableBreak(covered))
                    }
                    for ((day, segments) in segmentsByDay) {
                        for (segment in segments) key(day, segment.startPeriod) {
                            val height = courseGridHeight(segment.startPeriod, segment.endPeriod, rowHeight.value, breaks, 22f).dp
                            TimetableSegmentRow(segment, Modifier.offset(x = timeWidth + dayWidth * (day - 1),
                                y = periodTop(segment.startPeriod, rowHeight.value, breaks, 22f).dp).width(dayWidth)
                                .padding(horizontal = courseColumnInsetDp.dp)) { course, cell, hiddenCount ->
                                val origin = rememberLaunchSource(course.id, courseTileCorner)
                                val glass = glassColors(courseColor(course.name))
                                val showStart = segment.startPeriod == course.startPeriod
                                val showEnd = segment.endPeriod == course.endPeriod
                                var rootY by remember { mutableFloatStateOf(0f) }
                                fun tap() {
                                    if (hiddenCount > 0) overlapping = CourseOverlapSelection(segment.courses, origin.capture())
                                    else latestTap(course, origin.capture())
                                }
                                val latestTapTile by rememberUpdatedState(::tap)
                                Box(cell.height(height).padding(vertical = 2.dp)
                                    .onGloballyPositioned { rootY = it.positionInRoot().y }
                                    .launchOrigin(origin).alpha(if (active?.id == course.id && proposed != course) .35f else 1f)
                                    .courseTile(glass)
                                    .semantics {
                                        contentDescription = "${course.name}，${ScheduleText.weekdayLabel(day)}，${segment.startPeriod}–${segment.endPeriod}节"
                                        if (!busy) onClick(if (hiddenCount > 0) "查看全部 ${hiddenCount + 1} 门重叠课程" else "查看课程") {
                                            latestTapTile()
                                            true
                                        }
                                    }
                                    .pointerInput(course, busy) {
                                        detectTapGestures(onTap = { if (!busy) latestTapTile() })
                                    }
                                    .pointerInput(course, segment.startPeriod, segment.endPeriod, dayPx, rowPx, breaks, busy) {
                                        if (!busy && course.startPeriod in 1..course.endPeriod && course.endPeriod <= rows)
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { point ->
                                                    dx = 0f; dy = 0f; scrollAtStart = scroll.value
                                                    pointerStartY = periodTop(segment.startPeriod, rowPx, breaks, breakPx) + insetPx + point.y
                                                    mode = when (val grip = ScheduleMoveRules.gripMode(point.y, size.height.toFloat(), edgePx)) {
                                                        CourseDragMode.Start -> if (showStart) grip else CourseDragMode.Move
                                                        CourseDragMode.End -> if (showEnd) grip else CourseDragMode.Move
                                                        CourseDragMode.Move -> grip
                                                    }
                                                    fingerY = rootY + point.y; dragOrigin = origin.capture()
                                                    active = course; proposed = course; latestDragging(true)
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                onDragCancel = { active = null; proposed = null; dragOrigin = null; latestDragging(false) },
                                                onDragEnd = {
                                                    val next = proposed
                                                    active = null; proposed = null; latestDragging(false)
                                                    if (next != null && next != course) { pending = Triple(course, next, dragOrigin); error = null }
                                                    dragOrigin = null
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume(); dx += amount.x; dy += amount.y; fingerY += amount.y; updateProposal()
                                                })
                                    }) {
                                    if (segment.courses.size > 1) OverlapCardContent(course, glass.ink, hiddenCount > 0)
                                    else CourseCardContent(course, foreground = glass.ink, singlePeriod = segment.startPeriod == segment.endPeriod)
                                    if (hiddenCount > 0) CourseOverlapBadge(hiddenCount,
                                        Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp))
                                    if (showStart && hiddenCount == 0) CourseGrip(glass.ink,
                                        Modifier.align(Alignment.TopCenter).padding(top = 3.dp))
                                    if (showEnd) CourseGrip(glass.ink, Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
                                }
                            }
                        }
                    }
                    proposed?.takeIf { it != active }?.let { course ->
                        val glass = glassColors(courseColor(course.name))
                        Box(Modifier.offset(x = timeWidth + dayWidth * (course.weekday - 1),
                            y = periodTop(course.startPeriod, rowHeight.value, breaks, 22f).dp).width(dayWidth)
                            .height(courseGridHeight(course.startPeriod, course.endPeriod, rowHeight.value, breaks, 22f).dp)
                            .padding(2.dp).zIndex(2f).shadow(8.dp, courseTileShape).courseTile(glass)) {
                            CourseCardContent(course, glass.ink)
                            CourseGrip(glass.ink, Modifier.align(Alignment.TopCenter).padding(top = 3.dp))
                            CourseGrip(glass.ink, Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
                        }
                    }
                    TimetableNowLine(slots, rowHeight, today, timeWidth, dayWidth)
                }
            }
        }
    }
    overlapping?.let { selection ->
        CourseOverlapDialog(selection, onSelect = latestTap, onDismiss = { overlapping = null })
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

@Composable
private fun CourseGrip(ink: Color, modifier: Modifier) {
    Box(modifier.width(16.dp).height(2.dp).background(ink.copy(alpha = .65f), RoundedCornerShape(2.dp)))
}
