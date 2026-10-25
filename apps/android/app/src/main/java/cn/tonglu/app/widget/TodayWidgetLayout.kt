package cn.tonglu.app.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 小部件的**排版算术** —— 纯 Kotlin，不碰 Compose / Context，所以能被 JVM 单测钉住。
 *
 * ## 为什么要把「能放几行」单独抽出来
 *
 * Glance 画的是 **RemoteViews**：不能滚动，放不下就是**直接被裁掉**
 * （用户看到的是最后一行只剩半截字），而且**不报错、不打日志**。
 * 上一版在 TodayWidget 里写了个 `maxRowsFor`，阈值是拍脑袋定的
 * （`<140dp → 2 行`），实际一算：一个课程块要 60dp 上下，
 * 而最小的 3×2 尺寸（110dp）扣掉卡片 padding 和标题行只剩 43dp ——
 * **一行都放不下**，照样被裁。
 *
 * 所以这里改成「先把每块占多少 dp 写成常量，再算能塞几块」，
 * 并用单测按真实尺寸逐个验（见 TodayWidgetLayoutTest）。
 *
 * ## 各个数字的来路（都是行高，不是猜的）
 *
 * Android 单行文字高度 ≈ `字号 × 1.3`（含默认行距）：
 *   · 14sp → 18dp，13sp → 17dp，11sp → 14dp
 *
 * ## 倒计时（2026-10-16 新增）怎么占位
 *
 * 倒计时是**额外的行**，插在课程块下面，每行 [cdRow]。它有两条路可走：
 *
 * 1. **独立行**（[Plan.countdownRows]，`inline = false`）：一条 = [gap] + [cdRow]。
 * 2. **顶进标题的副行**（[Plan.inline] == true）：**不额外占高** ——
 *    标题第二行本来就是 11sp 的一行字（「10月16日 · 周四 · 第7周」），
 *    倒计时也是 11sp 一行，两者互换高度不变。
 *
 * 为什么需要第 2 条：最小尺寸 3×2 只有 110dp，扣掉 padding 20 + 标题 39 只剩 51dp，
 * 而「一节课 43 + 一条倒计时 24」= 67dp 塞不下。这时**不是不显示倒计时**，
 * 而是把它放到副行去 —— 否则用户拖上桌面（绝大多数人就是 3×2）根本看不到这个功能。
 * 代价是副行不再显示日期/周次；只在**真的放不下**时才做这个交换。
 */
object TodayWidgetLayout {

    /** 卡片上下 padding 合计（Box 的 top / bottom 各 10dp） */
    val vPad: Dp = 20.dp

    /** 标题行占的高度：主标题 14sp→18dp + 1dp 间隙 + 副标题 11sp→14dp + 标题下方 6dp */
    val header: Dp = 39.dp

    /** 相邻课程块之间的间距 */
    val gap: Dp = 6.dp

    /**
     * 一块课程占的高度：上下 padding 各 6dp + 课名行 13sp→17dp + 「节次·时间」行 11sp→14dp。
     *
     * 教室/教师挂在**「节次·时间」那一行的右侧**，不单独占行 ——
     * 单独占行会让每块多 14dp，最小的 3×2 尺寸就一行都放不下了。
     */
    val row: Dp = 43.dp

    /**
     * 一条倒计时占的高度：11sp 文字 14dp + 上下各 2dp 余量。
     *
     * 余量是刻意留的：倒计时行里可能有「临近」这种小字号徽标，
     * 行高按纯 11sp 文字算（14dp）会在某些字体缩放下顶出 1~2dp。
     */
    val cdRow: Dp = 18.dp

    /** 倒计时块与上方内容之间的间距（与课程块之间的间距同一个值） */
    val cdGap: Dp = 6.dp

    /** 最多显示几条倒计时（一条竞赛 + 一条考试）。再多会把课全挤掉。 */
    const val MAX_COUNTDOWNS = 2

    /** 卡片左右 padding 合计 */
    val hPad: Dp = 24.dp

    // ── 非「今天有课」的几种状态，正文各行的高度（供 countdownRowsFor 算基础高度） ──

    /** 正文主标题（「今天没课」/「假期中」）14sp */
    val bodyTitle: Dp = 18.dp

    /** 正文主标题与说明之间的间隙 */
    val bodyGap: Dp = 3.dp

    /** 正文说明行（「3 天后 · 周一」/「接下来一周都没有课」）11sp */
    val bodySub: Dp = 14.dp

    /** 正文两行（主标题 + 间隙 + 说明）合计高度 */
    val bodyText: Dp = bodyTitle + bodyGap + bodySub

    /** 「下一节课」那一块占的高度：与上方内容的间距 + 一块课程 */
    val nextCourseBlock: Dp = gap + row

    // ── 块高度 ───────────────────────────────────────────────────────────

    /** [count] 块课程占多高（块之间含间距）。`count <= 0` 时为 0。 */
    fun courseBlock(count: Int): Dp =
        if (count <= 0) 0.dp else row * count.toFloat() + gap * (count - 1).toFloat()

