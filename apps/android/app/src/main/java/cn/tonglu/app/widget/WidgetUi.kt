package cn.tonglu.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import cn.tonglu.app.MainActivity
import cn.tonglu.app.widget.TodayWidgetDeadlines.Item

/**
 * 各小部件共用的**视觉与颜色**（2026-10-16 拆分小组件时从 [TodayWidget] 抽出来）。
 *
 * ## 为什么要单独一个文件
 *
 * 拆成「今日课表 / 日历 / 完整课表」三个部件之后，圆角、强调色、临近红、
 * 倒计时行的画法成了**三个部件共用的一份契约**。留在第一个部件里当 `private`，
 * 后面两个就只能各抄一份 —— 颜色一多，「同一个意思两种颜色」只是时间问题
 * （`audit_brand_color.py` 钉的就是这个）。
 *
 * 🔴 色值**只在这里出现一次**：
 *   · [WIDGET_ACCENT] `#2563EB` —— 与 `res/values/colors.xml` 的 `tonglu_accent` 一致
 *     （2026-10-11 随浅色 Accent 加深后的值）。`audit_brand_color.py` 的 CARRIERS
 *     钉的是**这个文件**（2026-10-16 从 TodayWidget.kt 迁来，常量搬家但值与条数不变）。
 *   · [WIDGET_URGENT] `#D93A3A` —— 与 `ui/home/ExamCountdownCard.kt` 的 `UrgentInk` 同值：
 *     首页那条倒计时条和桌面这几块，说的是同一件事，颜色不该有两种。
 */

/** 卡片圆角。桌面部件的圆角普遍在 16~28dp，20dp 和图标、对话框都不打架。 */
internal val WIDGET_CARD_RADIUS = 20.dp

/** 课程色块的圆角。比卡片小一档 —— 两层同样大的圆角套在一起会显得糊。 */
internal val WIDGET_BLOCK_RADIUS = 12.dp

/**
 * 圆角开关：只在 API 31+ 真的加 [cornerRadius]。
 *
 * 低于 31 必须原样返回：Glance 在低版本上调 cornerRadius 会抛
 * （常量池里那句 `Cannot set the rounded corner of views before Api 31.`），
 * 那时用户看到的不是「没圆角」，是小部件整个崩掉。
 * 「没有圆角」可以接受，「小部件崩溃」不行。
 */
internal fun GlanceModifier.widgetRounded(radius: Dp): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.cornerRadius(radius) else this

internal val WIDGET_ACCENT = Color(0xFF2563EB)
internal val WIDGET_ACCENT_PROVIDER = ColorProvider(Color(0xFF2563EB))
internal val WIDGET_URGENT = Color(0xFFD93A3A)

/**
 * 整块卡片可点 → 拉起 [MainActivity]。
 *
 * 🔴 必须显式构造 Intent：Glance 只有 `actionStartActivity(intent)`，
 * **没有** Compose 那种 `actionStartActivity<T>()` 的 reified 重载
 * （已在 glance-appwidget 1.2.0 的 StartActivityIntentIntentKt 里确认）。
 * 不带深链参数：首页就是课表，多一个 extra 只会让冷启动多一次分支。
 */
internal fun widgetOpenAppIntent(context: android.content.Context): Intent =
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * 一条倒计时：`竞赛 · 蓝桥杯报名` ……… `还有 3 天`
 *
 * ## 两个不能省的细节
 * 1. **天数整体放右侧**（`maxLines = 1`，不给 weight）。左边标题长了会被裁，
 *    但天数永远看得见 —— 天数才是这条的全部价值。反过来（天数在左、标题在右）
 *    一旦被裁，用户看到的是一句没有数字的「竞赛 · 蓝桥杯报名」。
 * 2. **临近时多给一个文字信号**（「临近」），不只换颜色 —— 这是 plan/56 §2.4 的规矩
 *    （色觉障碍用户看不出蓝色和红色的差别）。
 *
 * @param inline 顶进标题副行的那种**不设高度**（它的高度由标题区的算式管着）；
 *        独立行则给足 [TodayWidgetLayout.cdRow]（18dp），与算式一致。
 */
@Composable
internal fun DeadlineRow(item: Item, inline: Boolean) {
    val label = if (item.urgent) "临近 · ${item.label}" else item.label
    val labelColor = if (item.urgent) WIDGET_URGENT else WIDGET_ACCENT

    Row(
        modifier = if (inline) {
            GlanceModifier.fillMaxWidth()
        } else {
            GlanceModifier.fillMaxWidth().height(TodayWidgetLayout.cdRow)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${item.kind.tag} · ${item.title}",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp),
            modifier = GlanceModifier.defaultWeight(),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = label,
            style = TextStyle(color = ColorProvider(labelColor), fontSize = 11.sp),
            maxLines = 1,
        )
    }
}

/**
 * 多条倒计时，只画 [shown] 条（其余由高度预算决定）。
 *
 * 行之间**不再另加间距**：每条自己就是 [TodayWidgetLayout.cdRow] 高、文字居中，
 * 上下各有约 2dp 余量 —— 一个 [TodayWidgetLayout.cdGap] 只管整块与上方内容的距离。
 * 另加间距会让实际高度超过算式，就又回到「被裁掉还不知道为什么」。
 */
@Composable
internal fun DeadlineRows(items: List<Item>, shown: Int) {
    val take = shown.coerceAtMost(items.size)
    if (take <= 0) return
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Spacer(modifier = GlanceModifier.height(TodayWidgetLayout.cdGap))
        items.take(take).forEach { item -> DeadlineRow(item = item, inline = false) }
    }
}

/**
 * 用**系统真实字体度量**量出一行 [sp] 文字的高度（dp）。
 *
 * ## 为什么不能按「字号 × 1.3」估
 *
 * 小部件里显示的是中文：12sp 标题行在 K100 实测一行 17.7dp（约 1.47em），
 * 而按西文常见系数 1.3 估只有 15.6dp —— 低估约 12%。列表高度是从卡片高度倒减标题行
 * 得到的，行高估小 = 列表算大 = 最后一门课被宿主静默裁掉（BUGS S132 的那类事故）。
 *
 * ## 为什么用 StaticLayout 而不是自己算 ascent/descent
 *
 * TextView 内部就是用 StaticLayout 排版：includePad 对应 TextView 默认的
 * includeFontPadding，所以 layout.height 就是屏幕上那一行（含 padding）占的高度，
 * 中文字体回退、粗体、行距全部按系统实现走，换设备/换字体不用改系数。
 *
 * @param sample 该行实际会出现的文字（混排几类字符可让度量覆盖所有回退字体，取最大值）
 */
internal fun lineHeightDp(context: Context, sp: Float, sample: String): Float {
    val metrics = context.resources.displayMetrics
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp * metrics.scaledDensity
        typeface = Typeface.DEFAULT
    }
    val layout = StaticLayout.Builder
        .obtain(sample, 0, sample.length, paint, 10_000)
        .setIncludePad(true)
        .build()
    return layout.height / metrics.density
}
