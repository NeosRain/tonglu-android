package cn.tonglu.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * 「日历」小部件 —— 日期 / 星期 / 教学周 + 竞赛、考试倒计时。
 *
 * ## 为什么拆出来（2026-10-16 与用户确认）
 *
 * 用户原意就是「拆分多个小组件」：原来的「今日课表」三合一保留不动，
 * 日期和倒计时拆到这里 —— 它们都是「日子」类信息（今天几号、离某件事还有几天），
 * 跟「今天上什么课」是两回事；而「考研还有 12 天」这种事，跟日历放一起最顺。
 *
 * ## 版式
 *
 * 第一行：`10月16日 · 周五`（14sp Medium，左）+ `第7周`（11sp，右，没设开学日期就不显示）。
 * 下面就是倒计时行（画法与今日课表**同一份** [DeadlineRow]，天数靠右、临近加文字信号）。
 * 没有任何倒计时时显示一句「暂无考试或竞赛倒计时」—— 空态也要说清楚，不能白一块。
 *
 * ## 🔴 高度预算照 [CalendarWidgetLayout] 的算式走
 *
 * RemoteViews 放不下 = 静默裁掉（不崩不打日志）。最小档 62dp 只放得下
 * 「日期行 + 一条倒计时」，这是算出来的不是拍出来的 —— 改任何一个常量都要跑
 * `CalendarWidgetLayoutTest`。
 */
class CalendarWidget : GlanceAppWidget() {

    /**
     * 两档就够：倒计时最多两条（[TodayWidgetDeadlines.MAX_ITEMS]），
     * 更高的格子只会留白。
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetNavigation.selection(context, id)
        // 取数放在 provideContent 外面（同 TodayWidget：后台线程 + 读 Room/SharedPreferences）
        val snapshot = runCatching { TodayWidgetSource.loadCalendar(context) }
            .getOrElse { TodayWidgetSource.emptyCalendar(System.currentTimeMillis()) }

        provideContent {
            GlanceTheme {
                WidgetNavigation.Observe(context, initial, snapshot, { TodayWidgetSource.loadCalendar(context) }) { data, _ ->
                    CalendarContent(data)
                }
            }
        }
    }

    companion object {
        /** 62dp：日期行 + 一条倒计时（见 [CalendarWidgetLayout.needed]） */
        val SMALL = DpSize(180.dp, 62.dp)

        /** 80dp：日期行 + 两条倒计时 */
        val WIDE = DpSize(250.dp, 80.dp)
    }
}

@Composable
private fun CalendarContent(snapshot: TodayWidgetSource.CalendarSnapshot) {
    val height = LocalSize.current.height
    val shown = CalendarWidgetLayout.countdownRowsFor(height, snapshot.deadlines.size)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .widgetAppearance(LocalContext.current, "Calendar")
            .clickable(actionStartActivity(widgetOpenAppIntent(LocalContext.current)))
            // 🔴 vPad=20 是 CalendarWidgetLayout 算式的输入，两边必须一起改。
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // 日期与星期同一行：日历部件的第一眼就是「今天几号周几」，
                    // 拆两行会把 62dp 的最小档撑爆（这正是算式的结论）。
                    text = buildList {
                        if (snapshot.dateLabel.isNotBlank()) add(snapshot.dateLabel)
                        if (snapshot.weekdayLabel.isNotBlank()) add(snapshot.weekdayLabel)
                    }.joinToString(" · "),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                    maxLines = 1,
                )
                snapshot.week?.takeIf { it > 0 }?.let {
                    Text(
                        text = "第${it}周",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }

            if (shown > 0) {
                DeadlineRows(items = snapshot.deadlines, shown = shown)
            } else if (CalendarWidgetLayout.subFits(height)) {
                Spacer(modifier = GlanceModifier.height(CalendarWidgetLayout.subGap))
                Text(
                    text = "暂无考试或竞赛倒计时",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 桌面「添加小部件」列表的入口 */
class CalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget()

    override fun onEnabled(context: android.content.Context) {
        super.onEnabled(context)
        // 刷新走 TodayWidgetUpdater 的同一颗闹钟：三个部件吃的是同一份本机数据，
        // 各排各的闹钟只会多耗电还可能时刻不一致（见 TodayWidgetUpdater 的注释）。
        TodayWidgetUpdater.scheduleNext(context)
    }

    override fun onUpdate(
        context: android.content.Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        TodayWidgetUpdater.scheduleNext(context)
    }
}