    /** [count] 条**独立行**倒计时占多高（含与上方内容的间距）。`count <= 0` 时为 0。 */
    fun countdownBlock(count: Int): Dp =
        if (count <= 0) 0.dp else cdGap + cdRow * count.toFloat()

    /**
     * 放 [count] 块课程**至少**需要多高的卡片。
     *
     * 🔴 `Dp` 只有 `× Float` 的重载（没有 `× Int`），所以必须写成
     *    `row * count.toFloat()` 而不是 `count * row` —— 后者编译不过
     *    （Kotlin 会去找 `Int.times(Dp)`，而那个不存在）。
     */
    fun needed(count: Int): Dp = needed(count, 0)

    /** 放 [courses] 块课程 + [countdowns] 条倒计时（独立行）至少需要多高的卡片。 */
    fun needed(courses: Int, countdowns: Int): Dp =
        vPad + header + courseBlock(courses) + countdownBlock(countdowns)

    /** 在「已经占掉 [base] 高度」的前提下，再放 [countdowns] 条独立倒计时是否放得下。 */
    fun fits(height: Dp, base: Dp, countdowns: Int): Boolean = base + countdownBlock(countdowns) <= height

    /**
     * 给定卡片高度，能放几节课（不显示倒计时）。
     *
     * 做法是把 [needed] 从小到大试到 [TodayWidgetModel.MAX_ROWS]，
     * 取最后一个「装得下」的量 —— 比解不等式直观，也更容易在单测里复核。
     *
     * @return 0 = 连一行都放不下；调用方这时要退回「只显示标题 + 共 N 节」，别硬塞，
     *         硬塞的结果是被裁成半行，比不放还难看。
     */
    fun rowsFor(height: Dp): Int = maxCourses(height, 0)

    /** 已经要给 [countdowns] 条独立倒计时留位置时，最多能放几节课。 */
    fun maxCourses(height: Dp, countdowns: Int = 0): Int {
        var n = 0
        while (n < TodayWidgetModel.MAX_ROWS && needed(n + 1, countdowns) <= height) n += 1
        return n
    }

    /**
     * 在「基础内容已经占掉 [base]」的前提下，最多能放几条独立倒计时行。
     *
     * 给非「今天有课」的状态（今天没课 / 假期中）用：那些状态的正文高度各不相同，
     * 但「还剩多少位置给倒计时」是同一个算法。
     */
    fun countdownRowsFor(height: Dp, base: Dp, want: Int): Int {
        var n = 0
        val cap = want.coerceAtMost(MAX_COUNTDOWNS)
        while (n < cap && fits(height, base, n + 1)) n += 1
        return n
    }

    /**
     * 非「今天有课」的状态：独立行**一条都放不下**时，是否该把第一条倒计时顶进标题副行。
     *
     * 为什么需要它：`Plan.countdownInline` 只在有课的时候会被 [plan] 给出
     * （[plan] 的第 3 步写死了 `floor > 0` 才走 inline），而「今天没课 / 假期中 /
     * 还没导入课表」走的是 [countdownRowsFor] —— 在最小的 3×2（110dp）上，
     * 正文两行（94dp）之后一条独立行都放不下，**用户一条倒计时都看不到**。
     * 而这三种状态恰恰是最需要倒计时的（没课可看，只能看「还有几天」）。
     *
     * 口径与 [Plan.countdownInline] 一致：宁可让副行不再显示日期/周次，
     * 也不能让这个功能在默认尺寸上整个消失。
     *
     * @param base 该状态正文已经占掉的高度（`EmptyBody` / `RestBody` / `NoUpcomingBody` 各自传入）。
     */
    fun inlineForBody(height: Dp, base: Dp, countdownCount: Int): Boolean =
        countdownCount > 0 && countdownRowsFor(height, base, countdownCount) == 0

    /**
     * 一次排版决策：几节课 + 几条倒计时 + 倒计时要不要顶进标题副行。
     *
     * @param countdownRows **独立行**的条数。🔴 顶进副行的那条**不算在这里**
     *        （[countdownInline] == true 时它必为 0）—— 渲染层按「独立行画 [countdownRows] 条，
     *        inline 再画一条」来画，两个字段若不互斥就会把同一条倒计时画两遍
     *        （一遍在副行、一遍在底部，而底部那条会超出预算被裁掉半截）。
     * @param countdownInline 倒计时改用**标题的副行**显示（只放得下 1 条，且副行不再显示日期/周次）。
     *        代价是真实的，但比「3×2 上压根看不到倒计时」好 —— 见类注释。
     */
    data class Plan(
        val courseRows: Int,
        val countdownRows: Int,
        val countdownInline: Boolean = false,
    )

