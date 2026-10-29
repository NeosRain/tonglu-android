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
 * 「完整课表」桌面部件：七天网格 + 上下滚动的 LazyColumn（不是被裁的静态缩略图）。
 *
 * ## 清晰度：位图按设备像素渲染（2026-10-18 修用户反馈）
 *
 * 旧实现把位图 scale 压到 2 以下（1200×2608@480 上为 1.61），Bitmap 被 Image(FillBounds)
 * 放大 1.87 倍 → 字发虚。现在 scale = 设备密度（bitmap px = 屏幕 px），预算不够才降级。
 * ## 高度：滚动区按实测头部高度倒减（不再固定减 64dp），最后一条网格带不会被卡片下沿裁掉。
 */
class WeekWidget : GlanceAppWidget() {
    // Use the host's actual viewport, avoiding unused space between fixed size buckets.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetNavigation.selection(context, id)
        val date = WidgetNavigation.date(initial.dayOffset)
        val snapshot = try { TodayWidgetSource.loadWeek(context, date) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("WeekWidget", "课表读取失败：${error.javaClass.simpleName}")
            TodayWidgetSource.WeekSnapshot(WeekWidgetModel.build(emptyList(), null),
                ScheduleText.weekdayOf(date), hasData = false, weekStart = ScheduleText.weekStartMillis(date))
        }
        provideContent { GlanceTheme {
            WidgetNavigation.Observe(context, initial, snapshot, { TodayWidgetSource.loadWeek(context, it) }) { data, offset ->
                WeekContent(context, data, offset)
            }
        } }
    }

    companion object {
        val SMALL = DpSize(250.dp, 160.dp)
        val WIDE = DpSize(300.dp, 220.dp)
        val LARGE = DpSize(350.dp, 320.dp)
    }
}

@Composable
private fun WeekContent(context: Context, snapshot: TodayWidgetSource.WeekSnapshot, offset: Int) {
    // 头部三行的真实行高：中文走 CJK 回退字体，不能按「字号 × 1.3」估（见 lineHeightDp）。
    val headerLine = lineHeightDp(context, 12f, "‹ 上周 第7周 · 回本周 下周 ›").dp
    val dayLine = lineHeightDp(context, 10f, "一 今天 六").dp
    val dateLine = lineHeightDp(context, 8f, "10/12").dp
    // 滚动区高度 = 宿主真实高度 − 头部实测高度（旧实现固定减 64dp，实测头部约 70dp →
    // 第 6 行被卡片下沿裁掉，用户 2026-10-18 截图里能看到）。
    val listHeight = (WeekWidgetLayout.listHeight(LocalSize.current.height, headerLine, dayLine, dateLine) - widgetOpenFooterHeight(context)).coerceAtLeast(1.dp)
    val width = (LocalSize.current.width.value - 16f).coerceAtLeast(160f)
    val foreground = GlanceTheme.colors.onSurface.getColor(context)
    val empty = GlanceTheme.colors.surfaceVariant.getColor(context).copy(alpha = .32f)
    val fallback = GlanceTheme.colors.surfaceVariant.getColor(context)
    val display = context.resources.displayMetrics
    val bandCount = if (snapshot.hasData) (WeekGridArtwork.rows(snapshot) + 1) / 2 else 0
    // Exact 会把桌面提供的多个尺寸合成一份 RemoteViews，位图预算是整份共用。
    val layouts = if (android.os.Build.VERSION.SDK_INT >= 31) {
        maxOf(3, LocalAppWidgetOptions.current.getParcelableArrayList<android.util.SizeF>(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES)?.size ?: 0)
    } else 3
    // 按设备像素 1:1 渲染（模糊的根因与预算见 WeekGridArtwork.scaleFor）。
    val bitmapScale = WeekGridArtwork.scaleFor(
        width, bandCount, display.widthPixels, display.heightPixels, display.density, layouts,
    )
    val bands = remember(snapshot, width, foreground, empty, fallback, bitmapScale) {
        if (!snapshot.hasData) emptyList() else (0 until bandCount).map {
            WeekGridArtwork.band(snapshot, it, width, foreground, empty, fallback, bitmapScale)
        }
    }
    Column(GlanceModifier.fillMaxSize().widgetAppearance(context, "Week").clickable(actionStartActivity(widgetOpenAppIntent(context))).padding(8.dp)) {
        val title = snapshot.week?.let { "第${it}周" } ?: "完整课表"
        WidgetDateHeader(title, "week", offset)
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 5.dp)) {
            Spacer(GlanceModifier.width(WeekGridArtwork.GUTTER_DP.dp))
            (1..7).forEach { day ->
                val today = offset == 0 && day == snapshot.todayWeekday
                Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (today) "今天" else ScheduleText.shortWeekday(day).removePrefix("周"),
                        style = TextStyle(color = if (today) WIDGET_ACCENT_PROVIDER else GlanceTheme.colors.onSurface,
                            fontSize = 10.sp), maxLines = 1)
                    Text(ScheduleText.dateLabel(day, snapshot.weekStart).removeSuffix("日").replace("月", "/"),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 8.sp), maxLines = 1)
                }
            }
        }
        if (bands.isEmpty()) {
            Text("还没有课表", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
            Text("打开苔米林导入课表", modifier = GlanceModifier.padding(top = 8.dp)
                .clickable(actionStartActivity(widgetOpenAppIntent(context))),
                style = TextStyle(color = WIDGET_ACCENT_PROVIDER, fontSize = 12.sp))
        } else {
            LazyColumn(GlanceModifier.fillMaxWidth().height(listHeight)) {
                bands.forEachIndexed { index, bitmap ->
                    item(itemId = index.toLong()) {
                        val names = snapshot.grid.days.flatten().filter {
                            it.endRow >= index * 2 + 1 && it.startRow <= index * 2 + 2
                        }.joinToString { it.name }
                        Image(ImageProvider(bitmap), "第${index * 2 + 1}至${index * 2 + 2}节：$names",
                            modifier = GlanceModifier.fillMaxWidth().height(WeekGridArtwork.BAND_DP.dp)
                                .clickable(actionStartActivity(widgetOpenAppIntent(context))),
                            contentScale = ContentScale.FillBounds)
                    }
                }
                if (snapshot.grid.dropped > 0) item {
                    Text("另有 ${snapshot.grid.dropped} 门课超出${WeekWidgetModel.MAX_PERIODS}节，请在应用查看",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp))
                }
            }
        }
        WidgetOpenFooter(context)
    }
}

/** 桌面「添加小部件」列表的入口 */
class WeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 刷新走 TodayWidgetUpdater 的同一颗闹钟（原因同 CalendarWidgetReceiver）
        TodayWidgetUpdater.scheduleNext(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        TodayWidgetUpdater.scheduleNext(context)
    }
}
