package cn.tonglu.app.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「日历」小部件的**排版算术** —— 纯 Kotlin，不碰 Compose / Context，能被 JVM 单测钉住。
 *
 * ## 这块部件显示什么（2026-10-16 拆分小组件时与用户确认的口径）
 *
 * 「日历」= **日期 / 星期 / 教学周 + 竞赛、考试倒计时**。
 * 原三合一的「今日课表」保留不动；日期和倒计时拆到这里，是因为它们都是
 * 「日子」类信息（今天几号、离某件事还有几天），跟「今天上什么课」是两回事。
 *
 * ## 为什么每个数字都要进算式
 *
 * Glance 画的是 RemoteViews：**不能滚动，放不下就是静默裁掉**（不崩、不打日志）。
 * 今天的教训见 [TodayWidgetLayout] 的类注释 —— 阈值拍脑袋定的那一版，
 * 真实表现是最后一行只剩半截字。这里沿用同一套做法：先把每块占多少 dp 写成常量，
 * 再由算式推出能放几条。
 *
 * ## 各个数字的来路（都是行高，不是猜的；Android 单行文字 ≈ 字号 × 1.3）
 *
 *   · 14sp → 18dp（日期行）；11sp → 14dp（说明行）；倒计时行沿用 [TodayWidgetLayout.cdRow]。
 */
object CalendarWidgetLayout {

    /** 卡片上下 padding 合计（Box 的 top / bottom 各 10dp，与今日课表同一档） */
    val vPad: Dp = 20.dp

    /** 日期行：「10月16日 · 周五」（14sp，Medium），右侧「第7周」（11sp） */
    val dateRow: Dp = 18.dp

    /** 日期行与说明行之间的间隙 */
    val subGap: Dp = 3.dp

    /** 说明行（没有倒计时时的「暂无考试或竞赛倒计时」提示，11sp） */
    val subRow: Dp = 14.dp

    /** 一条倒计时占的高度（沿用今日课表那套：11sp 文字 14dp + 上下各 2dp 余量） */
    val cdRow: Dp = 18.dp

    /** 倒计时块与上方内容的间距 */
    val cdGap: Dp = 6.dp

    /** 最多显示几条倒计时（与 [TodayWidgetDeadlines.MAX_ITEMS] 同源，多要也没有） */
    const val MAX_COUNTDOWNS = 2

    /** [count] 条倒计时占多高（含与上方内容的间距）。`count <= 0` 时为 0。 */
    fun countdownBlock(count: Int): Dp =
        if (count <= 0) 0.dp else cdGap + cdRow * count.toFloat()

    /** 只有日期行时的总高：`vPad + dateRow + countdownBlock(n)` */
    fun needed(countdowns: Int): Dp = vPad + dateRow + countdownBlock(countdowns)

    /** 带说明行（空态提示）时的总高 */
    fun neededWithSub(countdowns: Int): Dp = needed(countdowns) + subGap + subRow

    /** 给定卡片高度，能放几条独立倒计时行（与 [TodayWidgetLayout.countdownRowsFor] 同一算法）。 */
    fun countdownRowsFor(height: Dp, want: Int): Int {
        var n = 0
        val cap = want.coerceAtMost(MAX_COUNTDOWNS)
        while (n < cap && needed(n + 1) <= height) n += 1
        return n
    }

    /**
     * 一条倒计时都放不下时，说明行还放不放得下（决定空态怎么画）。
     *
     * 为什么单独一个函数：空态是「日期行 + 一句提示」，它有自己的高度预算；
     * 不加判断直接画，62dp 的最小档就会把提示裁成半截 —— 又是静默裁掉。
     */
    fun subFits(height: Dp): Boolean = neededWithSub(0) <= height
}