    /**
     * 决定怎么排。
     *
     * 取舍顺序（每一步都有单测钉住，改的时候别只看前一步）：
     * 1. **课程优先**：在不牺牲任何课程的前提下，能放几条倒计时就放几条（从多到少试）。
     * 2. **让出课程行**：还放不下就一行一行地牺牲课程（**最多牺牲到只剩 1 行**）换倒计时。
     *    —— 课程是这个部件的本职，不能为了倒计时把课全挤没。
     * 3. **顶进副行**：连「1 行课 + 1 条倒计时」都放不下（3×2 = 110dp 就是这种情况），
     *    把倒计时放进标题副行 —— 不额外占高，代价是那一行不再显示日期/周次。
     *
     * @param courseCount 今天总共有几节课（不是能放几节）。
     * @param countdownCount 有几条倒计时可显示（已经过 [TodayWidgetDeadlines] 筛选）。
     */
    fun plan(height: Dp, courseCount: Int, countdownCount: Int): Plan {
        val courses = courseCount.coerceIn(0, TodayWidgetModel.MAX_ROWS)
        val want = countdownCount.coerceIn(0, MAX_COUNTDOWNS)
        // 「今天有课」时无论如何留 1 行；今天没课时 floor = 0（全是倒计时也不违规）。
        val floor = if (courseCount > 0) 1 else 0

        if (want > 0) {
            // 1. 课程优先
            val noTrade = minOf(courses, maxCourses(height, 0))
            for (cd in want downTo 1) {
                if (noTrade >= floor && needed(noTrade, cd) <= height) {
                    return Plan(noTrade, cd, countdownInline = false)
                }
            }
            // 2. 让出课程行（降到 floor 为止）
            var c = noTrade - 1
            while (c >= floor) {
                for (cd in want downTo 1) {
                    if (needed(c, cd) <= height) return Plan(c, cd, countdownInline = false)
                }
                c -= 1
            }
            // 3. 顶进副行（不额外占高）。只对「今天有课」做：没课时上面两步不会失败。
            //    🔴 countdownRows 必须是 0：那条倒计时已经由副行画了，
            //    渲染层还会按 countdownRows 画独立行，不归零就是同一条画两遍。
            if (noTrade >= floor && floor > 0) return Plan(noTrade, 0, countdownInline = true)
        }

        return Plan(minOf(courses, maxCourses(height, 0)), 0, countdownInline = false)
    }
    // ── 精确自适应（2026-10-18：LazyColumn 渲染链）────────────────────────
    //
    // 上面那套 rowsFor/plan 是「固定高度、最多四块」时代的算术；现在 TodayWidget 用
    // LazyColumn 承载全部课程（可滚动），要让「列表正好填满卡片剩余高度」，就必须知道
    // 标题行和日期行**真实**占了多少 —— 这两个高度由字体度量决定，写死常数不可行。
    //
    // 用户 2026-10-18 反馈的两件事是同一处根因的两面：
    //   · 「显示不全」：列表高度算大 → 最后一门课被裁；
    //   · 「今天/明天切换后底部一大块空白」：SizeMode.Responsive 下 LocalSize.current 是
    //     **声明的尺寸档**（如 180×210），不是宿主真实视口，固定减 50dp 自然对不上。
    // 修法见 TodayWidget：改用 SizeMode.Exact（LocalSize = 真实视口）+ 这里按实测行高算高度。

    /** 卡片上下 padding 合计（TodayWidget 里 Column 的 vertical = 6dp，改一处必须改两处） */
    val cardVPad: Dp = 12.dp

    /** 日期导航行里文字上下的内边距合计（WidgetDateHeader 每个 Text padding(vertical = 5dp)） */
    val headerTextPad: Dp = 10.dp

    /** 日期副标题与滚动区之间的间隙（TodayWidgetContent 的 Spacer） */
    val listGap: Dp = 4.dp

    /** 滚动区最小高度：再小连一行字都放不下，宁可让滚动区到此为止（而不是被宿主裁掉） */
    val minList: Dp = 44.dp

    /**
     * SizeMode.Exact 的尺寸来自 AppWidgetManager 的 options（宿主按 **cell** 报的近似值），
     * 比真实视口可能大 2~3dp。不预留这点余量，卡片底部 padding 会被列表吃掉
     * （实测 1200×2608@480 Launcher3：options 高度 226.3dp vs 真实 224dp → 底部只剩 3.7dp）。
     * 留 4dp：正常情况下最多在底部留 4dp 白（6dp padding 里看不出来），换来列表不会越过卡片底边。
     */
    val exactSlack: Dp = 4.dp

    /**
     * 滚动区高度 = 卡片高度 − 上下 padding − 标题行 − 日期行 − 间隙。
     *
     * [headerLine] / [dateLine] 由渲染层用系统字体**实测**（见 [lineHeightDp]）后传入：
     * 小部件里的中文走 CJK 回退字体，实测 10sp≈14.7dp、12sp≈17.7dp（约 1.47em），
     * 按「字号×1.3」估会低估 10~20%，结果就是最后一行课被宿主静默裁掉。
     */
    fun listHeight(card: Dp, headerLine: Dp, dateLine: Dp): Dp =
        (card - cardVPad - headerLine - headerTextPad - dateLine - listGap - exactSlack)
            .coerceAtLeast(minList)
}
