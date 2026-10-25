package cn.tonglu.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.*
import cn.tonglu.app.ui.*
import top.yukonga.miuix.kmp.basic.Text

// 主页配色统一放 HomeCards.kt（HomeAccent / HomeMuted），这里不再重复声明。

@Composable
internal fun HomeScreen(
    state: AppUiState,
    search: String,
    onAdd: () -> Unit,
    onDeleteCourse: (String) -> Unit,
    onRescheduleCourse: (CourseDto, CourseDto, (Boolean, String) -> Unit) -> Unit,
    onImport: () -> Unit,
    showImportPrompt: Boolean,
    onDismissImportPrompt: () -> Unit,
    onImportBoth: () -> Unit,
    onSetTermStart: (String?) -> Unit,
    onEditPeriods: () -> Unit,
    onOpenService: (cn.tonglu.app.data.CampusService) -> Unit,
    openingServiceId: String? = null,
    topInset: Dp = 0.dp,
    // 考试倒计时（`docs/plan/54` M1）：放在课表卡**上方**（M1 明确要求）。
    // 开关关掉时组件内部第一行就 return，首页连占位都不留（M2 硬约束 1）。
    // 日历页入口不在这里 —— 在学习页（M2 硬约束 3）。
    examStore: cn.tonglu.app.data.exam.ExamStore,
    onOpenExamCalendar: () -> Unit,
    onOpenExam: (cn.tonglu.app.data.exam.ExamEvent) -> Unit,
    // 成绩入口从学习页搬到首页（用户要求：成绩属于首页）；plan/56 §2.4 再降为周课表标题旁的「成绩 ›」。
    scoreDemo: Boolean = false,
    onOpenScores: () -> Unit = {},
    // 校园地图 v1（2026-10-10）：与 [CampusServicesSections] 平行的一张独立入口卡，
    // 因为它跳的是**第三方高德搜索**而不是 App 内浏览器，混进校园服务表会被「入口管理」误关。
    onOpenCampusMap: () -> Unit = {},
) {
    // 今天周几、放假横幅都得跟着跨天刷新 —— 写死成 remember{} 的话，
    // 进程活到第二天，「今天」这一列和假期横幅都还停在昨天（与周次不自动切换同源）。
    var today by remember { mutableIntStateOf(ScheduleText.todayWeekday()) }
    var holidayToday by remember { mutableStateOf(Holidays.holidayAt(System.currentTimeMillis())) }
    var epochDay by remember { mutableLongStateOf(ScheduleText.todayEpochDay()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var weekFilter by rememberSaveable { mutableIntStateOf(state.currentWeek ?: 0) }
    LaunchedEffect(state.termStart, state.currentWeek, state.scheduleImportRevision) {
        // 导入完成、读到学期日期或进入新的教学周时默认看当前周。
        // 普通重组和手动切周不会触发，保留用户正在浏览的周次。
        weekFilter = state.currentWeek ?: 0
    }
    var showTermStartDialog by remember { mutableStateOf(false) }
    var fullscreenTimetable by rememberSaveable { mutableStateOf(false) }
    var timetableOrigin by remember { mutableStateOf(Rect.Zero) }
    var timetableBounds by remember { mutableStateOf(Rect.Zero) }
    // 长按课表里的课 → 确认后删除（长按比单击安全：格子小，误触代价大）
    var pendingDelete by remember { mutableStateOf<CourseDto?>(null) }
    var nowMinutes by remember { mutableIntStateOf(ScheduleText.nowMinutes()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMinutes = ScheduleText.nowMinutes()
            // 跨天：把"今天周几"和假期横幅一起换过来。
            // 周次不在这里重算 —— 它在 AppViewModel 里（TongluApp 的心跳调 refreshWeek()），
            // 因为首页离屏两页就会被销毁，靠首页兜不住。
            val day = ScheduleText.todayEpochDay()
            if (day != epochDay) {
                epochDay = day
                today = ScheduleText.todayWeekday()
                holidayToday = Holidays.holidayAt(System.currentTimeMillis())
            }
        }
    }
    val week = weekFilter.takeIf { it > 0 }
    val todayForGrid = if (week != null && week == state.currentWeek) today else null
    // 列头日期/「休」标跟随所选周（此前锚死本周，切到别的周日期不动）
    val gridWeekStart = remember(weekFilter, state.termStart) {
        week?.let { ScheduleText.weekStartOfTerm(state.termStart, it) }
    }

    val visible = remember(state.courses, weekFilter, search) {
        state.courses
            .filter { week == null || it.activeInWeek(week) }
            .filter {
                search.isBlank() || it.name.contains(search, true) ||
                    it.location.orEmpty().contains(search, true) ||
                    it.teacher.orEmpty().contains(search, true)
            }
    }
    val lastCourseWeek = remember(state.courses) {
        state.courses.maxOfOrNull { it.weeks.maxOrNull() ?: it.endWeek }
    }
    val semesterFinished = week != null && lastCourseWeek != null && week > lastCourseWeek
    val emptyMessage = when {
        state.courses.isEmpty() -> "还没有导入课表"
        semesterFinished -> "本学期已上完"
        search.isNotBlank() -> "没有匹配的课程"
        else -> "这一周没有课"
    }
    val weekLabel = when {
        week == null -> if (state.termStart.isNullOrBlank()) "未设置开学日期" else "全部周次"
        week == state.currentWeek -> "第 $week 周"
        else -> "第 $week 周"
    }

    if (showTermStartDialog) {
        TermStartDialog(
            initial = state.termStart,
            onDismiss = { OverlayDismissGuard.arm(); showTermStartDialog = false },
            onConfirm = { value -> OverlayDismissGuard.arm(); onSetTermStart(value); showTermStartDialog = false },
        )
    }

    pendingDelete?.let { course ->
        AlertDialog(
            onDismissRequest = { OverlayDismissGuard.arm(); pendingDelete = null },
            title = { Text("删除课程") },
            text = {
                Text(
                    "删除「${course.name}」？\n" +
                        "${ScheduleText.shortWeekday(course.weekday)} ${course.startPeriod}-${course.endPeriod} 节" +
                        listOfNotNull(course.location?.takeIf { it.isNotBlank() }).joinToString("", prefix = " · ") +
                        "\n\n只删除本机这一条记录，不影响教务系统。",
                )
            },
            confirmButton = {
                TextButton(onClick = { OverlayDismissGuard.arm(); onDeleteCourse(course.id); pendingDelete = null }) {
                    Text("删除", color = cn.tonglu.app.ui.themeColor(Color(0xFFD9480F), cn.tonglu.app.ui.AppColors.Danger))
                }
            },
            dismissButton = { TextButton(onClick = { OverlayDismissGuard.arm(); pendingDelete = null }) { Text("取消") } },
        )
    }

    if (fullscreenTimetable) {
        FullscreenTimetableDialog(
            originBounds = timetableOrigin,
            returnBounds = timetableBounds,
            courses = visible,
            allCourses = state.courses, termStart = state.termStart, search = search,
            emptyMessage = emptyMessage,
            weekFilter = weekFilter,
            currentWeek = state.currentWeek,
            weekLabel = weekLabel,
            onWeekChange = { weekFilter = it },
            nowMinutes = nowMinutes,
            today = todayForGrid,
            weekStartMillis = gridWeekStart,
            onSetTermStart = { showTermStartDialog = true },
            onEditPeriods = onEditPeriods,
            onRescheduleCourse = onRescheduleCourse,
            // AnimatedTimetableWindow.close 内部已 arm（BACK / 下拉 / 关闭按钮共用），
            // 这里再包一层防御未来新增的关闭路径 —— 重复 arm 无害（只是重置冷却起点）。
            onDismiss = { OverlayDismissGuard.arm(); fullscreenTimetable = false },
            onLongPressCourse = { pendingDelete = it },
        )
    }

    // 一级页面仅广场有下拉刷新；首页和学习使用本地状态，社团频道后续接入实时更新。
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp + topInset, bottom = 116.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            holidayToday?.let { span -> item { HolidayBanner(span) } }
            item {
                TodayClassesCard(
                    courses = state.courses,
                    semesterFinished = state.currentWeek != null && lastCourseWeek != null && state.currentWeek > lastCourseWeek,
                    week = state.currentWeek,
                    nowMinutes = nowMinutes,
                    busy = state.busy,
                    onImport = onImport,
                    onAdd = onAdd,
                    onLongPressCourse = { pendingDelete = it },
                )
            }
            // 考试倒计时（plan/56 §2.4：压成**单行**，排在课程摘要下面、导入提示上面）。
            // 开关关掉、或没有可显示的节点时组件内部直接 return，首页不留空卡。
            item(key = "exam_countdown") {
                ExamCountdownSection(
                    store = examStore,
                    onOpenCalendar = onOpenExamCalendar,
                    onOpenExam = onOpenExam,
                )
            }
            if (showImportPrompt) item(key = "academic_import_prompt") {
                ImportPromptCard(onImportBoth, onDismissImportPrompt)
            }
            item {
                TimetableCard(
                    courses = visible,
                    allCourses = state.courses, termStart = state.termStart, search = search,
                    emptyMessage = emptyMessage,
                    semesterFinished = semesterFinished,
                    weekLabel = weekLabel,
                    weekFilter = weekFilter,
                    currentWeek = state.currentWeek,
                    onWeekChange = { weekFilter = it },
                    onSetTermStart = { showTermStartDialog = true },
                    onExpand = { bounds -> timetableOrigin = bounds; fullscreenTimetable = true },
                    onBoundsChanged = { timetableBounds = it },
                    nowMinutes = nowMinutes,
                    onImport = onImport,
                    onAdd = onAdd,
                    today = todayForGrid,
                    weekStartMillis = gridWeekStart,
                    onEditPeriods = onEditPeriods,
                    onLongPressCourse = { pendingDelete = it },
                    // 成绩入口：§2.4 从首页独立卡片降为「周课表」标题旁的「成绩 ›」（功能保留）。
                    onOpenScores = onOpenScores,
                    scoreDemo = scoreDemo,
                )
            }
            // 校园地图 v1（2026-10-10）：单独的「跳第三方」入口，不并入下面那张校园服务卡。
            item(key = "campus_map") {
                CampusMapEntryCard(onClick = onOpenCampusMap)
            }
            // 入口按分区渲染：常用工具 / 生活服务 / 校园入口。
            // 不再套一个「校园服务 · 数字平台」总括标题 —— 那个标题把文件互传和生活缴费
            // 也算成了校园网站，用户明确指出过。分区标题由组件自己带。
            item(key = "services") {
                CampusServicesSections(onImport, onOpenService, openingServiceId)
            }
        }
    }
}

@Composable
private fun TermStartDialog(initial: String?, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var value by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置开学日期") },
        text = {
            Column {
                Text("填写第 1 周周一的日期，用来算「第几教学周」，课表按周过滤。格式：2026-09-01", style = MaterialTheme.typography.bodySmall, color = HomeMuted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(10) },
                    singleLine = true,
                    label = { Text("开学日期") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim().ifBlank { null }) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

