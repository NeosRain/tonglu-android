package cn.tonglu.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur

private val FadeDistance = 60.dp
private val BottomFeather = 20.dp

internal fun topBarFadeFraction(scrollPx: Float, fadeDistancePx: Float, reduceMotion: Boolean): Float =
    if (reduceMotion) {
        if (scrollPx > 0f) 1f else 0f
    } else {
        (scrollPx / fadeDistancePx).coerceIn(0f, 1f)
    }

internal fun LazyListState.topBarScrollPx(): Float {
    // 列表首项滑进顶部留白后，firstVisibleItemIndex 已经可能指向第二项。
    val first = layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
    return first?.let { -it.offset.toFloat() }
        ?: if (firstVisibleItemIndex == 0) 0f else Float.POSITIVE_INFINITY
}

internal fun LazyGridState.topBarScrollPx(): Float {
    val first = layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
    return first?.let { -it.offset.y.toFloat() }
        ?: if (firstVisibleItemIndex == 0) 0f else Float.POSITIVE_INFINITY
}

/** 每个主页保存自己的滚动读口，顶栏只读取当前页。 */
internal class MainTopBarScroll {
    var readOffset: (() -> Float)? by mutableStateOf(null)
}

internal val LocalMainTopBarScroll = staticCompositionLocalOf<MainTopBarScroll?> { null }

@Composable
internal fun BindMainTopBarScroll(readOffset: () -> Float) {
    val target = LocalMainTopBarScroll.current ?: return
    val latestOffset by rememberUpdatedState(readOffset)
    DisposableEffect(target) {
        val reader = { latestOffset() }
        target.readOffset = reader
        onDispose { if (target.readOffset === reader) target.readOffset = null }
    }
}

/** 只遮罩玻璃背景层，标题和按钮由独立的兄弟节点绘制。 */
@Composable
internal fun DarkTopBarBackground(
    backdrop: Backdrop?,
    scrollOffsetPx: () -> Float,
    modifier: Modifier,
) {
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val density = LocalDensity.current
    val statusHeight = WindowInsets.statusBars.getTop(density)
    val fadeDistancePx = with(density) { FadeDistance.toPx() }
    val latestOffset by rememberUpdatedState(scrollOffsetPx)
    val fraction by remember(fadeDistancePx, motionScale) {
        derivedStateOf(structuralEqualityPolicy()) {
            topBarFadeFraction(latestOffset(), fadeDistancePx, motionScale?.scaleFactor == 0f)
        }
    }
    val visible by remember(fadeDistancePx, motionScale) { derivedStateOf { fraction > 0f } }
    if (!visible) return

    val blur = if (backdrop != null) Modifier.drawPlainBackdrop(
        backdrop = rememberBottomBackdrop(backdrop),
        shape = { RectangleShape },
        effects = { blur(28.dp.toPx()) },
    ) else Modifier

    Box(modifier
        .layout { measurable, constraints ->
            // 背景向下多绘制一段，控件高度和页面顶部占位仍按原尺寸计算。
            val height = constraints.maxHeight
            val extendedHeight = height + BottomFeather.roundToPx()
            val placeable = measurable.measure(constraints.copy(minHeight = extendedHeight, maxHeight = extendedHeight))
            layout(placeable.width, height) { placeable.place(0, 0) }
        }
        .graphicsLayer {
            alpha = fraction
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .drawWithCache {
            val barEnd = (size.height - BottomFeather.roundToPx()) / size.height
            val mask = Brush.verticalGradient(
                0f to Color.Black,
                (statusHeight / size.height).coerceAtMost(barEnd) to Color.Black,
                barEnd to Color.Black.copy(alpha = .45f),
                1f to Color.Transparent,
            )
            onDrawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = .70f))
                drawRect(mask, blendMode = BlendMode.DstIn)
            }
        }
        .then(blur))
}
