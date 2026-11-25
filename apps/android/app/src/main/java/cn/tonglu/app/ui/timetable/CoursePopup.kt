package cn.tonglu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.*
import cn.tonglu.app.ui.ContainerOrigin
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.edgeSwipeBack
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberLaunchSource
import cn.tonglu.app.ui.rememberOverlayMotionImpl
import cn.tonglu.app.ui.books.CourseBooksLink

internal data class CourseSelection(val course: CourseDto, val origin: ContainerOrigin? = null)

@Composable
internal fun CoursePopup(origin: ContainerOrigin?, onDismiss: () -> Unit, dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val motion = rememberOverlayMotionImpl(
        onClose = onDismiss, predictive = OverlayMotion.enabledState, selfDrag = true,
        backEnabled = dismissEnabled, origin = origin, takePending = false,
    )
    val canDismiss = dismissEnabled && motion.canHandleBack
    val preview = OverlayMotion.enabledState && !motion.reducedMotion && canDismiss
    Dialog(onDismissRequest = { if (canDismiss) motion.requestClose() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().edgeSwipeBack(motion.edge, enabled = OverlayMotion.enabledState && canDismiss,
            backEnabled = canDismiss, preview = preview)) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = motion.shadeOpacity(includeReduced = true) }
                .background(Color.Black.copy(alpha = .32f)).clickable(enabled = canDismiss) {
                    if (!motion.blocksInput) motion.requestClose()
                })
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
                contentAlignment = Alignment.Center) {
                val background = AppColors.SurfaceOpaque
                Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 640.dp)
                    .overlayMotion(motion, fade = false, containerColor = background, destinationCorner = 24.dp,
                        dimBehind = false, handleGestures = false),
                    shape = RoundedCornerShape(24.dp), color = background, contentColor = AppColors.TextPrimary,
                    shadowElevation = 12.dp) {
                    Column(Modifier.fillMaxWidth()) { content(motion::requestClose) }
                }
            }
        }
    }
}

@Composable
internal fun CourseDetailDialog(selection: CourseSelection, relatedCourses: List<CourseDto> = listOf(selection.course), onDelete: ((CourseDto) -> Unit)? = null,
    onEdit: ((CourseDto)->Unit)? = null,
    onDismiss: () -> Unit) {
    val course = selection.course
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showClassNotes by remember { mutableStateOf(false) }
    val accent = courseColor(course.name)
    CoursePopup(selection.origin, onDismiss = { onDismiss(); afterClose?.invoke() },
        dismissEnabled = !showClassNotes) { close ->
        Column(Modifier.fillMaxWidth().background(accent).padding(22.dp)) {
            Text(course.name, color = AppColors.OnAccent, fontSize = 22.sp, lineHeight = 29.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("${ScheduleText.weekdayLabel(course.weekday)} · 第 ${course.startPeriod}–${course.endPeriod} 节",
                color = AppColors.OnAccent, fontSize = 14.sp, lineHeight = 22.sp,
                modifier = Modifier.padding(top = 10.dp))
            Text(course.timeText(), color = AppColors.OnAccent, fontSize = 16.sp, lineHeight = 24.sp)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            CourseDetailField("上课地点", course.location?.takeIf(String::isNotBlank) ?: "未填写地点")
            CourseDetailField("任课老师", course.teacher?.takeIf(String::isNotBlank) ?: "未填写老师")
            CourseDetailField("上课周次", if (course.weeks.isNotEmpty()) WeekSpec.describe(course.weeks)
                else "${course.startWeek}–${course.endWeek} 周")
            CourseBooksLink(course.name)
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) TextButton(onClick = { afterClose = { onDelete(course) }; close() }) {
                Text("删除课程", color = AppColors.Danger)
            }
            val origin = rememberLaunchSource(course.id, 20.dp)
            TextButton(onClick = { origin.launch { showClassNotes = true } }, modifier = Modifier.launchOrigin(origin)) {
                Text("课堂笔记", color = AppColors.Accent)
            }
            if (onEdit != null) TextButton(onClick={afterClose={onEdit(course)};close()}) { Text("编辑",color=AppColors.Accent) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = close) { Text("完成", color = AppColors.Accent) }
        }
    }
    if (showClassNotes) ClassNotesDialog(course, relatedCourses) { showClassNotes = false }
}

@Composable
private fun CourseDetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = AppColors.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        Text(value, color = AppColors.TextPrimary, fontSize = 16.sp, lineHeight = 24.sp)
    }
}


internal data class CourseOverlapSelection(val courses: List<CourseDto>, val origin: ContainerOrigin?)

/** The compact overlap chooser uses the same motion, colors and detail entry as a single course. */
@Composable
internal fun CourseOverlapDialog(selection: CourseOverlapSelection,
    onSelect: (CourseDto, ContainerOrigin?) -> Unit, onDismiss: () -> Unit) {
    CoursePopup(selection.origin, onDismiss = onDismiss) { close ->
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Text("同一时段 · ${selection.courses.size} 门课", fontSize = 20.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text("课程时间有重叠，请核对学校安排。点选课程查看完整详情。",
                fontSize = 14.sp, lineHeight = 21.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp))
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            selection.courses.forEach { course ->
                val origin = rememberLaunchSource(course.id, 18.dp)
                Surface(Modifier.fillMaxWidth().launchOrigin(origin)
                    .clickable { onSelect(course, origin.capture()) },
                    shape = RoundedCornerShape(18.dp), color = courseColor(course.name),
                    contentColor = AppColors.OnAccent) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(course.name, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text("${course.startPeriod}–${course.endPeriod} 节 · ${course.weekLabel()}", fontSize = 13.sp, lineHeight = 20.sp)
                        Text(course.location?.takeIf(String::isNotBlank) ?: "未填写地点", fontSize = 13.sp, lineHeight = 20.sp)
                        Text("查看详情 ›", fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = close) { Text("关闭", color = AppColors.Accent) }
        }
    }
}
