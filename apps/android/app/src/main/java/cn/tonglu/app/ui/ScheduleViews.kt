package cn.tonglu.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.ClassOverLines
import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.Holidays
import cn.tonglu.app.data.PeriodSlot
import cn.tonglu.app.data.PeriodTable
import cn.tonglu.app.data.ScheduleBoard
import cn.tonglu.app.data.ScheduleText
import cn.tonglu.app.data.scheduleOverlapGroups
import cn.tonglu.app.data.WeekSpec
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/* ------------------------------- 配色 / 小工具 ------------------------------- */

internal val SvAccent get() = AppColors.Accent
internal val SvMuted get() = AppColors.TextMuted
internal val SvInk get() = AppColors.TextPrimary

/**
 * 课程配色：Open Color（开源色库 https://yeun.github.io/open-color/）的 shade 8~9。
 *
 * 🔴 **原注释写「白字对比 ≥4.5」是没核对过的**（2026-10-10 实测：那个说法对下面 4 个色是假的 ——
 * 白字只有 3.95~4.37）。四个**偏亮的**已按「同色相 + 同饱和度、只压明度」压到达标：
 *   orange `#D9480F`(4.30) → `#C7420E`(4.98)
 *   teal   `#099268`(3.95) → `#08805C`(4.94)
 *   green  `#2B8A3E`(4.37) → `#288039`(4.96)
 *   cyan   `#0C8599`(4.35) → `#0B798C`(5.09)
 * **这两个数字（写死的色值、它的白字对比度）由 `ScheduleCourseColorTest` 钉住** ——
 * 改任何一格都会因「白字对课程色 < 4.5」变红。加色时先算对比度再加。
 */
internal val coursePalette: List<Color> = listOf(
    Color(0xFF1971C2), // blue-8
    Color(0xFFC7420E), // orange（9 压暗：白字 4.98）
    Color(0xFF08805C), // teal（8 压暗：白字 4.94）
    Color(0xFF9C36B5), // grape-8
    Color(0xFF288039), // green（9 压暗：白字 4.96）
    Color(0xFFA61E4D), // pink-9
    Color(0xFF0B798C), // cyan（8 压暗：白字 5.09）
    Color(0xFFC92A2A), // red-9
    Color(0xFF3B5BDB), // indigo-8
    Color(0xFF6741D9), // violet-8
)

/**
 * 课程色板用尽（**同一学期超过 10 门不同名课程**）后的兜底生成色。
 *
 * 原来是写死的 `Color.hsv(hue, 0.72f, 0.68f)` —— 明度 0.68 在**黄绿段**白字只剩 **2.39:1**
 * （浅底白字，基本读不出）。这里按色相查「白字仍 ≥4.6:1 的最亮明度」，在类初始化时算一次
 * （360 个色相 × 二分 20 次，启动开销可忽略），运行期只查表。
 */
private val fallbackMaxValue: FloatArray = FloatArray(360) { deg ->
    var lo = 0.15f
    var hi = 0.68f
    repeat(20) {
        val mid = (lo + hi) / 2f
        if (whiteContrastOf(Color.hsv(deg.toFloat(), 0.72f, mid)) >= 4.6f) lo = mid else hi = mid
    }
    lo
}

/** 白字压在该色上的 WCAG 对比度（= `(1.05) / (L + 0.05)`）。 */
internal fun whiteContrastOf(color: Color): Float = 1.05f / (color.luminance() + 0.05f)

/** 兜底课程色：按黄金角散开色相，明度查 [fallbackMaxValue]，保证白字可读。 */
internal fun courseFallbackColor(order: Int): Color {
    val hue = (((order * 137.508f) % 360f) + 360f) % 360f
    return Color.hsv(hue, 0.72f, fallbackMaxValue[hue.toInt().coerceIn(0, 359)])
}

internal val LocalCourseColors = staticCompositionLocalOf<Map<String, Color>> { emptyMap() }

/** 按完整课表分配颜色，同一课程在各周保持一致；先消除有限调色板的哈希碰撞。 */
internal fun buildCourseColors(courses: List<CourseDto>): Map<String, Color> {
    val used = mutableSetOf<Int>()
    return courses.map { it.name.trim() }.distinct().sorted().mapIndexed { order, name ->
        val preferred = (name.hashCode().toLong() and 0x7fffffff).rem(coursePalette.size).toInt()
        val slot = (0 until coursePalette.size).map { (preferred + it) % coursePalette.size }
            .firstOrNull { it !in used }
        val color = if (slot != null) {
            used += slot
            coursePalette[slot]
        } else {
            courseFallbackColor(order)
        }
        name to color
    }.toMap()
}

@Composable
internal fun courseColor(name: String): Color {
    LocalCourseColors.current[name.trim()]?.let { return it }
    // 加一层混合，避免相邻 hash 都撞到同一色
    var h = name.hashCode()
    h = h xor (h ushr 16)
    h *= 0x45D9F3B
    h = h xor (h ushr 16)
    val index = ((if (h < 0) -(h + 1) else h) % coursePalette.size)
    return coursePalette[index]
}

/** 把教务的长教室名压短：13#楼_307(制图实验室)(金山校区) → 13#楼307 */
internal fun shortPlace(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val head = text.substringBefore("(").substringBefore("（")
    return head.replace("_", "").trim().ifBlank { text.take(12) }
}

private fun periodMinutes(period: Int, start: Boolean): Int? {
    val slot = PeriodTable.slot(period) ?: return null
    return PeriodTable.parseMinutes(if (start) slot.start else slot.end)
}

/** 这节课是否已经上完 */
internal fun hasFinished(course: CourseDto, nowMinutes: Int): Boolean =
    periodMinutes(course.endPeriod, start = false)?.let { it < nowMinutes } ?: false

