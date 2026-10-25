package cn.tonglu.app.widget

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import cn.tonglu.app.data.ScheduleStore
import cn.tonglu.app.data.ScheduleText
import cn.tonglu.app.data.SessionStore

/**
 * 给小部件用的课表取数 —— **唯一**把「Android 侧的本机存储」接进
 * [TodayWidgetModel] 的地方。
 *
 * ## 为什么单独一层（而不是把这段写进 GlanceAppWidget 的 provideGlance）
 *
 * 小部件有三个调用方，都要同一份数据：
 *   · [TodayWidget] 画界面时
 *   · [TodayWidgetUpdater] 决定下一分钟要不要刷新时（要算「几点下课」）
 *   · [TodayWidgetReceiver.onEnabled] 用户刚把部件拖到桌面时
 * 写在 provideGlance 里就会三份实现，改一处漏两处。
 *
 * ## 三条必须照做（照抄 ClassReminder，别自己发明）
 *
 * 1. 🔴 **owner 必须走 [SessionStore.currentOwner]** —— 课表是**按身份分桶**存的
 *    （见 ScheduleStore 的 key(name) = "$name@$owner"）。用默认构造拿到的是
 *    「当前身份」，但如果小部件的进程比登录状态活得更久（App 被杀后小部件还在刷新），
 *    不显式传 owner 就可能读到另一个账号的桶 —— 换人换设备时会串课表。
 * 2. 🔴 **必须先 [ScheduleStore.applyPeriodTable]** 再算时间。
 *    课程存的是「第几节」，不是几点；作息表是用户可改的（教务默认 08:00 起，
 *    但有人改过）。不灌进 [cn.tonglu.app.data.PeriodTable] 就会用默认作息算错时间。
 * 3. 🔴 **week 必须走 [ScheduleText.weekOf]** —— 它是「开学日期 + 今天」→ 教学周。
 *    自己算会漏掉「周从周一起算」「开学日期所在周就是第 1 周」这些约定。
 */
object TodayWidgetSource {

    /** 一次取数的完整结果：既要显示的内容，也要「下次该在什么时候刷新」 */
    data class Snapshot(
        val state: TodayWidgetModel.State,
        /** 是否读到了课表（false = 没导入 / 未登录，界面上要区分「空课表」和「没数据」） */
        val hasData: Boolean,
        /**
         * 今天星期几的文案（"周一"…"周日"）。
         *
         * 为什么要由数据层给、不在画的时候现算：Glance 的 `provideContent` 里没有 Context，
         * 更要紧的是「今天是周几」**必须和 [state] 用同一个 nowMillis 算** ——
         * 分开算会在跨天那一瞬间错位（标题写周一、内容却是周二的课）。
         */
        val weekdayLabel: String,
        /** "10月16日"。和 [weekdayLabel] 同理，必须同源同时刻算。 */
        val dateLabel: String = "",
        /** 当前教学周，null = 没设开学日期（这时标题不显示周次）。 */
        val week: Int? = null,
        /**
         * 课程名 → 色的 ARGB 整数（`0xAARRGGBB`，`Color.toArgb()` 的取值）。
         *
         * 🔴 **存 Int 不存 `androidx.compose.ui.graphics.Color`**：这一层要能被 JVM 单测
         * 直接构造。**字段保持 Int** 就是为了这个 —— 测试可以不碰 Compose 就 new 一个 Snapshot。
         *（`load()` 里为了转换会短暂碰到 Compose 类型，但那是实现细节，不出现在 Snapshot 上。）
         *
         * 🔴 颜色规则**不在这里自己写一份**：直接复用 App 内课表的 `buildCourseColors`
         * （Open Color 调色板 +「白字 ≥4.5:1」的压暗色 + 超出 10 门后的兜底色），
         * 为的是「小组件课块的颜色」和「App 内课表方块的颜色」**永远一致**。
         * 照抄一份调色板过来，等于给「两边颜色打架」留后路。
         */
        val courseColors: Map<String, Int> = emptyMap(),
        /**
         * 底部那几条倒计时（竞赛 / 考试），已经按紧急程度排好序 —— 画的时候不用再挑。
         *
         * 为什么在数据层就定死「显示哪几条」：小部件的高度预算很紧（见 [TodayWidgetLayout]），
         * 而「该显示几条」不是画的时候能决定的（那时已经不知道还剩多少位置了）。
         * 这里给全量、由 [TodayWidgetLayout.plan] 决定放几条。
         */
        val deadlines: List<TodayWidgetDeadlines.Item> = emptyList(),
        val allRows: List<TodayWidgetModel.Row> = emptyList(),
    )

