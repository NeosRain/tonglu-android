package cn.tonglu.app.ui.timetable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.PeriodSlot
import cn.tonglu.app.data.ScheduleText
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme
import cn.tonglu.app.ui.theme.ThemeCanvas
import kotlinx.coroutines.delay

/** 状态只在使用它的子组件读取，避免每分钟重组整张课程网格。 */
@Composable
internal fun rememberTimetableMinute(): State<Int> = produceState(ScheduleText.nowMinutes()) {
    while (true) {
        delay(60_000L - System.currentTimeMillis() % 60_000L)
        value = ScheduleText.nowMinutes()
    }
}

@Composable
internal fun glassColors(color: Color): CourseGlassColors = courseGlassColors(
    color, AppTheme.darkState, AppColors.CanvasOpaque, AppColors.SurfaceOpaque, ThemeCanvas.wallpaperActive,
)

/** 两层背景合并成同一透明色，仍按先中性垫底、后课程着色的顺序合成。 */
internal fun Modifier.courseGlass(colors: CourseGlassColors): Modifier =
    background(colors.tint.compositeOver(colors.underlay))

@Composable
internal fun TimetableBreakPill(label: String, modifier: Modifier = Modifier) {
    Box(modifier.height(22.dp).padding(horizontal = 2.dp, vertical = 2.dp)
        .background(AppColors.Surface.copy(alpha = .55f), RoundedCornerShape(50)),
        contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
    }
}

/** draw 阶段读取分钟，课程块不会参与这条线的刷新。 */
@Composable
internal fun TimetableNowLine(slots: List<PeriodSlot>, rowHeight: Dp, today: Int?,
    timeWidth: Dp, dayWidth: Dp? = null, modifier: Modifier = Modifier) {
    if (today == null) return
    val minute = rememberTimetableMinute()
    val accent = AppColors.Accent
    Canvas(modifier.fillMaxSize()) {
        val y = timetableTimeOffset(slots, rowHeight.toPx(), 22.dp.toPx(), minute.value) ?: return@Canvas
        val day = dayWidth?.toPx() ?: ((size.width - timeWidth.toPx()) / 7)
        val x = timeWidth.toPx() + (today - 1) * day
        drawLine(accent, Offset(x, y), Offset(x + day, y), strokeWidth = 2.dp.toPx())
        drawCircle(accent, radius = 3.dp.toPx(), center = Offset(x + 3.dp.toPx(), y))
    }
}

/** 隐去被跨休息课程覆盖的胶囊片段，防止文字穿过半透明课程底色。 */
internal fun Modifier.maskTimetableBreak(covered: List<Pair<Float, Float>>): Modifier = drawWithContent {
    var left = 0f
    covered.sortedBy { it.first }.forEach { (start, end) ->
        val right = start.coerceIn(0f, 1f) * size.width
        if (right > left) clipRect(left = left, right = right) { this@drawWithContent.drawContent() }
        left = maxOf(left, end.coerceIn(0f, 1f) * size.width)
    }
    if (left < size.width) clipRect(left = left) { this@drawWithContent.drawContent() }
}