/** 这节课此刻是否正在上 */
internal fun isOngoing(course: CourseDto, nowMinutes: Int): Boolean {
    val start = periodMinutes(course.startPeriod, start = true) ?: return false
    val end = periodMinutes(course.endPeriod, start = false) ?: return false
    return nowMinutes in start..end
}

/**
 * 这节课是否已经「过去」：今天之前的日期（昨天的课），或今天已上完的节次。
 * today 传 null 表示看的不是当前周（如第 1 周回看），不做已结束处理。
 */
internal fun isCourseOver(course: CourseDto, today: Int?, nowMinutes: Int): Boolean =
    today != null && (
        course.weekday < today ||
            (course.weekday == today && hasFinished(course, nowMinutes))
        )

internal fun periodLabel(course: CourseDto): String = "${course.startPeriod}-${course.endPeriod}节"

/**
 * 首页摘要要列的课，**最多两条**（plan/56 §2.1）。
 *
 * - 正在上的那条优先，再补下一节；没有正在上的就取最近的两条未来课。
 * - 完整全天/整周仍由周课表承载 —— 首页不该把同一份课表列两遍（首屏列整日课程正是「主页臃肿」的来源）。
 * - 作息表里查不到时刻的**不能按 00:00 参与排序**，否则会被当成「马上上课」顶到最前面；
 *   它们也别就此消失，排不上「最近」就补在后面。
 * - 只有一条就显示一条，不补假数据。连堂课本来就是一条（startPeriod~endPeriod），不会按课时拆开。
 */
internal fun upcomingSummary(restCourses: List<CourseDto>, nowMinutes: Int, limit: Int = 2): List<CourseDto> {
    if (restCourses.isEmpty()) return emptyList()
    val (timed, untimed) = restCourses.partition { periodMinutes(it.startPeriod, start = true) != null }
    val sorted = timed.sortedWith(
        compareBy<CourseDto> { periodMinutes(it.startPeriod, start = true)!! }.thenBy { it.startPeriod }
    )
    val ongoing = sorted.firstOrNull { isOngoing(it, nowMinutes) }
    val future = sorted.filter { (periodMinutes(it.startPeriod, start = true) ?: 0) > nowMinutes }
    val picked = if (ongoing != null) {
        listOf(ongoing) + future.filter { it != ongoing }.take(limit - 1)
    } else {
        future.take(limit)
    }
    return (picked + untimed.filter { it !in picked }).take(limit)
}

/* ------------------------------- 今日课程卡 ------------------------------- */

/**
 * 今日课表：只列当天的课（一般 2~4 节），一眼看到下节课。
 *  · 正在上 → 高亮
 *  · 已上完 → 变暗 + 「已结束」
 *  · 还没到 → 「N 分钟后上课」
 */
