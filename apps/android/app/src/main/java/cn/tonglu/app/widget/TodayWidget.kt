package cn.tonglu.app.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import cn.tonglu.app.data.ScheduleText
import kotlinx.coroutines.CancellationException

/**
 * 「今日课表」桌面部件：标题日期 + 可滚动的今日课表 + 竞赛/考试倒计时。
 *
 * ## 为什么是 SizeMode.Exact（2026-10-18 修用户反馈）
 *
 * 用户截图反馈两件事：课程被裁、切到明天后底部一大块空白。根因是**尺寸口径**：
 * Responsive 下 LocalSize.current 是「声明尺寸档」里离宿主最近的那个（例如宿主真实
 * 352×224dp，选中 180×210 档 → 列表按 210−50=160dp 排），于是列表既填不满卡片
 * （留一大块白），也放不下全部课程（超出宿主的部分被裁掉）。
 *
 * Exact 下 LocalSize 就是宿主真实视口，配合 TodayWidgetLayout.listHeight（按系统字体
 * 实测行高从卡片高度倒减）列表正好铺满剩余空间：不裁、不留白、可滚动。
 * （完整课表 WeekWidget 从 0.9.4 起就是 Exact，桌面实测底部空白随之消除 —— 同一口径。）
 */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetNavigation.selection(context, id)
        val date = WidgetNavigation.date(initial.dayOffset)
        val snapshot = try { TodayWidgetSource.load(context, date) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("TodayWidget", "课表读取失败：${error.javaClass.simpleName}")
            TodayWidgetSource.empty(date)
        }
        provideContent { GlanceTheme {
            WidgetNavigation.Observe(context, initial, snapshot, { TodayWidgetSource.load(context, it) }) { data, offset ->
                TodayContent(context, data, offset)
            }
        } }
    }
    companion object {
        // 旧 Responsive 尺寸档：现在 sizeMode 是 Exact，这三档只剩单测在引用
        // （TodayWidgetLayoutTest 用它们验旧算术），保留是为了不悄悄删掉测试锚点。
        val SMALL = DpSize(180.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val TALL = DpSize(180.dp, 210.dp)
    }
}

@Composable
private fun TodayContent(context: Context, snapshot: TodayWidgetSource.Snapshot, offset: Int) {
    // 标题/日期行的真实行高：中文走 CJK 回退字体，按「字号×1.3」估会低估 ~20%。
    // 标题行按**最高的那个孩子**（中间标题 12sp）量，不是按钮的 11sp —— 行高差点 1dp，
    // 列表就会多算 1dp、把最后一行课压到卡片外面去。
    val headerLine = lineHeightDp(context, 12f, "‹ 前日 回到今天 · 第7周 后日 ›").dp
    val dateLine = lineHeightDp(context, 10f, snapshot.dateLabel + " " + snapshot.weekdayLabel + " · 第7周").dp
    val listHeight = TodayWidgetLayout.listHeight(LocalSize.current.height, headerLine, dateLine)
    Column(GlanceModifier.fillMaxSize().widgetAppearance(context, "Today").padding(horizontal = 10.dp, vertical = 6.dp)) {
        val ongoing = (snapshot.state as? TodayWidgetModel.State.Courses)?.ongoing
        WidgetDateHeader(if (offset == 0 && ongoing != null) "正在上课" else "今天", "today", offset)
        Text("${snapshot.dateLabel} ${snapshot.weekdayLabel}" + (snapshot.week?.let { " · 第${it}周" } ?: ""),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp), maxLines = 1)
        Spacer(GlanceModifier.height(TodayWidgetLayout.listGap))
        // 列表高度 = 宿主真实高度 − 标题/日期实测高度（TodayWidgetLayout.listHeight）。
        // 不用 defaultWeight：部分桌面（HyperOS）会把带 weight 的集合量成 0 高，内容全没。
        LazyColumn(GlanceModifier.fillMaxWidth().height(listHeight)) {
            if (snapshot.allRows.isEmpty()) item {
                Text(if (!snapshot.hasData) "打开同路导入课表" else "这一天没有课",
                    modifier = GlanceModifier.padding(vertical = 8.dp).clickable(actionStartActivity(widgetOpenAppIntent(context))),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp))
            }
            snapshot.allRows.forEachIndexed { index, row -> item(itemId = index.toLong()) {
                TodayCourse(context, row, snapshot.courseColors[row.name.trim()], offset == 0 && row == ongoing)
            } }
            if (offset == 0 && snapshot.state is TodayWidgetModel.State.Rest) item {
                val rest = snapshot.state
                Column(GlanceModifier.fillMaxWidth()) {
                    Text("下一次 · ${rest.gapDays}天后 ${rest.weekdayLabel}",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp))
                    TodayCourse(context, rest.next, snapshot.courseColors[rest.next.name.trim()])
                }
            }
            (if (offset == 0) snapshot.deadlines else emptyList()).forEach { deadline -> item {
                Column(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)) { DeadlineRow(deadline, inline = true) }
            } }
        }
    }
}

@Composable
private fun TodayCourse(context: Context, row: TodayWidgetModel.Row, color: Int?, ongoing: Boolean = false) {
    val foreground = if (color != null) ColorProvider(Color.White) else GlanceTheme.colors.onSurface
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 5.dp)) {
        Column(GlanceModifier.fillMaxWidth().background(color?.let { ColorProvider(Color(it)) }
            ?: GlanceTheme.colors.surfaceVariant).widgetRounded(WIDGET_BLOCK_RADIUS)
            .clickable(actionStartActivity(widgetOpenAppIntent(context))).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(row.name + if (ongoing) " · 正在上" else "", style = TextStyle(color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 2)
            Text("${row.periods}节 · ${row.time}", style = TextStyle(color = foreground, fontSize = 10.sp), maxLines = 1)
            val place = cn.tonglu.app.ui.shortPlace(row.location) ?: row.teacher.orEmpty()
            if (place.isNotBlank()) Text(place, style = TextStyle(color = foreground, fontSize = 10.sp), maxLines = 1)
        }
    }
}

/** 桌面「添加小部件」列表的入口：系统靠这个 receiver 认识我们的部件 */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    /**
     * 用户刚把部件拖到桌面上 —— 立刻排一次刷新。
     *
     * 不做这一步的后果：部件会一直显示**拖进来的那一刻**的画面，
     * 直到下一次系统碰巧刷新它（可能几小时）。用户第一眼看到的就是旧数据，
     * 直接判定「这功能坏了」。
     */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TodayWidgetUpdater.scheduleNext(context)
    }

    /** 最后一个部件被移除 —— 取消闹钟，别继续空转耗电 */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        TodayWidgetUpdater.cancel(context)
    }

    /**
     * 部件被 resize / 被系统要求更新 —— 重画一次。
     *
     * `R.drawable.ic_widget_preview` 对应的是选择器预览，不在这里。
     * 这里只负责让内容跟上（例如用户从 3×2 拉到 3×4，要能多显示几行）。
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        TodayWidgetUpdater.scheduleNext(context)
    }
}
