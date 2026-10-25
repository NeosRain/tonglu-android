package cn.tonglu.app.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「完整课表」小部件的**排版算术** —— 纯 Kotlin，能被 JVM 单测钉住。
 *
 * ## 结构
 *
 * 表头一行（周一~周日，7 列等宽）+ 下面一个 `行数 × 7` 的网格。
 * 每行 = 一个节次，行高 [rowUnit]；一门课占 `span` 行。
 * 7 列等宽靠 Glance 的 `defaultWeight()`，所以**宽度不进算式** ——
 * 算式只管「能画到第几节」，宽度放不放得下由字号（8sp 课名缩写）兜。
 *
 * ## 各个数字的来路
 *
 *   · 9sp 表头 → 12dp，留 2dp 余量 = [header] 14dp；
 *   · 一行 = 课名 8sp（≈11dp）+ 上下各 1.5dp = [rowUnit] 14dp，其中 [rowGap] 1dp 是格与格的缝。
 */
object WeekWidgetLayout {

    /** 卡片上下 padding 合计（Box 的 top / bottom 各 8dp） */
    val vPad: Dp = 16.dp

    /** 表头行高（周一~周日） */
    val header: Dp = 14.dp

    /** 一行的总高（含格与格之间的缝） */
    val rowUnit: Dp = 14.dp

    /** 格与格之间的缝（一格内部没有缝，缝画在行与行之间） */
    val rowGap: Dp = 1.dp

    /** [rows] 行网格的总高 */
    fun needed(rows: Int): Dp = vPad + header + rowUnit * rows.toFloat()

    /** 空态（「还没有课表」两行文字）的总高：14sp 主标题 + 3 间隙 + 11sp 说明 */
    val emptyNeeded: Dp = vPad + 18.dp + 3.dp + 14.dp

    /**
     * 给定卡片高度能画到第几节（0 = 连表头都勉强，什么都不画）。
     *
     * 🔴 上限是 [WeekWidgetModel.MAX_PERIODS]：网格行数由**数据**（最大节次）决定，
     * 这里算出来的只是「高度允许画到第几节」，两者取小。
     */
    fun rowsFor(height: Dp, dataRows: Int): Int {
        val budget = ((height - vPad - header).value / rowUnit.value).toInt()
        return budget.coerceIn(0, dataRows.coerceAtMost(WeekWidgetModel.MAX_PERIODS))
    }

    /** 一格 `span` 行要画多高（缝只算在格与格之间，所以减一个 [rowGap]）。 */
    fun cellHeight(span: Int): Dp = if (span <= 0) 0.dp else rowUnit * span.toFloat() - rowGap

    // ── 精确自适应（2026-10-18：位图 + LazyColumn 渲染链）──────────────────
    //
    // 上面 rowsFor 那套是「静态网格、按节高算能画几行」时代的算术；现在完整课表用
    // 两节一条位图带装进 LazyColumn（可上下滚动到 12 节），滚动区高度必须按**真实**头部
    // 高度倒减，否则底部会压掉最后一条带（用户 2026-10-18 截图里第 6 行被裁）。

    /** 卡片上下 padding 合计（WeekWidget 里 Column 的 padding = 8dp） */
    val cardVPad: Dp = 16.dp

    /** 日期导航行文字上下的内边距合计（WidgetDateHeader 每个 Text padding(vertical = 5dp)） */
    val headerTextPad: Dp = 10.dp

    /** 日期导航行与「周几/日期」表头之间的距离（Row padding(bottom = 5dp)） */
    val dayHeaderGap: Dp = 5.dp

    /** 滚动区最小高度：再小连一条位图带都放不下 */
    val minList: Dp = 48.dp

    /**
     * SizeMode.Exact 的尺寸来自 AppWidgetManager 的 options（宿主按 cell 报的近似值），
     * 比真实视口可能大 2~3dp；留 4dp 防列表把卡片底部 padding 吃掉（今日课表同一口径）。
     */
    val exactSlack: Dp = 4.dp

    /**
     * 滚动区高度 = 卡片高度 − 上下 padding − 标题行 − 「周几」行 − 「日期」行 − 间距。
     *
     * [headerLine] / [dayLine] / [dateLine] 由渲染层用系统字体**实测**后传入
     * （中文走 CJK 回退字体，固定系数估不准）。
     */
    fun listHeight(card: Dp, headerLine: Dp, dayLine: Dp, dateLine: Dp): Dp =
        (card - cardVPad - headerLine - headerTextPad - dayLine - dateLine - dayHeaderGap - exactSlack)
            .coerceAtLeast(minList)
}
