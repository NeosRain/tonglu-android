package cn.tonglu.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.ScheduleText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/** 课表区域的周次手势由内层 Pager 处理，页面切换由外层 Pager 处理。 */
@Composable
internal fun WeeklyTimetablePager(
    allCourses: List<CourseDto>, selectedWeek: Int, currentWeek: Int?, termStart: String?,
    search: String, nowMinutes: Int, onWeekChange: (Int) -> Unit, expanded: Boolean,
    modifier: Modifier = Modifier, onImport: (() -> Unit)? = null,
    onLongPressCourse: (CourseDto) -> Unit,
    onTapCourse: (CourseDto) -> Unit = {},
) {
    val lastWeek = maxOf(20, selectedWeek, currentWeek ?: 0,
        allCourses.maxOfOrNull { it.weeks.maxOrNull() ?: it.endWeek } ?: 0)
    val pager = rememberPagerState(initialPage = selectedWeek, pageCount = { lastWeek + 1 })
    val latestOnWeekChange by rememberUpdatedState(onWeekChange)
    // 程序化跳页（点周次胶囊）的目标页。跳页途中 currentPage 会掠过中间页并翻转，
    // 中间值一旦回写 selectedWeek，下面按 key 重启的 LaunchedEffect 就会取消正在飞的
    // animateScrollToPage，且新协程 currentPage==selectedWeek 直接 no-op —— 滚动死在
    // 中间页，点「第 8 周」落在第 3 周（2026-10-06 审查发现的回归）。
    // 所以跳页期间 pager→state 方向静默；落位后 finally 清标记（已被新目标取代时不清）。
    var jumpTarget by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedWeek) {
        if (pager.currentPage != selectedWeek) {
            jumpTarget = selectedWeek
            try {
                pager.animateScrollToPage(selectedWeek)
            } finally {
                if (jumpTarget == selectedWeek) jumpTarget = null
            }
        }
    }
    LaunchedEffect(pager) {
        // 2026-10-06 修「滑页时周次数字滞后」：原先监听 settledPage（动画/惯性**完全停稳**才变），
        // 滑动过程中标题周数和周次胶囊全都不动、停稳了才猛跳一下，观感就是「数字跳得不快」。
        // currentPage 在页面滑过一半的瞬间就切换，数字跟着手走；drop(1) 跳过初始页，
        // 避免刚打开就回调一次把 selectedWeek 重写。
        snapshotFlow { pager.currentPage }.drop(1).distinctUntilChanged().collect { page ->
            if (jumpTarget == null) latestOnWeekChange(page)
        }
    }
    val matching = remember(allCourses, search) {
        allCourses.filter { search.isBlank() || it.name.contains(search, true) ||
            it.location.orEmpty().contains(search, true) || it.teacher.orEmpty().contains(search, true) }
    }
    val courseEnd = remember(allCourses) { allCourses.maxOfOrNull { it.weeks.maxOrNull() ?: it.endWeek } }
    HorizontalPager(state = pager, modifier = modifier.fillMaxWidth(),
        beyondViewportPageCount = 0, key = { it }, verticalAlignment = Alignment.Top) { page ->
        val courses = remember(matching, page) { matching.filter { page == 0 || it.activeInWeek(page) } }
        val finished = page > 0 && courseEnd != null && page > courseEnd
        val message = when {
            allCourses.isEmpty() -> "还没有导入课表"
            finished -> "本学期已上完"
            search.isNotBlank() -> "没有匹配的课程"
            else -> "这一周没有课"
        }
        Column(Modifier.fillMaxWidth()
            .then(if (expanded) Modifier.fillMaxHeight().verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = if (expanded) 10.dp else 2.dp)) {
            if (courses.isEmpty()) {
                Column(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    MiuixText(message, color = AppColors.TextMuted)
                    if (!finished && onImport != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onImport, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) {
                            MiuixText("从教务导入", color = AppColors.OnAccent)
                        }
                    }
                }
            } else {
                TimetableGrid(courses = courses,
                    today = if (page > 0 && page == currentWeek) ScheduleText.todayWeekday() else null,
                    expanded = expanded, nowMinutes = nowMinutes,
                    weekStartMillis = if (page > 0) ScheduleText.weekStartOfTerm(termStart, page) else null,
                    onLongPressCourse = onLongPressCourse, onTapCourse = onTapCourse)
            }
        }
    }
}