    /**
     * 读本机课表 → 算出要显示什么。
     *
     * 🔴 **是 suspend**：底下的 [ScheduleStore.loadCourses] 要读 Room，
     * 不能在主线程跑。Glance 的 `provideGlance` 本身就是 suspend，直接调即可。
     *
     * 不抛异常：小部件跑在桌面进程的广播里，抛出去就是「小部件崩了」的红字，
     * 而真实原因往往只是「没登录」，不值得崩。任何失败都退化成 [TodayWidgetModel.State.Empty]。
     */
    suspend fun load(context: Context, nowMillis: Long = System.currentTimeMillis()): Snapshot {
        // 不用判 null —— SessionStore.currentOwner 内部已经把「读不到会话」兜成 "local" 了，
        // 返回值永远非空。这里 runCatching 只是防「加密存储本身抛异常」这种情况。
        val owner = runCatching { SessionStore.currentOwner(context) }
            .getOrDefault(SessionStore.LOCAL_OWNER)

        // 倒计时**先取**：它跟课表是两条独立的数据链（课表在 Room，考试在 SharedPreferences，
        // 竞赛订阅在盘上的镜像）。先取还顺带保证了「没有课表的日子」也照样能看到倒计时 ——
        // 寒暑假恰恰是最需要看到「考研还有 N 天」的时候。
        val deadlines = loadDeadlines(context, owner, nowMillis)

        val store = runCatching {
            ScheduleStore(context.applicationContext, owner).also { it.applyPeriodTable() }
        }.getOrNull() ?: return empty(nowMillis, deadlines)

        val courses = runCatching { store.loadCourses() }
            .getOrDefault(emptyList())

        if (courses.isEmpty()) return empty(nowMillis, deadlines)

        val week = runCatching { ScheduleText.weekOf(store.termStart, nowMillis) }.getOrNull()
        val weekday = ScheduleText.weekdayOf(nowMillis)
        // 取色失败不该让整个小部件退化成「没有数据」：颜色错了还能看，课表丢了才是事故。
        val colors: Map<String, Int> = runCatching {
            cn.tonglu.app.ui.buildCourseColors(courses).mapValues { (_, color) -> color.toArgb() }
        }.getOrDefault(emptyMap())

        return Snapshot(
            state = TodayWidgetModel.build(courses, week, nowMillis),
            hasData = true,
            weekdayLabel = ScheduleText.shortWeekday(weekday),
            dateLabel = ScheduleText.dateLabel(weekday, nowMillis),
            week = week,
            courseColors = colors,
            deadlines = deadlines,
            allRows = cn.tonglu.app.data.ScheduleBoard.ofDay(courses, weekday, week).map { course ->
                TodayWidgetModel.Row(
                    if (course.startPeriod == course.endPeriod) "${course.startPeriod}" else "${course.startPeriod}-${course.endPeriod}",
                    cn.tonglu.app.data.PeriodTable.timeText(course.startPeriod, course.endPeriod),
                    course.name, course.location, course.teacher,
                )
            },
        )
    }

    /**
     * 取竞赛 / 考试的倒计时。
     *
     * 三条数据源各自独立、**任一失败都不影响其它**（更不该影响课表）：
     *   · 考试 —— [cn.tonglu.app.data.exam.ExamStore]：本机 SharedPreferences，读得动就一定有值；
     *     顺手读一次首页那个「考试倒计时」开关（关掉就不显示考试那一条，见 [TodayWidgetDeadlines.pick]）。
     *   · 竞赛 —— [cn.tonglu.app.data.competition.CompetitionMirror]：订阅的本机镜像
     *     （服务端是唯一真相，镜像由 `CompetitionSubscriptionStore.refresh()` 覆盖写）。
     *     **小部件绝不在这里拉服务端**：桌面进程的刷新很短，拉网络只会让部件卡在旧画面上。
     *
     * ⚠️ `ExamStore.get().events.value` 是**纯读**：不调 `reload()`。
     * 那个函数会把过期的考试归档并**写盘**，而小部件刷新是分钟级的后台动作，
     * 在那里改用户数据是不合适的（谁写谁负责，归档由用户进页面时做）。
     */
    private fun loadDeadlines(
        context: Context,
        owner: String,
        nowMillis: Long,
    ): List<TodayWidgetDeadlines.Item> = runCatching {
        val exams = cn.tonglu.app.data.exam.ExamStore.get(context.applicationContext).events.value
        val examsEnabled = cn.tonglu.app.data.exam.ExamSettings.enabled(context.applicationContext)
        val subscriptions = cn.tonglu.app.data.competition.CompetitionMirror
            .read(context.applicationContext, owner)
        TodayWidgetDeadlines.pick(exams, examsEnabled, subscriptions, nowMillis)
    }.getOrDefault(emptyList())

