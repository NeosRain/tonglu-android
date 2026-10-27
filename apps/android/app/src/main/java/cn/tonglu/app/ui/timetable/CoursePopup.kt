package cn.tonglu.app.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.tonglu.app.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

internal data class CourseSelection(val course: CourseDto, val origin: Rect = Rect.Zero)

internal data class CoursePopupTransform(val scaleX: Float, val scaleY: Float, val x: Float, val y: Float)
internal fun coursePopupTransform(source: Rect, target: Rect, progress: Float): CoursePopupTransform {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return CoursePopupTransform(1f, 1f, 0f, 0f)
    if (source.width <= 0f || source.height <= 0f || target.width <= 0f || target.height <= 0f)
        return CoursePopupTransform(.94f + .06f * p, .94f + .06f * p, 0f, 0f)
    return CoursePopupTransform(source.width / target.width * (1f - p) + p,
        source.height / target.height * (1f - p) + p,
        (source.center.x - target.center.x) * (1f - p),
        (source.center.y - target.center.y) * (1f - p))
}

/** One motion for course details and move confirmation. All exit paths finish the reverse animation. */
@Composable
internal fun CoursePopup(origin: Rect, onDismiss: () -> Unit, dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val motion = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    var closing by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf(Rect.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    fun close() {
        if (closing) return
        closing = true
        OverlayDismissGuard.arm()
        scope.launch {
            motion.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            latestDismiss()
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { cardSize.width > 0 && area.width > 0f }.first { it }
        if (!closing) motion.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
    }
    Dialog(onDismissRequest = { if (dismissEnabled) close() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let {
                it.setWindowAnimations(0)
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        ThemeSystemBars()
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = motion.value }
                .background(Color.Black.copy(alpha = .32f)).clickable(enabled = dismissEnabled) { close() })
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)
                .onGloballyPositioned { c ->
                    val at = c.positionInWindow()
                    area = Rect(at.x, at.y, at.x + c.size.width, at.y + c.size.height)
                }, contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 640.dp)
                    .onSizeChanged { cardSize = it }.graphicsLayer {
                        val target = Rect(area.center.x - cardSize.width / 2f, area.center.y - cardSize.height / 2f,
                            area.center.x + cardSize.width / 2f, area.center.y + cardSize.height / 2f)
                        val transform = coursePopupTransform(origin, target, motion.value)
                        scaleX = transform.scaleX; scaleY = transform.scaleY
                        translationX = transform.x; translationY = transform.y
                        alpha = motion.value
                    }, shape = RoundedCornerShape(24.dp), color = AppColors.SurfaceOpaque, contentColor = AppColors.TextPrimary,
                    shadowElevation = 12.dp) {
                    Column(Modifier.fillMaxWidth()) { content { close() } }
                }
            }
        }
    }
}

@Composable
internal fun CourseDetailDialog(selection: CourseSelection, onDelete: ((CourseDto) -> Unit)? = null,
    onEdit: ((CourseDto)->Unit)? = null,
    onDismiss: () -> Unit) {
    val course = selection.course
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    val accent = courseColor(course.name)
    CoursePopup(selection.origin, onDismiss = { onDismiss(); afterClose?.invoke() }) { close ->
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
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) TextButton(onClick = { afterClose = { onDelete(course) }; close() }) {
                Text("删除课程", color = AppColors.Danger)
            }
            if (onEdit != null) TextButton(onClick={afterClose={onEdit(course)};close()}) { Text("编辑",color=AppColors.Accent) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = close) { Text("完成", color = AppColors.Accent) }
        }
    }
}

@Composable
private fun CourseDetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = AppColors.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        Text(value, color = AppColors.TextPrimary, fontSize = 16.sp, lineHeight = 24.sp)
    }
}


internal data class CourseOverlapSelection(val courses: List<CourseDto>, val origin: Rect)

/** The compact overlap chooser uses the same motion, colors and detail entry as a single course. */
@Composable
internal fun CourseOverlapDialog(selection: CourseOverlapSelection,
    onSelect: (CourseDto, Rect) -> Unit, onDismiss: () -> Unit) {
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    CoursePopup(selection.origin, onDismiss = { onDismiss(); afterClose?.invoke() }) { close ->
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
                var bounds by remember(course.id) { mutableStateOf(Rect.Zero) }
                Surface(Modifier.fillMaxWidth().onGloballyPositioned {
                    val at = it.positionInWindow()
                    bounds = Rect(at.x, at.y, at.x + it.size.width, at.y + it.size.height)
                }.clickable { afterClose = { onSelect(course, bounds) }; close() },
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
