package cn.tonglu.app.ui.timetable

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import cn.tonglu.app.data.PeriodSlot
import cn.tonglu.app.data.PeriodTable

internal data class TimetableBreak(val afterPeriod: Int, val label: String)

/** 只压缩长休息，短课间仍沿用原来的等高节次。 */
internal fun timetableBreaks(slots: List<PeriodSlot>): List<TimetableBreak> =
    slots.sortedBy { it.period }.zipWithNext().mapNotNull { (left, right) ->
        val end = PeriodTable.parseMinutes(left.end)
        if (PeriodTable.parseMinutes(right.start) - end >= 50) {
            TimetableBreak(left.period, if (end < 15 * 60) "午休" else "晚休")
        } else null
    }

internal fun periodTop(period: Int, rowHeight: Float, breaks: List<TimetableBreak>, breakHeight: Float): Float =
    (period - 1) * rowHeight + breaks.count { it.afterPeriod < period } * breakHeight

/** 跨休息的课程包含休息高度，尾端的休息留给独立胶囊。 */
internal fun courseGridHeight(start: Int, end: Int, rowHeight: Float, breaks: List<TimetableBreak>, breakHeight: Float): Float =
    (end - start + 1) * rowHeight + breaks.count { it.afterPeriod in start until end } * breakHeight

/** 普通课间停在行边界，长休息在胶囊内部线性前进。单位由调用方决定。 */
internal fun timetableTimeOffset(slots: List<PeriodSlot>, rowHeight: Float, breakHeight: Float, now: Int): Float? {
    val sorted = slots.sortedBy { it.period }
    if (sorted.isEmpty() || rowHeight <= 0f || breakHeight < 0f) return null
    if (now < PeriodTable.parseMinutes(sorted.first().start) || now > PeriodTable.parseMinutes(sorted.last().end)) return null
    val breaks = timetableBreaks(sorted)
    sorted.forEachIndexed { index, slot ->
        val start = PeriodTable.parseMinutes(slot.start)
        val end = PeriodTable.parseMinutes(slot.end)
        val top = periodTop(slot.period, rowHeight, breaks, breakHeight)
        if (now in start..end && end > start) return top + rowHeight * (now - start).toFloat() / (end - start)
        val next = sorted.getOrNull(index + 1)
        if (next != null && now > end && now < PeriodTable.parseMinutes(next.start)) {
            val gapHeight = if (breaks.any { it.afterPeriod == slot.period }) breakHeight else 0f
            return top + rowHeight + gapHeight * (now - end).toFloat() / (PeriodTable.parseMinutes(next.start) - end)
        }
    }
    return null
}

internal fun contrastRatio(foreground: Color, background: Color): Float {
    val ink = foreground.compositeOver(background)
    val a = ink.luminance()
    val b = background.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

internal data class CourseGlassColors(val tint: Color, val border: Color, val ink: Color, val underlay: Color)

/** 先取设计混色，再按实际叠色校正；不依赖固定色板，兜底色相同样可读。 */
internal fun courseGlassColors(color: Color, dark: Boolean, canvas: Color, surface: Color, wallpaper: Boolean): CourseGlassColors {
    val tint = color.copy(alpha = if (dark) 0.30f else 0.20f)
    val underlay = surface.copy(alpha = if (wallpaper) 0.55f else 0f)
    val background = tint.compositeOver(underlay.compositeOver(canvas))
    val target = if (dark) Color.White else Color.Black
    var amount = if (dark) 0.60f else 0.50f
    var ink = lerp(color, target, amount)
    while (contrastRatio(ink, background) < 4.5f && amount < 1f) {
        amount = (amount + 0.01f).coerceAtMost(1f)
        ink = lerp(color, target, amount)
    }
    return CourseGlassColors(tint, color.copy(alpha = if (dark) 0.45f else 0.35f), ink, underlay)
}

internal enum class TodayCourseState(val label: String) {
    Upcoming("未开始"), Ongoing("进行中"), Finished("已结束"), Unknown("时间待定")
}
internal enum class TodayCourseGroup(val label: String) { Morning("上午"), Afternoon("下午"), Evening("晚上"), Unknown("时间待定") }
internal data class TodayCourseTiming(val state: TodayCourseState, val remainingMinutes: Int?, val group: TodayCourseGroup) {
    val countdown: String get() = when (state) {
        TodayCourseState.Upcoming -> remainingMinutes!!.let { if (it >= 60) "${it / 60} 小时 ${it % 60} 分钟后" else "$it 分钟后" }
        TodayCourseState.Ongoing -> "还剩 $remainingMinutes 分钟下课"
        else -> state.label
    }
}

/** 结束边界采用半开区间，铃响时立刻进入已结束状态。 */
internal fun todayCourseTiming(start: Int?, end: Int?, now: Int): TodayCourseTiming {
    val group = when {
        start == null -> TodayCourseGroup.Unknown
        start < 12 * 60 -> TodayCourseGroup.Morning
        start < 18 * 60 -> TodayCourseGroup.Afternoon
        else -> TodayCourseGroup.Evening
    }
    return when {
        start == null || end == null || end <= start -> TodayCourseTiming(TodayCourseState.Unknown, null, group)
        now < start -> TodayCourseTiming(TodayCourseState.Upcoming, start - now, group)
        now < end -> TodayCourseTiming(TodayCourseState.Ongoing, end - now, group)
        else -> TodayCourseTiming(TodayCourseState.Finished, 0, group)
    }
}

/** 反向压平休息条；条内两侧边缘映射到同一个节次边界，避免拖动多算一节。 */
internal fun timetableDragY(y: Float, rowHeight: Float, breaks: List<TimetableBreak>, breakHeight: Float): Float {
    var removed = 0f
    breaks.sortedBy { it.afterPeriod }.forEach { rest ->
        val top = rest.afterPeriod * rowHeight + removed
        if (y < top) return y - removed
        if (y <= top + breakHeight) return rest.afterPeriod * rowHeight
        removed += breakHeight
    }
    return y - removed
}