@Composable
internal fun TodayClassesCard(
    courses: List<CourseDto>,
    semesterFinished: Boolean = false,
    week: Int?,
    nowMinutes: Int,
    busy: Boolean,
    onImport: () -> Unit,
    onAdd: () -> Unit,
    onLongPressCourse: (CourseDto) -> Unit = {},
) {
    val today = ScheduleText.todayWeekday()
    // 点今日课程行看这门课的详情（2026-10-10：与课表格子保持一致）
    var selectedCourse by remember { mutableStateOf<CourseDto?>(null) }
    val holidayNow = remember { Holidays.holidayAt(System.currentTimeMillis()) }
    val todayCourses = remember(courses, today, week) { ScheduleBoard.ofDay(courses, today, week) }
    // 国定假期当天一律不上课（2026-10-05 用户报告）：当天课表**整体进入完成状态**，
    // 「今日还剩几节课」直接归零 —— 假期里的「2 小时 27 分后上课」全是错的。
    // 调课安排 App 猜不了，文案一律落到「以学校通知为准」。
    val onHoliday = holidayNow != null
    // 上完一节就消失一节：只留下还没上完的（假期时全部按完成态处理）
    val restCourses = if (onHoliday) emptyList() else todayCourses.filterNot { hasFinished(it, nowMinutes) }
    // 首页摘要最多两条：正在上 + 下一节。完整那份在周课表里，这里不重列。
    val summary = remember(restCourses, nowMinutes) { upcomingSummary(restCourses, nowMinutes) }
    val finishedCount = if (onHoliday) todayCourses.size else todayCourses.size - restCourses.size
    val current = if (onHoliday) null else restCourses.firstOrNull { isOngoing(it, nowMinutes) }
    val nextCourse = if (onHoliday) null else restCourses.firstOrNull { (periodMinutes(it.startPeriod, true) ?: 0) > nowMinutes }
    // 「下次课」：今天没课、或今天放假时都要算（nextInDays 内部会跳过假期里的日子）
    val later = if (todayCourses.isEmpty() || onHoliday) {
        ScheduleBoard.nextInDays(courses, today, week, nowMinutes)
    } else null
    // 「上完课」那句小字：点一下换一条（每小时也会自然换一次）
    var lineTick by remember { mutableIntStateOf(0) }
    val lineBase = remember(finishedCount, nowMinutes) { finishedCount * 7 + nowMinutes / 60 }

    MiuixCard(insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MiuixText(
                    when {
                        semesterFinished -> "本学期已上完"
                        // 假期不数「还剩 N 节」—— 归零的正确说法是「放假」
                        onHoliday -> "今天放假${holidayNow.name.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"
                        todayCourses.isEmpty() -> "今天没有课"
                        restCourses.isEmpty() -> "今天已上完"
                        else -> "今天还剩 ${restCourses.size} 节课"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                MiuixText(ScheduleText.todayLabel(), color = SvMuted, style = MaterialTheme.typography.bodySmall)
            }
            val badge: Pair<String, Boolean>? = when {
                current != null -> "正在上课" to true
                nextCourse != null ->
                    (ScheduleBoard.gapText((periodMinutes(nextCourse.startPeriod, true) ?: 0) - nowMinutes) + "上课") to false
                onHoliday && later != null ->
                    "${ScheduleBoard.gapText(later.second)}（${ScheduleText.shortWeekday(later.first.weekday)}）上课" to false
                onHoliday -> "放假中" to false
                restCourses.isEmpty() && todayCourses.isNotEmpty() -> "已上完" to false
                later != null ->
                    "${ScheduleBoard.gapText(later.second)}（${ScheduleText.shortWeekday(later.first.weekday)}）" to false
                else -> null
            }
            if (badge != null) {
                Surface(
                    color = if (badge.second) SvAccent else SvAccent.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    MiuixText(
                        badge.first,
                        color = if (badge.second) AppColors.OnAccent else SvAccent,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (semesterFinished) {
            MiuixText("本学期课表中的课程已结束。", color = SvMuted, style = MaterialTheme.typography.bodySmall)
        } else if (todayCourses.isEmpty()) {
            if (holidayNow != null) {
                MiuixText(
                    "今天是${holidayNow.name}（${holidayNow.rangeLabel()}），当前课表没有课程，学校调课请以通知为准。",
                    color = SvMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                MiuixText(
                    later?.let {
                        "下次课：${ScheduleText.weekdayLabel(later.first.weekday)}（${ScheduleText.dateLabel(later.first.weekday)}）${later.first.name} ${periodLabel(later.first)}"
                    } ?: if (courses.isEmpty()) "导入课表后即可查看课程安排" else "本周暂无后续课程",
                    color = SvMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (courses.isEmpty()) {
              Spacer(Modifier.height(10.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, colors = ButtonDefaults.buttonColors(containerColor = SvAccent)) {
                    Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp),
                        tint = themeColor(androidx.compose.material3.LocalContentColor.current, AppColors.OnAccent))
                    Spacer(Modifier.width(6.dp))
                    MiuixText("从教务导入", color = AppColors.OnAccent)
                }
                OutlinedButton(onClick = onAdd) { MiuixText("手动添加") }
              }
            }
        } else if (onHoliday) {
            // 假期当天有排课：**不列那些已经不上的灰色行** ——
            // 置灰列满一屏，一眼看过去反而像「今天还有这些课」（plan/56 §2.1）。
            // 只说放假，再加一句能确定的下一次课。
            MiuixText(
                "今天是${holidayNow.name}（${holidayNow.rangeLabel()}），当天课程不上，调课安排以学校通知为准。",
                color = SvMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            later?.let {
                Spacer(Modifier.height(4.dp))
                MiuixText(
                    "下次课：${ScheduleText.weekdayLabel(it.first.weekday)}（${ScheduleText.dateLabel(it.first.weekday)}）${it.first.name} ${periodLabel(it.first)}",
                    color = SvMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (busy) {
                Spacer(Modifier.height(6.dp))
                MiuixText("正在同步课表…", color = SvMuted, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            summary.forEach { course ->
                TodayClassRow(
                    course = course,
                    ongoing = course == current,
                    finished = false,
                    isNext = course == nextCourse,
                    minutesToStart = (periodMinutes(course.startPeriod, start = true) ?: 0) - nowMinutes,
                    onLongPress = { onLongPressCourse(course) },
                    onTap = { selectedCourse = course },
                )
            }
            if (finishedCount > 0) {
                Spacer(Modifier.height(4.dp))
                // 全上完 → 只说开心的；还有课 → 保留「已上完几节」的信息 + 一句人话
                val overLine = if (restCourses.isEmpty()) {
                    ClassOverLines.pick(allDone = true, tick = lineBase + lineTick)
                } else {
                    "已上完 $finishedCount 节 · " +
                        ClassOverLines.pick(allDone = false, tick = lineBase + lineTick)
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { lineTick++ }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiuixText(
                        overLine,
                        color = SvMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Refresh, "换一条", Modifier.size(12.dp), tint = SvMuted)
                }
            }
            if (busy) {
                Spacer(Modifier.height(6.dp))
                MiuixText("正在同步课表…", color = SvMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    selectedCourse?.let { CourseDetailDialog(it) { selectedCourse = null } }
}

@Composable
private fun TodayClassRow(
    course: CourseDto,
    ongoing: Boolean,
    finished: Boolean,
    isNext: Boolean,
    minutesToStart: Int,
    onLongPress: () -> Unit,
    onTap: () -> Unit = {},
) {
    val accent = courseColor(course.name)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            // 与课表格子保持一致（2026-10-10）：点一下看这门课的详情，长按仍是删除确认。
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }) }
            .background(
                when {
                    ongoing -> accent.copy(alpha = 0.12f)
                    isNext -> SvAccent.copy(alpha = 0.07f)
                    else -> Color.Transparent
                }
            )
            .alpha(if (finished) 0.42f else 1f)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 节次块（用课程色，一眼区分）
        Column(
            Modifier
                .width(46.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent)
                .padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MiuixText("${course.startPeriod}-${course.endPeriod}", color = AppColors.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            MiuixText("节", color = AppColors.OnAccent.copy(alpha = 0.85f), fontSize = 9.sp)
        }

        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            MiuixText(
                course.name,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            MiuixText(
                listOfNotNull(
                    course.timeText().takeIf { it.isNotBlank() },
                    shortPlace(course.location),
                    course.teacher?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "地点老师待补充" },
                color = SvMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val tag = when {
            ongoing -> "正在上课"
            finished -> "已结束"
            isNext && minutesToStart in 1..599 -> "${ScheduleBoard.gapText(minutesToStart)}上课"
            else -> null
        }
        if (tag != null) {
            MiuixText(
                tag,
                color = when {
                    ongoing -> accent
                    finished -> SvMuted
                    else -> SvAccent
                },
                fontSize = 10.sp,
                fontWeight = if (ongoing) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/* ------------------------------- 周课表（可伸缩） ------------------------------- */

@Composable
private fun TimetableActions(
    onSetTermStart: () -> Unit,
    onEditPeriods: () -> Unit,
    onAdd: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.MoreHoriz, "课表设置与操作", tint = SvMuted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            onAdd?.let { action -> DropdownMenuItem(text = { MiuixText("添加课程") },
                onClick = { expanded = false; action() }) }
            onImport?.let { action -> DropdownMenuItem(text = { MiuixText("更新课表") },
                onClick = { expanded = false; action() }) }
            DropdownMenuItem(text = { MiuixText("查看作息") }, onClick = { expanded = false; onEditPeriods() })
            DropdownMenuItem(text = { MiuixText("开学日期") }, onClick = { expanded = false; onSetTermStart() })
        }
    }
}

@Composable
internal fun WeekChips(selected: Int, currentWeek: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val lastWeek = maxOf(20, selected, currentWeek ?: 0)
    val scrollState = rememberLazyListState()
    LaunchedEffect(selected, currentWeek) {
        // 导入后把当前周移到可见区域，同时留出前一周方便切换。
        scrollState.animateScrollToItem((selected - 1).coerceAtLeast(0))
    }
    LazyRow(
        modifier.fillMaxWidth(),
        state = scrollState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items((0..lastWeek).toList(), key = { it }) { index ->
            val isSelected = selected == index
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) SvAccent else themeColor(Color(0xFFF0F1F4), AppColors.ChipIdle))
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                MiuixText(
                    when {
                        index == 0 -> "周课表"
                        index == currentWeek -> "$index · 当前周"
                        else -> "$index"
                    },
                    color = if (isSelected) AppColors.OnAccent else SvMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * 嵌入主页的周课表卡：底部把手**按住上下拖**（跟手动画 + 卡片放大），拖够展开成满屏课表；点一下也展开。
 *
 * 2026-10-05 曾以「竖向拖拽会和首页滚动抢事件」为由删过拖拽（8ab4de0），10-06 用户点名要回
 * 「摁住小红块上下有动画然后放大」的原效果 —— **完整恢复**。抢事件的问题靠手势作用域解决：
 * 拖拽只认把手区域（不占满卡片），首页的滚动从把手外的地方起手不受影响；
 * 从把手上竖滑时 `detectVerticalDragGestures` 会先消费事件，滚动列表拿不到。
 */
@Composable
internal fun TimetableCard(
    courses: List<CourseDto>,
    emptyMessage: String,
    semesterFinished: Boolean,
    weekLabel: String,
    weekFilter: Int,
    currentWeek: Int?,
    onWeekChange: (Int) -> Unit,
    onSetTermStart: () -> Unit,
    onExpand: (Rect) -> Unit,
    nowMinutes: Int,
    onImport: () -> Unit,
    onAdd: () -> Unit,
    today: Int?,
    weekStartMillis: Long?,
    onEditPeriods: () -> Unit,
    onLongPressCourse: (CourseDto) -> Unit = {},
    allCourses: List<CourseDto> = courses,
    termStart: String? = null,
    search: String = "",
    onBoundsChanged: (Rect) -> Unit = {},
    // 成绩入口（plan/56 §2.4）：从首页独立卡片降为周课表标题旁的「成绩 ›」。
    // **功能保留、只是降权** —— 规划验收明写「不删除成绩/导入入口」。
    onOpenScores: (() -> Unit)? = null,
    // 示例成绩必须在入口上就看得出来（示例数据三处打标的红线之一），降权不能把标记弄丢。
    scoreDemo: Boolean = false,
) {
    var dragAcc by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    // 点课格子的详情弹层（长按仍是删除确认，弹窗由首页管）
    var selectedCourse by remember { mutableStateOf<CourseDto?>(null) }
    val density = LocalDensity.current
    val threshold = with(density) { 48.dp.toPx() }
    // 拖动中 1:1 跟手（16ms 近似无插值），松手后弹簧回位/放大。
    val dragMotion by animateFloatAsState(
        targetValue = dragAcc,
        animationSpec = if (dragging) tween(16, easing = LinearEasing)
            else spring(dampingRatio = 0.75f, stiffness = 450f),
        label = "timetableHandleStretch",
    )
    val expand: () -> Unit = {
        val stretch = (abs(dragMotion) / threshold).coerceAtMost(2f)
        val sx = 1f + stretch * 0.008f
        val sy = 1f + stretch * 0.025f
        // 传递拉伸后的位置，弹层第一帧从手指松开的卡片接续。
        val width = cardBounds.width * sx
        val height = cardBounds.height * sy
        val left = cardBounds.center.x - width / 2f
        val top = cardBounds.center.y - height / 2f + dragMotion * 0.12f
        onExpand(Rect(left, top, left + width, top + height))
    }

    MiuixCard(
        modifier = Modifier.onGloballyPositioned {
            cardBounds = Rect(it.positionInWindow(), it.size.toSize())
            onBoundsChanged(cardBounds)
        }.graphicsLayer {
            // 拖动时轻微放大（宽 0.8%/档、高 2.5%/档）+ 位移，跟手动画的核心。
            val stretch = (abs(dragMotion) / threshold).coerceAtMost(2f)
            scaleX = 1f + stretch * 0.008f
            scaleY = 1f + stretch * 0.025f
            translationY = dragMotion * 0.12f
        },
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MiuixText(weekLabel, color = SvInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            onOpenScores?.let { open ->
                Row(
                    Modifier.clickable(onClick = open),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiuixText(
                        if (scoreDemo) "成绩 · 示例" else "成绩",
                        color = SvAccent,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = SvAccent,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            TimetableActions(onSetTermStart = onSetTermStart, onEditPeriods = onEditPeriods, onAdd = onAdd, onImport = onImport)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.padding(horizontal = 6.dp)) {
            WeekChips(selected = weekFilter, currentWeek = currentWeek, onSelect = onWeekChange)
        }
        Spacer(Modifier.height(6.dp))
        WeeklyTimetablePager(
            allCourses = allCourses, selectedWeek = weekFilter, currentWeek = currentWeek,
            termStart = termStart, search = search, nowMinutes = nowMinutes,
            onWeekChange = onWeekChange, expanded = false, onImport = onImport,
            onLongPressCourse = onLongPressCourse,
            onTapCourse = { selectedCourse = it },
        )
        selectedCourse?.let { CourseDetailDialog(it) { selectedCourse = null } }
        Spacer(Modifier.height(6.dp))
        // 拖拽把手：手势只认**小横条本体**（44dp 宽 + 纵向热区加宽到 44dp），
        // 横条左右两侧不响应 —— 2026-10-10 用户反馈：之前挂在 fillMaxWidth 的 Column 上，
        // 「小横条所在一整条都能拖」，从卡片底部任何位置起手都会把卡片拖变形。
        // 点一下展开同样只在小横条上（与拖拽同区，意图最明确）。
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .width(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .clickable(onClick = expand)
                    .pointerInput(threshold) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true; dragAcc = 0f },
                            onDragEnd = {
                                if (abs(dragAcc) >= threshold) expand()
                                dragging = false
                                dragAcc = 0f
                            },
                            onDragCancel = { dragging = false; dragAcc = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            dragAcc = (dragAcc + dragAmount).coerceIn(-threshold * 3f, threshold * 3f)
                        }
                    }
                    .padding(vertical = 20.dp),
            ) {
                Box(
                    Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(themeColor(Color(0xFFD9DDE5), AppColors.TextMuted)),
                )
            }
        }
    }
}

/** 满屏课表（全屏弹层） */
@Composable
internal fun FullscreenTimetableDialog(
    courses: List<CourseDto>,
    emptyMessage: String,
    weekFilter: Int,
    currentWeek: Int?,
    weekLabel: String,
    onWeekChange: (Int) -> Unit,
    nowMinutes: Int,
    onSetTermStart: () -> Unit,
    onDismiss: () -> Unit,
    today: Int?,
    weekStartMillis: Long?,
    onEditPeriods: () -> Unit,
    onLongPressCourse: (CourseDto) -> Unit = {},
    allCourses: List<CourseDto> = courses,
    termStart: String? = null,
    search: String = "",
    originBounds: Rect = Rect.Zero,
    returnBounds: Rect = originBounds,
    onRescheduleCourse: (CourseDto, CourseDto, (Boolean, String) -> Unit) -> Unit = { _, _, done -> done(false, "暂不可调整") },
) {
    // 满屏课表里点课格子的详情弹层
    var selectedCourse by remember { mutableStateOf<CourseDto?>(null) }
    var editing by remember { mutableStateOf(false) }
    AnimatedTimetableWindow(originBounds = originBounds, returnBounds = returnBounds, onDismiss = onDismiss) { close, dragHandle ->
        Surface(color = AppColors.SurfaceOpaque, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                // 顶部灰色把手：**唯一**响应拖拽的地方 —— 往下拖收起，点一下也收起。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = close)
                        .then(dragHandle)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .padding(top = 10.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(themeColor(Color(0xFFD9DDE5), AppColors.TextMuted)),
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiuixText(weekLabel, color = SvInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TimetableActions(onSetTermStart = onSetTermStart, onEditPeriods = onEditPeriods)
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "收起", tint = SvMuted) }
                }
                Box(Modifier.padding(horizontal = 16.dp)) {
                    WeekChips(selected = weekFilter, currentWeek = currentWeek, onSelect = onWeekChange)
                }
                TextButton(onClick = { editing = !editing }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(if (editing) "完成调整" else "调整课程") }
                Spacer(Modifier.height(10.dp))
                if (editing) TimetableEditGrid(
                    courses = allCourses.filter { (weekFilter == 0 || it.activeInWeek(weekFilter)) && (search.isBlank() || it.name.contains(search, true) || it.location.orEmpty().contains(search, true) || it.teacher.orEmpty().contains(search, true)) },
                    allCourses = allCourses, modifier = Modifier.weight(1f), onSave = onRescheduleCourse,
                ) else WeeklyTimetablePager(
                    allCourses = allCourses, selectedWeek = weekFilter, currentWeek = currentWeek,
                    termStart = termStart, search = search, nowMinutes = nowMinutes,
                    onWeekChange = onWeekChange, expanded = true,
                    onLongPressCourse = onLongPressCourse, modifier = Modifier.weight(1f),
                    onTapCourse = { selectedCourse = it },
                )
                selectedCourse?.let { CourseDetailDialog(it) { selectedCourse = null } }
                // 底部提示条：只留点击收起。
                // 2026-10-05 起不再挂 dragHandle —— 用户点名「只有顶部灰色把手响应拖拽，
                // 其下区域不响应」。这里原本也能下拖，结果是从正文区域往下拉会被误判成收起。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = close)
                        .navigationBarsPadding()
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MiuixText("点击收起", color = SvMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

/* ------------------------------- 课表网格 ------------------------------- */

@Composable
internal fun TimetableGrid(
    courses: List<CourseDto>,
    today: Int?,
    expanded: Boolean,
    nowMinutes: Int,
    weekStartMillis: Long?,
    modifier: Modifier = Modifier,
    onLongPressCourse: (CourseDto) -> Unit = {},
    onTapCourse: (CourseDto) -> Unit = {},
) {
    val maxPeriod = maxOf(PeriodTable.maxPeriod(), courses.maxOfOrNull { it.endPeriod } ?: 8).coerceIn(8, 30)
    val groupsByDay = remember(courses) { courses.groupBy { it.weekday }.mapValues { (_, items) -> scheduleOverlapGroups(items) } }
    var overlapping by remember(courses) { mutableStateOf<List<CourseDto>?>(null) }
    val rowHeight = if (expanded) 76.dp else 56.dp
    val timeColumnWidth = if (expanded) 30.dp else 20.dp
    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
    // 列头日期与法定节假日都按「所选周」的周一算（此前锚死本周，切周后日期/休标不动）
    val weekStart = weekStartMillis
    val dayMillis = 24L * 3600 * 1000
    val holidays = remember(weekStart) {
        (1..7).associateWith { day -> weekStart?.let { Holidays.holidayAt(it + (day - 1) * dayMillis) } }
    }

    Column(modifier.fillMaxWidth()) {
        // 表头：星期 + 日期 + 放假「休」标
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(timeColumnWidth))
            dayNames.forEachIndexed { index, name ->
                val day = index + 1
                val holiday = holidays[day]
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    MiuixText(
                        if (day == today) "今天" else name,
                        color = if (day == today) SvAccent else SvMuted,
                        fontSize = if (expanded) 12.sp else 11.sp,
                        fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (weekStart != null) MiuixText(
                        ScheduleText.dateLabelAt(weekStart + (day - 1) * dayMillis).removeSuffix("日").replace("月", "/"),
                        color = SvMuted.copy(alpha = 0.75f),
                        fontSize = 8.sp,
                    )
                    if (holiday != null) {
                        Spacer(Modifier.height(1.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(themeColor(Color(0xFFEBEDF1), AppColors.ChipIdle))
                                .padding(horizontal = 4.dp, vertical = 0.5.dp),
                        ) {
                            MiuixText("假", color = SvMuted, fontSize = 7.5.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            // 节次 + 作息时间
            Column(Modifier.width(timeColumnWidth)) {
                (1..maxPeriod).forEach { period ->
                    Column(
                        Modifier.height(rowHeight),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MiuixText("$period", color = SvMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        if (expanded) {
                            MiuixText(
                                PeriodTable.slot(period)?.start.orEmpty(),
                                color = SvMuted.copy(alpha = 0.7f),
                                fontSize = 7.sp,
                            )
                        }
                    }
                }
            }
            // 7 天
            (1..7).forEach { day ->
                val holiday = holidays[day]
                val dayGroups = groupsByDay[day].orEmpty()
                Column(Modifier.weight(1f)) {
                    var period = 1
                    while (period <= maxPeriod) {
                        val group = dayGroups.firstOrNull { it.startPeriod == period }
                        val course = group?.courses?.singleOrNull()
                        // 法定节假日只是提示，不能代替学校校历、覆盖已导入的课程。
                        val over = course != null && isCourseOver(course, today, nowMinutes)
                        if (group == null) {
                            val span = 1
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(rowHeight * span)
                                    .padding(1.5.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (holiday != null) themeColor(Color(0xFFF1F2F4), AppColors.ChipIdle) else themeColor(Color(0xFFF7F8FA), AppColors.Canvas)),
                            )
                            period += span
                        } else {
                            val span = (group.endPeriod - group.startPeriod + 1)
                                .coerceAtLeast(1)
                                .coerceAtMost(maxPeriod - period + 1)
                            if (course == null) OverlappingCourseBlock(
                                courses = group.courses, height = rowHeight * span,
                                onOpen = { overlapping = group.courses },
                            ) else CourseBlock(
                                course = course,
                                height = rowHeight * span,
                                expanded = expanded,
                                dimmed = over,
                                holiday = holiday != null,
                                onLongPress = { onLongPressCourse(course) },
                                onTap = { onTapCourse(course) },
                            )
                            period += span
                        }
                    }
                }
            }
        }
    }
    overlapping?.let { items ->
        AlertDialog(
            onDismissRequest = { overlapping = null },
            title = { Text("同一时段有 ${items.size} 门课") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("这些课程的节次有重叠，请核对学校安排。点选查看详情。", color = AppColors.TextSecondary)
                    items.forEach { item ->
                        Surface(shape = RoundedCornerShape(16.dp), color = AppColors.ChipIdle) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { overlapping = null; onTapCourse(item) }.padding(8.dp)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold)
                                    Text("${item.startPeriod}-${item.endPeriod}节 · ${item.weekLabel()}", style = MaterialTheme.typography.bodySmall)
                                    Text(item.location?.takeIf { it.isNotBlank() } ?: "未填写教室", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { overlapping = null; onLongPressCourse(item) }) {
                                    Icon(Icons.Outlined.DeleteOutline, "删除课程：${item.name}")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { overlapping = null }) { Text("关闭") } },
        )
    }
}

/** 一个时段只占一个可点区域，避免窄列里把两门课压成无法点中的细条。 */
@Composable
private fun OverlappingCourseBlock(courses: List<CourseDto>, height: Dp, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(height).padding(1.5.dp)
        .clip(RoundedCornerShape(9.dp)).clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(1.dp)) {
        courses.take(2).forEach { course ->
            Box(Modifier.fillMaxWidth().weight(1f).background(courseColor(course.name)).padding(3.dp),
                contentAlignment = Alignment.Center) {
                Text(course.name, color = AppColors.OnAccent, fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp, lineHeight = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (height >= 100.dp || courses.size > 2) {
            Box(Modifier.fillMaxWidth().background(AppColors.ChipIdle).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                Text("${courses.size}门 · 查看", color = AppColors.TextPrimary, fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}

/** 课表里的竖长条方块：课名 / 教室 / 老师 / 时间，按高度自动取舍 */
@Composable
private fun CourseBlock(
    course: CourseDto,
    height: Dp,
    expanded: Boolean,
    dimmed: Boolean,
    holiday: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit = {},
) {
    val base = if (holiday) themeColor(Color(0xFFDDE1E7), AppColors.ChipIdle) else courseColor(course.name)
    // 已上完 → 整块降透明度（保留原色，一眼看出是已经上完的课）
    val fg = if (holiday) themeColor(Color(0xFF454D59), AppColors.TextSecondary) else AppColors.OnAccent
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(1.5.dp)
            .clip(RoundedCornerShape(if (expanded) 11.dp else 9.dp))
            // 点击看详情（2026-10-10 用户要求）；长按删除保留不变。
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }) }
            .alpha(when { holiday -> 0.78f; dimmed -> 0.65f; else -> 1f })
            .background(base),
    ) {
        val available = height - 3.dp
        val showPlace = available >= (if (expanded) 70.dp else 66.dp)
        val showTeacher = expanded && available >= 110.dp
        val showTime = expanded && available >= 130.dp

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (expanded) 5.dp else 4.dp, vertical = 4.dp),
        ) {
            MiuixText(
                course.name,
                color = fg,
                fontSize = if (expanded) 11.sp else 10.sp,
                lineHeight = if (expanded) 13.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (expanded && available >= 150.dp) 5 else 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            shortPlace(course.location)?.let { place ->
                if (showPlace) {
                    MiuixText(
                        place,
                        color = fg.copy(alpha = 0.94f),
                        fontSize = if (expanded) 8.5.sp else 8.sp,
                        lineHeight = if (expanded) 10.sp else 9.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showTeacher) {
                course.teacher?.takeIf { it.isNotBlank() }?.let { teacher ->
                    MiuixText(
                        teacher,
                        color = fg.copy(alpha = 0.9f),
                        fontSize = if (expanded) 8.5.sp else 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MiuixText(
                "${course.startPeriod}-${course.endPeriod}节",
                color = fg.copy(alpha = 0.92f),
                fontSize = if (expanded) 8.5.sp else 8.sp,
                maxLines = 1,
            )
            if (showTime) {
                MiuixText(
                    course.timeText(),
                    color = fg.copy(alpha = 0.8f),
                    fontSize = 7.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** 点课格子弹出的课程详情（2026-10-10 用户要求；长按删除保留不变） */
@Composable
private fun CourseDetailDialog(course: CourseDto, onDismiss: () -> Unit) {
    val weeks = if (course.weeks.isNotEmpty()) WeekSpec.describe(course.weeks)
    else "${course.startWeek}-${course.endWeek} 周"
    AlertDialog(
        onDismissRequest = { OverlayDismissGuard.arm(); onDismiss() },
        title = { Text(course.name, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                detailLine("时间", "${ScheduleText.weekdayLabel(course.weekday)} · ${periodLabel(course)} · ${course.timeText()}")
                detailLine("教室", course.location?.takeIf { it.isNotBlank() } ?: "未填写")
                detailLine("教师", course.teacher?.takeIf { it.isNotBlank() } ?: "未填写")
                detailLine("周次", weeks)
            }
        },
        confirmButton = {
            TextButton(onClick = { OverlayDismissGuard.arm(); onDismiss() }) { Text("知道了") }
        },
    )
}

@Composable
private fun detailLine(label: String, value: String) {
    Row {
        Text(label, color = SvMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(42.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/* ------------------------------- 放假横幅 ------------------------------- */

/** 今天放假时主页顶部的「放假中」横幅 */
@Composable
internal fun HolidayBanner(span: Holidays.HolidaySpan) {
    Surface(color = themeColor(Color(0xFFF0F2F5), AppColors.ChipIdle), shape = RoundedCornerShape(14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                MiuixText(
                    "放假中 · ${span.name}",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
                MiuixText(
                    "${span.rangeLabel()} · 法定假期，上课安排以学校通知为准",
                    color = SvMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 课表离线问答：关键词匹配 + 本地课表计算（下节课 / 还剩几节 / 明天 / 放假 / 哪天最忙）。
 *
 * 暂时没有 UI 入口：主页那张「AI 助手」卡已按需求撤掉（放主页没规划好）。
 * 这段逻辑特意保留 —— 以后接进顶部搜索框（直接在搜索框里问课表问题）时直接复用。
 */
internal fun aiAnswer(
    question: String,
    courses: List<CourseDto>,
    week: Int?,
    today: Int,
    nowMinutes: Int,
): String {
    val q = question.replace(" ", "")
    val holidayNow = Holidays.holidayAt(System.currentTimeMillis())

    if (q.contains("放假") || q.contains("假期")) {
        holidayNow?.let { return "现在是${it.name}（${it.rangeLabel()}），上课和调课安排以学校通知为准。" }
        val next = Holidays.daysToNextHoliday()
        return next?.let { (span, days) -> "距离${span.name}（${span.rangeLabel()}）放假还有 $days 天" }
            ?: "暂无假期安排"
    }
    if (q.contains("下节") || q.contains("接下来") || q.contains("下一")) {
        // 假期里「下节课」必须先报放假，再说假期后的下次课（nextInDays 会跳过假期日）
        if (holidayNow != null) {
            val later = ScheduleBoard.nextInDays(courses, today, week, nowMinutes)
            return later?.let { (course, gap) ->
                "现在是${holidayNow.name}。下次课：${ScheduleText.shortWeekday(course.weekday)} ${course.name} " +
                    "${periodLabel(course)}（${ScheduleBoard.gapText(gap)}上课）"
            } ?: "现在是${holidayNow.name}，最近没有课"
        }
        val rest = ScheduleBoard.ofDay(courses, today, week).filter { !hasFinished(it, nowMinutes) }
        val next = rest.firstOrNull { (periodMinutes(it.startPeriod, true) ?: 0) > nowMinutes }
        if (next != null) {
            return "下节课：${next.name} · ${periodLabel(next)} ${next.timeText()} · " +
                (shortPlace(next.location) ?: "教室待定") + (next.teacher?.let { " · $it" } ?: "")
        }
        val later = ScheduleBoard.nextInDays(courses, today, week, nowMinutes)
        return later?.let { (course, gap) ->
            "今天没课了。下次课：${ScheduleText.shortWeekday(course.weekday)} ${course.name} " +
                "${periodLabel(course)}（${ScheduleBoard.gapText(gap)}上课）"
        } ?: "最近没有课"
    }
    if (q.contains("明天")) {
        // 明天还在假期里就说放假，别拿课表骗人
        val tomorrow = ScheduleText.startOfDay(System.currentTimeMillis()) + 24L * 3600 * 1000
        val tomorrowHoliday = Holidays.holidayAt(tomorrow)
        if (tomorrowHoliday != null) return "明天还在${tomorrowHoliday.name}假期里，没有课"
        val list = ScheduleBoard.ofDay(courses, today % 7 + 1, week)
        return if (list.isEmpty()) "明天没有课"
        else "明天 ${list.size} 节课：" + list.joinToString("、") { "${it.name}（${periodLabel(it)}）" }
    }
    if (q.contains("今天") || q.contains("还剩") || q.contains("几节")) {
        // 假期当天一律「不上课」，不数「还剩 N 节」（2026-10-05 用户报告）
        if (holidayNow != null) {
            return "现在是${holidayNow.name}（${holidayNow.rangeLabel()}），当天课程不上，调课安排以学校通知为准。"
        }
        val all = ScheduleBoard.ofDay(courses, today, week)
        val rest = all.filter { !hasFinished(it, nowMinutes) }
        val current = rest.firstOrNull { isOngoing(it, nowMinutes) }
        return when {
            all.isEmpty() -> "今天没有课"
            rest.isEmpty() -> "今天 ${all.size} 节课都已上完"
            current != null -> "正在上「${current.name}」，之后还有 ${rest.size - 1} 节"
            else -> "今天还剩 ${rest.size} 节：" + rest.joinToString("、") { "${it.name}（${periodLabel(it)}）" }
        }
    }
    if (q.contains("最多") || q.contains("最累") || q.contains("最忙")) {
        val counts = (1..7).map { ScheduleBoard.ofDay(courses, it, week).size }
        val max = counts.maxOrNull() ?: 0
        if (max == 0) return "这周没有课"
        val days = (1..7).filter { ScheduleBoard.ofDay(courses, it, week).size == max }
        return "这周" + days.joinToString("、") { ScheduleText.shortWeekday(it) } + "课最多（$max 节）"
    }
    return "我可以回答：下节课是什么 / 今天还剩几节 / 明天有课吗 / 什么时候放假 / 这周哪天课最多"
}

/* ------------------------------- 作息时间表 ------------------------------- */

/**
 * 作息时间表：改每节的上下课时间。
 * 保存后只在本机生效 —— 课表格子里的时间、正在上课/已上完的判断、课前提醒都跟着变；
 * 传 null 给 onSave 表示恢复教务默认作息。
 */
@Composable
internal fun PeriodEditorDialog(
    onDismiss: () -> Unit,
    onSave: (List<PeriodSlot>?) -> Unit,
) {
    val initial = remember { PeriodTable.slots() }
    val periodSource = cn.tonglu.app.data.ScheduleStore(androidx.compose.ui.platform.LocalContext.current).periodTableSource
    val rows = remember {
        mutableStateListOf<Pair<String, String>>().apply { initial.forEach { add(it.start to it.end) } }
    }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = AppColors.SurfaceOpaque,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(0.92f).padding(vertical = 20.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                MiuixText("作息时间表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                MiuixText(
                    "${periodSource ?: "内置默认作息"} · 导入课表时会自动获取学校作息，通常无需修改。学校临时调整时，可在这里手动修正。",
                    color = SvMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rows.forEachIndexed { index, row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiuixText("第 ${index + 1} 节", fontSize = 12.sp, modifier = Modifier.width(56.dp))
                            TimeField(row.first) { rows[index] = it to rows[index].second }
                            MiuixText("至", color = SvMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                            TimeField(row.second) { rows[index] = rows[index].first to it }
                            Spacer(Modifier.weight(1f))
                            if (rows.size > 1) {
                                IconButton(onClick = { rows.removeAt(index) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Outlined.Close, "删除第 ${index + 1} 节", Modifier.size(15.dp), tint = SvMuted)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = rows.size < 30, onClick = { if (rows.size < 30) rows.add("00:00" to "00:00") }) {
                        MiuixText("＋ 加一节", color = SvAccent, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        rows.clear()
                        PeriodTable.DEFAULT.forEach { rows.add(it.start to it.end) }
                    }) { MiuixText("填入教务默认", color = SvMuted, fontSize = 12.sp) }
                }
                error?.let {
                    MiuixText(it, color = themeColor(Color(0xFFC92A2A), AppColors.Danger), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onSave(null) }) { MiuixText("恢复默认", color = SvMuted) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { MiuixText("取消") }
                    Button(
                        onClick = {
                            val normalized = rows.mapIndexed { index, row ->
                                (index + 1) to (PeriodTable.normalizeHhmm(row.first) to PeriodTable.normalizeHhmm(row.second))
                            }
                            val badFormat = normalized.firstOrNull { it.second.first == null || it.second.second == null }
                            if (badFormat != null) {
                                error = "第 ${badFormat.first} 节时间要写成 08:00 这样"
                                return@Button
                            }
                            val parsed = normalized.map { (period, times) -> PeriodSlot(period, times.first!!, times.second!!) }
                            val badOrder = parsed.firstOrNull {
                                PeriodTable.parseMinutes(it.start) >= PeriodTable.parseMinutes(it.end)
                            }
                            if (badOrder != null) {
                                error = "第 ${badOrder.period} 节的下课时间要晚于上课时间"
                                return@Button
                            }
                            onSave(parsed)
                        },
                    ) { MiuixText("保存") }
                }
            }
        }
    }
}

/** HH:mm 输入框：只吃数字，打满 3 位自动补冒号（800 → 8:00，1230 → 12:30） */
@Composable
private fun TimeField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(4)
            onChange(
                if (digits.length <= 2) digits
                else digits.substring(0, digits.length - 2) + ":" + digits.substring(digits.length - 2),
            )
        },
        singleLine = true,
        modifier = Modifier.width(82.dp),
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}


