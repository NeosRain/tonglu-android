package cn.tonglu.app.widget

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.ScheduleText
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WidgetPreviewData(
    val today: TodayWidgetSource.Snapshot,
    val week: TodayWidgetSource.WeekSnapshot,
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val retry: () -> Unit = {},
)

/** Read local data once per picker, not one unmanaged Glance session per animated tab. */
@Composable
internal fun rememberWidgetPreviewData(): WidgetPreviewData {
    val context = LocalContext.current.applicationContext
    val now = remember { System.currentTimeMillis() }
    var retry by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf(WidgetPreviewData(TodayWidgetSource.empty(now),
        TodayWidgetSource.WeekSnapshot(WeekWidgetModel.build(emptyList(), null), ScheduleText.weekdayOf(now), hasData = false))) }
    LaunchedEffect(context, retry) {
        try {
            data = withContext(Dispatchers.IO) {
                WidgetPreviewData(TodayWidgetSource.load(context, now), TodayWidgetSource.loadWeek(context, now), loaded = true)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("WidgetPreview", "本机数据读取失败：${error.javaClass.simpleName}")
            data = data.copy(loaded = true, failed = true)
        }
    }
    return data.copy(retry = { retry++ })
}

@Composable
internal fun WidgetLocalPreview(kind: WidgetKind, data: WidgetPreviewData, appearance: WidgetAppearance = WidgetAppearance()) {
    Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(max = 230.dp)
        .clip(RoundedCornerShape(appearance.radiusDp.dp)).background(AppColors.SurfaceOpaque.copy(alpha = appearance.alpha)).padding(12.dp)
        .semantics { contentDescription = "${kind.title}本机数据预览" }) {
        when (kind) {
            WidgetKind.Calendar -> {
                Text("${data.today.dateLabel} ${data.today.weekdayLabel}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                if (data.today.deadlines.isEmpty()) Text("暂无考试或竞赛倒计时", color = AppColors.TextSecondary, fontSize = 12.sp)
                PreviewDeadlines(data.today.deadlines)
            }
            WidgetKind.Today -> {
                Text("今天 · ${data.today.dateLabel} ${data.today.weekdayLabel}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (data.today.allRows.isEmpty()) item {
                        Text(if (data.today.hasData) "今天没有课" else "导入课表后显示真实课程", color = AppColors.TextSecondary, fontSize = 12.sp)
                    }
                    items(data.today.allRows) { row ->
                        val color = data.today.courseColors[row.name.trim()]?.let { Color(it) }
                        val foreground = if (color != null) Color.White else AppColors.TextPrimary
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(color ?: AppColors.ChipIdle).padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(row.name, color = foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${row.periods}节 · ${row.time}", color = foreground, fontSize = 10.sp)
                            cn.tonglu.app.ui.shortPlace(row.location)?.let { Text(it, color = foreground, fontSize = 10.sp) }
                        }
                    }
                    item { PreviewDeadlines(data.today.deadlines) }
                }
            }
            WidgetKind.Week -> WeekPreview(data.week)
        }
        if (!data.loaded) Text("正在读取本机课表…", color = AppColors.TextMuted, fontSize = 11.sp)
        if (data.failed) TextButton(onClick = data.retry) { Text("读取失败，点击重试") }
    }
}

@Composable
private fun PreviewDeadlines(items: List<TodayWidgetDeadlines.Item>) {
    items.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(item.title, Modifier.weight(1f), color = AppColors.TextPrimary, fontSize = 11.sp, maxLines = 1)
            Text(item.label, color = if (item.urgent) AppColors.Danger else AppColors.Accent, fontSize = 11.sp)
        }
    }
}

@Composable
private fun WeekPreview(snapshot: TodayWidgetSource.WeekSnapshot) {
    Text(snapshot.week?.let { "第${it}周 · 上下滑动查看" } ?: "完整课表 · 上下滑动查看",
        fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    if (!snapshot.hasData) {
        Text("导入课表后显示真实课程", color = AppColors.TextSecondary, fontSize = 12.sp)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth.value
        val foreground = AppColors.TextPrimary
        val empty = AppColors.ChipIdle.copy(alpha = .32f)
        val fallback = AppColors.ChipIdle
        // 预览是直接画在 Compose 画布上的（不走 RemoteViews 位图预算），同样按设备像素渲染：
        // 多了就用 1:1，避免选择页的「桌面预览」本身把字拉伸。
        val density = LocalDensity.current.density
        val bands = remember(snapshot, width, foreground, empty, fallback, density) {
            (0 until (WeekGridArtwork.rows(snapshot) + 1) / 2).map {
                WeekGridArtwork.band(snapshot, it, width, foreground, empty, fallback, density).asImageBitmap()
            }
        }
        Column {
            Row(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                Spacer(Modifier.width(WeekGridArtwork.GUTTER_DP.dp))
                (1..7).forEach { day ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ScheduleText.shortWeekday(day).removePrefix("周"), fontSize = 10.sp)
                        Text(ScheduleText.dateLabel(day, snapshot.weekStart).removeSuffix("日").replace("月", "/"),
                            fontSize = 8.sp, color = AppColors.TextSecondary)
                    }
                }
            }
            LazyColumn(Modifier.heightIn(max = 155.dp)) {
                items(bands) { bitmap ->
                    Image(bitmap, "课表网格", Modifier.fillMaxWidth().height(WeekGridArtwork.BAND_DP.dp), contentScale = ContentScale.FillBounds)
                }
            }
        }
    }
}