    /**
     * 「没有数据」的统一出口 —— 各处 early return 与小部件的兜底都走这里，
     * 保证 [dateLabel] 这类必带字段不会漏填（漏了标题就会写成 "今天 · null"）。
     */
    fun empty(nowMillis: Long, deadlines: List<TodayWidgetDeadlines.Item> = emptyList()) = Snapshot(
        state = TodayWidgetModel.State.Empty,
        hasData = false,
        weekdayLabel = ScheduleText.shortWeekday(ScheduleText.weekdayOf(nowMillis)),
        dateLabel = ScheduleText.dateLabel(ScheduleText.weekdayOf(nowMillis), nowMillis),
        deadlines = deadlines,
    )

    // ── 日历 / 完整课表两个小组件（2026-10-16 拆分）─────────────────────────
    //
    // 为什么它们的取数也放在这里而不是各自开一层：三条规矩（owner 分桶、
    // applyPeriodTable、weekOf 算周次）写在类注释上，分开写等于给「某个部件漏一条」
    // 留后门。底下的 private 取数函数是共用的。

    /** [CalendarWidget] 要的一屏数据。 */
    data class CalendarSnapshot(
        val dateLabel: String,
        val weekdayLabel: String,
        /** 当前教学周，null = 没设开学日期（右侧不显示周次）。 */
        val week: Int? = null,
        val deadlines: List<TodayWidgetDeadlines.Item> = emptyList(),
    )

    /**
     * 「日历」部件的取数：日期 + 周次 + 倒计时，**不读课程**。
     *
     * week 仍要进 [ScheduleStore]（termStart 存在那里），但不需要
     * [ScheduleStore.applyPeriodTable] —— 那是给「第几节 → 几点」用的，日历不算时间。
     */
    suspend fun loadCalendar(context: Context, nowMillis: Long = System.currentTimeMillis()): CalendarSnapshot {
        val owner = runCatching { SessionStore.currentOwner(context) }
            .getOrDefault(SessionStore.LOCAL_OWNER)
        val deadlines = loadDeadlines(context, owner, nowMillis)
        val week = runCatching {
            val store = ScheduleStore(context.applicationContext, owner)
            ScheduleText.weekOf(store.termStart, nowMillis)
        }.getOrNull()
        val weekday = ScheduleText.weekdayOf(nowMillis)
        return CalendarSnapshot(
            dateLabel = ScheduleText.dateLabel(weekday, nowMillis),
            weekdayLabel = ScheduleText.shortWeekday(weekday),
            week = week,
            deadlines = deadlines,
        )
    }

    /** [loadCalendar] 的兜底出口（字段必填，原因同 [empty]）。 */
    fun emptyCalendar(nowMillis: Long) = CalendarSnapshot(
        dateLabel = ScheduleText.dateLabel(ScheduleText.weekdayOf(nowMillis), nowMillis),
        weekdayLabel = ScheduleText.shortWeekday(ScheduleText.weekdayOf(nowMillis)),
    )

    /** [WeekWidget] 要的一屏数据。 */
    data class WeekSnapshot(
        /** 纯函数 [WeekWidgetModel.build] 算好的整周网格（含每格的课名与颜色键）。 */
        val grid: WeekWidgetModel.Grid,
        /** 今天是周几（1~7，周一=1）—— 表头要把这一列高亮。 */
        val todayWeekday: Int,
        /** 课程名 → ARGB（与今日课表同一套 `buildCourseColors`，保证两边同色）。 */
        val courseColors: Map<String, Int> = emptyMap(),
        /** false = 没导入课表 / 未登录，显示「还没有课表」。 */
        val hasData: Boolean,
        val week: Int? = null,
        val weekStart: Long = ScheduleText.weekStartMillis(),
        val periodStarts: Map<Int, String> = emptyMap(),
    )

