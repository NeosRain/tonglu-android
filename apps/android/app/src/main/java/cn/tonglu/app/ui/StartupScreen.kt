package cn.tonglu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.StartupArtwork
import cn.tonglu.app.ui.startup.StartupRefraction
import cn.tonglu.app.ui.startup.startupTextReveal

/** 单画布折射透镜；照片保持清晰，进度只在绘制层读取。 */
@Composable
internal fun StartupScreen(
    artwork: StartupArtwork? = null,
    progress: () -> Float = { 0f },
    notice: String? = null,
) {
    val canvas = AppColors.Canvas
    val ink = AppColors.TextPrimary
    // 开屏的品牌装饰色走 StartupAccent（白天薄荷 / 晚上深海），**不是**站内强调色 Accent。
    // 字标文字用的是 ink（高对比），accent 只画玻璃墙主调、光晕、波峰描边与揭示扫描线，
    // 所以换成亮色不会伤可读性。见 AppColors.StartupAccent 的说明。
    val accent = AppColors.StartupAccent
    val dark = AppTheme.darkState
    val glass = remember(artwork, canvas, accent, dark) {
        StartupRefraction(artwork?.sharp, canvas.toArgb(), accent.toArgb(), dark)
    }
    Box(Modifier.fillMaxSize().background(canvas)) {
        Canvas(Modifier.fillMaxSize()) {
            val p = progress().coerceIn(0f, 1f)
            drawIntoCanvas { glass.draw(it.nativeCanvas, size.width, size.height, density, p) }
            // Keep the selected image legible. The theme veil is confined to the wordmark area.
            val veil = if (artwork != null) .92f else .48f
            drawRect(Brush.verticalGradient(
                listOf(Color.Transparent, canvas.copy(alpha = veil)),
                startY = size.height * .62f, endY = size.height,
            ))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 62.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.fillMaxWidth(.82f).height(40.dp).semantics { contentDescription = "同路 T O N G L U" }) {
                val reveal = startupTextReveal(progress())
                drawWordmark(ink, reveal)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "同路 —— 陪你把这一天过好", color = ink, fontSize = 15.sp,
                modifier = Modifier.graphicsLayer { alpha = startupTextReveal(progress()) },
            )
            Spacer(Modifier.height(10.dp))
            // notice（如「启动背景读取失败」）**不随字标渐显**：progress=0 时它也得看得见，
            // 否则背景读取失败时用户只看到一张空白渐变，没有任何解释（2026-10-15 排查实录）。
            Text(notice ?: "平等 · 互助 · 进步 · 团结", color = AppColors.TextMuted, fontSize = 11.sp)
        }
    }
}

/** 原创几何线性字标，宽字距、切角及微倾斜；没有使用SpaceX标志或版权不明字体。 */
private fun DrawScope.drawWordmark(ink: Color, reveal: Float) {
    val letters = listOf(
        listOf(listOf(0f to 0f, 1f to 0f), listOf(.5f to 0f, .5f to 1f)),
        listOf(listOf(.15f to 0f, .85f to 0f, 1f to .15f, 1f to .85f, .85f to 1f, .15f to 1f, 0f to .85f, 0f to .15f, .15f to 0f)),
        listOf(listOf(0f to 1f, 0f to 0f, 1f to 1f, 1f to 0f)),
        listOf(listOf(1f to .18f, .82f to 0f, .18f to 0f, 0f to .18f, 0f to .82f, .18f to 1f, 1f to 1f, 1f to .55f, .55f to .55f)),
        listOf(listOf(0f to 0f, 0f to 1f, 1f to 1f)),
        listOf(listOf(0f to 0f, 0f to .82f, .18f to 1f, .82f to 1f, 1f to .82f, 1f to 0f)),
    )
    val unit = size.width / 6.8f; val height = size.height * .72f
    letters.forEachIndexed { index, strokes ->
        val visibilityT = ((reveal * 6.8f - index * 1.14f) / .8f).coerceIn(0f, 1f)
        val visibility = visibilityT * visibilityT * (3f - 2f * visibilityT)
        strokes.forEach { points ->
            val path = Path()
            points.forEachIndexed { i, point ->
                val x = index * unit * 1.14f + point.first * unit * .74f + (1f - point.second) * unit * .07f
                val y = point.second * height + size.height * .10f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ink.copy(alpha = ink.alpha * visibility), style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Bevel))
        }
    }
}
