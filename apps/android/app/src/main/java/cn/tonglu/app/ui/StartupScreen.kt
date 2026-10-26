package cn.tonglu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import cn.tonglu.app.R
import cn.tonglu.app.data.StartupArtwork
import cn.tonglu.app.data.AppBrand
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
            Image(painterResource(R.drawable.ic_brand_logo), contentDescription = null,
                modifier = Modifier.size(56.dp).graphicsLayer { alpha = startupTextReveal(progress()) })
            Spacer(Modifier.height(16.dp))
            Canvas(Modifier.fillMaxWidth(.82f).height(40.dp).semantics { contentDescription = AppBrand.CHINESE_NAME + " " + AppBrand.ENGLISH_NAME }) {
                val reveal = startupTextReveal(progress())
                drawWordmark(ink, reveal)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                AppBrand.POEM, color = ink, fontSize = 14.sp, lineHeight = 24.sp, textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = startupTextReveal(progress()) },
            )
            Spacer(Modifier.height(10.dp))
            // notice（如「启动背景读取失败」）**不随字标渐显**：progress=0 时它也得看得见，
            // 否则背景读取失败时用户只看到一张空白渐变，没有任何解释（2026-10-15 排查实录）。
            Text(notice ?: AppBrand.VALUES, color = AppColors.TextMuted, fontSize = 11.sp)
        }
    }
}

/** System-font wordmark fitted to its available width; preserves the existing reveal timing. */
private fun DrawScope.drawWordmark(ink: Color, reveal: Float) {
    if (reveal <= 0f || size.width <= 0f || size.height <= 0f) return
    val text = AppBrand.CHINESE_NAME
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = ink.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        textSize = size.height * .85f
    }
    paint.textSize *= minOf(1f, size.width * .98f / paint.measureText(text))
    val metrics = paint.fontMetrics
    val baseline = (size.height - metrics.ascent - metrics.descent) / 2f
    clipRect(right = size.width * reveal.coerceIn(0f, 1f)) {
        drawIntoCanvas {
            it.nativeCanvas.drawText(text, size.width / 2f, baseline, paint)
        }
    }
}