    /**
     * 「完整课表」部件的取数：整周的网格。
     *
     * 课表为空时也照常返回（[WeekWidgetModel.build] 对空列表返回 `rows=0` 的网格），
     * 由画的一侧决定显示「还没有课表」——这样「没数据」和「这学期没课」两种空态
     * 都能落到同一个出口。
     */
    suspend fun loadWeek(context: Context, nowMillis: Long = System.currentTimeMillis()): WeekSnapshot {
        val owner = runCatching { SessionStore.currentOwner(context) }
            .getOrDefault(SessionStore.LOCAL_OWNER)
        val store = runCatching {
            ScheduleStore(context.applicationContext, owner).also { it.applyPeriodTable() }
        }.getOrNull() ?: return WeekSnapshot(WeekWidgetModel.build(emptyList(), null), ScheduleText.weekdayOf(nowMillis), hasData = false)

        val courses = runCatching { store.loadCourses() }.getOrDefault(emptyList())
        val week = runCatching { ScheduleText.weekOf(store.termStart, nowMillis) }.getOrNull()
        val colors: Map<String, Int> = runCatching {
            cn.tonglu.app.ui.buildCourseColors(courses).mapValues { (_, color) -> color.toArgb() }
        }.getOrDefault(emptyMap())
        return WeekSnapshot(
            grid = WeekWidgetModel.build(courses, week),
            todayWeekday = ScheduleText.weekdayOf(nowMillis),
            courseColors = colors,
            hasData = courses.isNotEmpty(),
            week = week,
            weekStart = ScheduleText.weekStartMillis(nowMillis),
            periodStarts = cn.tonglu.app.data.PeriodTable.slots().associate { it.period to it.start },
        )
    }

    /**
     * 从「今天要显示的课」里算出**下一个需要刷新的时刻**（毫秒）。
     *
     * 为什么需要它：小部件必须自己知道该在什么时候重画 ——
     * updatePeriodMillis 最快也就 30 分钟且不准（见 today_widget_info.xml 的注释），
     * 而「08:45 下课 → 标题从『正在上课』变回『今天』」「上午的课全部上完 → 该显示下午的课」
     * 都是分钟级事件。少了这个，用户看到的会是「上一节课还在上」。
     *
     * 规则：
     *   · 有课且**正在上课** → 这节课**下课**的时刻（那一刻标题要变）
     *   · 有课但**没在上课** → 下一节课的**上课**时刻
     *   · 今天没课 / 没数据 → 明天 0 点
     *   · 兜底：任何异常都返回「1 小时后」，宁可早刷一次也不要漏刷。
     */
    suspend fun nextRefreshAt(context: Context, nowMillis: Long = System.currentTimeMillis()): Long {
        val oneHour = 60L * 60 * 1000
        // 🔴 显式写 lambda 的返回类型 Long：不写的话，`runCatching` 里的
        //    `nextRefreshAt(...)` 会和**本函数自己**（同名的 Context 重载）撞上，
        //    Kotlin 推不出 R 是谁，报 `Cannot infer type for type parameter 'R'`。
        val computed: Long? = runCatching<Long> {
            // 复用下一次取数的结果（顺带把「几点了」这件事只算一遍，避免跨天瞬间前后不一致）
            nextRefreshAtState(load(context, nowMillis).state, nowMillis)
        }.getOrNull()
        return computed ?: (nowMillis + oneHour)
    }

    /**
     * [nextRefreshAt] 的**纯函数**内核（不碰 Context）—— 抽出来是为了能在 JVM 单测里
     * 用固定时间戳验证「下课那一刻算没算对」。小部件没有 dump 可读，
     * 刷新时刻算错的表现只是「标题停在上节课」，肉眼要盯很久才发现。
     *
     * 规则（与 [nextRefreshAt] 的 KDoc 一致）：正在上课 → 下课时刻；
     * 否则 → 下一节上课时刻；再不然 → 明天 0 点。
     */
    internal fun nextRefreshAtState(state: TodayWidgetModel.State, nowMillis: Long): Long {
        val dayStart = ScheduleText.startOfDay(nowMillis)
        val tomorrow = dayStart + 24L * 3600 * 1000
        if (state !is TodayWidgetModel.State.Courses) return tomorrow

        val nowMinutes = TodayWidgetModel.minutesOfDay(nowMillis)
        val ongoing = state.ongoing
        if (ongoing != null) {
            endMinutes(ongoing.time)?.let { return dayStart + it * 60_000L + 1000 }
        }
        val nextStart = state.rows
            .mapNotNull { startMinutes(it.time) }
            .filter { it > nowMinutes }
            .minOrNull()
            ?: return tomorrow
        return dayStart + nextStart * 60_000L + 1000
    }

    /** "08:00-09:40" → 480（起始分钟）。格式不对返回 null（不猜）。 */
    internal fun startMinutes(timeText: String): Int? =
        timeText.substringBefore('-', "").takeIf { it.isNotBlank() }
            ?.let { cn.tonglu.app.data.PeriodTable.parseMinutes(it) }
            ?.takeIf { it > 0 }

    /** "08:00-09:40" → 580（结束分钟）。 */
    internal fun endMinutes(timeText: String): Int? =
        timeText.substringAfter('-', "").takeIf { it.isNotBlank() }
            ?.let { cn.tonglu.app.data.PeriodTable.parseMinutes(it) }
            ?.takeIf { it > 0 }
}
