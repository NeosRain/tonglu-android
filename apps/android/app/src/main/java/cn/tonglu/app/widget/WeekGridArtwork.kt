package cn.tonglu.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cn.tonglu.app.ui.shortPlace

/** Same seven columns and course palette as TimetableGrid, with a compact desktop row.
 * Render in two-period bands so a launcher ListView can scroll a grid with spanning cells.
 * No Column can exceed Glance's ten-child limit; no course is reduced to two characters.
 *
 * ## 位图必须按**设备像素**渲染（2026-10-18 修用户反馈「整周模糊」）
 *
 * 位图画在 dp 坐标系里（1 单位 = 1dp），再按 [scale] 放大到像素。旧实现把 scale 压在 2 以下
 * （1200×2608@480 上算出来 1.61），而屏幕密度是 3.0 —— Bitmap 只有 540px 宽，
 * 却被 Image(FillBounds) 拉到 1008px，等于放大 1.87 倍，字必然发虚。
 * 现在 [scaleFor] 默认给设备密度（bitmap px = 屏幕 px，1:1），只在 RemoteViews 位图预算
 * 不够时才降级（AppWidgetService 上限约为 6×屏幕像素字节，这里只用 3/4）。
 */
internal object WeekGridArtwork {
    const val ROW_DP = 40f
    const val BAND_DP = ROW_DP * 2
    const val GUTTER_DP = 20f

    fun rows(snapshot: TodayWidgetSource.WeekSnapshot) = snapshot.grid.rows.coerceIn(8, WeekWidgetModel.MAX_PERIODS)

    /**
     * 位图缩放倍数 —— 清楚的关键：**设备密度**就是 1:1。
     *
     * @param widthDp 每个 band 的绘制宽度（dp）
     * @param bands band 数量（每 band 两节）
     * @param screenWidthPx/screenHeightPx 宿主屏幕像素
     * @param density 设备密度（1dp = density px）
     */
    fun scaleFor(widthDp: Float, bands: Int, screenWidthPx: Int, screenHeightPx: Int, density: Float, layouts: Int = 1): Float {
        if (bands <= 0 || widthDp <= 0f || density <= 0f) return 1f
        // 1:1 渲染时所有 band 的位图总字节。
        val bytesAtDensity = bands * widthDp * density * BAND_DP * density * 4.0
        val budgetBytes = 0.75 * 6.0 * screenWidthPx * screenHeightPx / layouts.coerceAtLeast(1)
        val allowed = if (bytesAtDensity <= budgetBytes) density
        else kotlin.math.sqrt(budgetBytes / (bands * widthDp * BAND_DP * 4.0)).toFloat()
        return allowed.coerceAtMost(density)
    }

    fun band(snapshot: TodayWidgetSource.WeekSnapshot, index: Int, widthDp: Float,
             foreground: Color, empty: Color, fallback: Color, scale: Float): Bitmap {
        // 宽度就是宿主真实可用宽度（Exact 尺寸），不再往 420dp 上截 —— 截了会被 FillBounds 拉伸。
        val width = widthDp.coerceAtLeast(160f)
        val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (BAND_DP * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(0f, -index * BAND_DP)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val cellWidth = (width - GUTTER_DP) / 7
        val topPeriod = index * 2 + 1
        for (period in topPeriod..minOf(topPeriod + 1, rows(snapshot))) {
            val top = (period - 1) * ROW_DP
            text.color = foreground.toArgb(); text.textSize = 9f; text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            canvas.drawText("$period", GUTTER_DP / 2, top + 17f, text)
            text.textSize = 6.5f
            canvas.drawText(snapshot.periodStarts[period].orEmpty(), GUTTER_DP / 2, top + 28f, text)
            for (day in 0..6) {
                val left = GUTTER_DP + day * cellWidth
                paint.color = empty.toArgb()
                canvas.drawRoundRect(RectF(left + 1.5f, top + 1.5f, left + cellWidth - 1.5f, top + ROW_DP - 1.5f), 7f, 7f, paint)
            }
        }
        snapshot.grid.days.forEachIndexed { day, cells ->
            cells.filter { it.endRow >= topPeriod && it.startRow <= topPeriod + 1 }.forEach { cell ->
                val left = GUTTER_DP + day * cellWidth + 1.5f
                val top = (cell.startRow - 1) * ROW_DP + 1.5f
                val bottom = cell.endRow * ROW_DP - 1.5f
                val color = snapshot.courseColors[cell.name.trim()]
                paint.color = color ?: fallback.toArgb()
                canvas.drawRoundRect(RectF(left, top, left + cellWidth - 3f, bottom), 9f, 9f, paint)
                text.color = if (color != null) Color.White.toArgb() else foreground.toArgb()
                text.textSize = 9.5f; text.textAlign = Paint.Align.LEFT
                text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                val textWidth = (cellWidth - 11f).toInt().coerceAtLeast(1)
                val maxLines = minOf(4, ((bottom - top - 8f) / 12f).toInt()).coerceAtLeast(1)
                val layout = StaticLayout.Builder.obtain(cell.name, 0, cell.name.length, text, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                    .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
                canvas.save(); canvas.translate(left + 4f, top + 5f); layout.draw(canvas); canvas.restore()
                if (bottom - top >= 66f) {
                    val place = shortPlace(cell.location).orEmpty()
                    text.textSize = 8f; text.typeface = Typeface.DEFAULT
                    val clipped = TextUtils.ellipsize(place, text, textWidth.toFloat(), TextUtils.TruncateAt.END)
                    canvas.drawText(clipped.toString(), left + 4f, bottom - 7f, text)
                }
            }
        }
        return bitmap
    }
}
