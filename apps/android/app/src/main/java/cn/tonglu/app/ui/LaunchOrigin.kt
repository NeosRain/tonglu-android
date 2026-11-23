package cn.tonglu.app.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * 一镜到底：记录「这一层是从哪里打开的」。
 *
 * 覆盖层（[overlayMotion]）创建时取走一次：有来源就从来源矩形展开成全屏，关闭时收回来源；
 * 没有来源（程序触发、超时）就沿用原来的进场方向。坐标统一用窗口坐标——全屏弹窗与主窗口重合。
 *
 * 来源有两级：元素用 [launchOrigin] 标记时记录它的整块边界；否则退回主窗口最近一次按下的位置
 * （[MainActivity] 转发），页面从手指处长出来。
 */
internal object LaunchOrigin {
    /** 按下到弹层创建之间允许的最长间隔，超过就当作不是这次点击打开的。 */
    private const val TTL_MS = 900L

    private var enter: Rect? = null
    private var exit: Rect? = null
    private var armedAt = 0L
    private var touch: Offset? = null
    private var touchedAt = 0L
    private var container: ContainerOrigin? = null

    fun arm(source: ContainerOrigin) {
        container = source
        armedAt = SystemClock.uptimeMillis()
    }

    /** 记录元素来源。[exit] 用于「从 A 打开、关闭时回到 B」，例如弹窗卡片展开成笔记、关闭回到课表格子。 */
    fun arm(enter: Rect, exit: Rect = enter) {
        if (enter.width <= 0f || enter.height <= 0f) return
        container = null
        this.enter = enter
        this.exit = exit.takeIf { it.width > 0f && it.height > 0f } ?: enter
        armedAt = SystemClock.uptimeMillis()
    }

    fun touch(x: Float, y: Float) {
        container = null
        enter = null
        exit = null
        touch = Offset(x, y)
        touchedAt = SystemClock.uptimeMillis()
    }

    fun takeContainer(): ContainerOrigin? {
        val source = container?.takeIf { SystemClock.uptimeMillis() - armedAt <= TTL_MS }
        container = null
        if (source != null) {
            enter = null
            exit = null
            touch = null
        }
        return source
    }

    /** 取走来源（进场矩形, 收回矩形）；只能取一次。 */
    fun take(touchSizePx: Float, includeTouch: Boolean = true): Pair<Rect, Rect>? {
        val now = SystemClock.uptimeMillis()
        val element = enter?.takeIf { now - armedAt <= TTL_MS }?.let { it to (exit ?: it) }
        val finger = touch?.takeIf { includeTouch && now - touchedAt <= TTL_MS }
        enter = null; exit = null; touch = null
        if (element != null) return element
        if (finger == null) return null
        val half = touchSizePx / 2f
        val r = Rect(finger.x - half, finger.y - half, finger.x + half, finger.y + half)
        return r to r
    }
}

/** 一次导航的起点和实时返回目标。源与目标可能位于不同的 Dialog 窗口。 */
internal class ContainerOrigin(
    val initial: Rect,
    val cornerPx: Float,
    val target: LaunchSource,
)

internal val LocalReaderOrigin = staticCompositionLocalOf<ContainerOrigin?> { null }

internal class LaunchSource(internal val cornerPx: Float, initialSurfaceColor: Color) {
    internal var surfaceColor by mutableStateOf(initialSurfaceColor)
    internal var coordinates: LayoutCoordinates? by mutableStateOf(null)
    internal var alpha: State<Float>? by mutableStateOf(null)

    fun bounds(): Rect? {
        val layout = coordinates?.takeIf { it.isAttached } ?: return null
        val bounds = layout.boundsInWindow()
        val screen = layout.localToScreen(Offset.Zero)
        if (!screen.isSpecified) return null
        return bounds.translate(screen - layout.positionInWindow()).takeIf { it.width > 0f && it.height > 0f }
    }

    fun capture(): ContainerOrigin? = bounds()?.let { ContainerOrigin(it, cornerPx, this) }

    fun launch(onClick: () -> Unit) {
        capture()?.let(LaunchOrigin::arm)
        onClick()
    }
}

@Composable
internal fun rememberLaunchSource(
    key: Any?,
    corner: Dp = 0.dp,
    surfaceColor: Color = MaterialTheme.colorScheme.surface,
): LaunchSource {
    val cornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { corner.toPx() }
    val source = remember(key, cornerPx) { LaunchSource(cornerPx, surfaceColor) }
    SideEffect { source.surfaceColor = surfaceColor }
    return source
}

internal fun Modifier.launchOrigin(source: LaunchSource): Modifier =
    onGloballyPositioned { source.coordinates = it }
        .graphicsLayer { alpha = source.alpha?.value ?: 1f }

/** 仅在点击成立时登记，同时支持键盘及无障碍点击。 */
internal fun Modifier.launchClickable(
    key: Any?,
    corner: Dp = 0.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val source = rememberLaunchSource(key, corner)
    launchOrigin(source).clickable(enabled = enabled) { source.launch(onClick) }
}

/** 标记一个可打开新层的元素：按下时把它的整块边界登记为来源，不消费事件。 */
internal fun Modifier.launchOrigin(): Modifier = composed {
    val bounds = remember { arrayOf(Rect.Zero) }
    onGloballyPositioned { bounds[0] = it.boundsInWindow() }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                LaunchOrigin.arm(bounds[0])
            }
        }
}

/** 展开过程中的裁剪框（层内坐标）。每帧新建，保证轮廓随进度更新。 */
internal class OriginClipShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * 从来源矩形 [from]（层内坐标）到整层 [size] 的容器变换。
 * 内容等比缩放铺满当前矩形，水平居中、顶部对齐，多出的部分裁掉。
 */
internal data class OriginFrame(
    val scale: Float, val translationX: Float, val translationY: Float, val clip: Rect, val radius: Float,
)

internal fun originFrame(from: Rect, size: Size, progress: Float, cornerPx: Float): OriginFrame {
    val p = progress.coerceIn(0f, 1f)
    val w = lerp(from.width, size.width, p).coerceAtLeast(1f)
    val h = lerp(from.height, size.height, p).coerceAtLeast(1f)
    val left = lerp(from.left, 0f, p)
    val top = lerp(from.top, 0f, p)
    val scale = maxOf(w / size.width, h / size.height).coerceIn(0.01f, 1f)
    val clipW = (w / scale).coerceAtMost(size.width)
    val clipH = (h / scale).coerceAtMost(size.height)
    val clipLeft = (size.width - clipW) / 2f
    return OriginFrame(
        scale = scale,
        translationX = left - clipLeft * scale,
        translationY = top,
        clip = Rect(clipLeft, 0f, clipLeft + clipW, clipH),
        radius = lerp(cornerPx, 0f, p) / scale,
    )
}

internal fun fitOriginFrame(bounds: Rect, size: Size, radius: Float): OriginFrame {
    val scale = maxOf(bounds.width / size.width, bounds.height / size.height)
    val width = (bounds.width / scale).coerceAtMost(size.width)
    val height = (bounds.height / scale).coerceAtMost(size.height)
    val left = (size.width - width) / 2f
    return OriginFrame(scale, bounds.left - left * scale, bounds.top, Rect(left, 0f, left + width, height),
        minOf(radius / scale, minOf(width, height) / 2f))
}

internal data class ContainerFrame(
    val bounds: Rect,
    val radius: Float,
    val containerAlpha: Float = 1f,
    val detailAlpha: Float = 1f,
    val sourceAlpha: Float = 1f,
    val surfaceBlend: Float = 1f,
)

internal fun interpolateBounds(from: Rect, to: Rect, progress: Float): Rect = Rect(
    lerp(from.left, to.left, progress), lerp(from.top, to.top, progress),
    lerp(from.right, to.right, progress), lerp(from.bottom, to.bottom, progress),
)

internal fun containerEnter(
    source: Rect?, full: Rect, sourceCorner: Float, fullCorner: Float,
    geometry: Float, time: Float, reduced: Boolean,
): ContainerFrame {
    if (source == null || reduced) {
        return ContainerFrame(full, fullCorner, containerAlpha = time)
    }
    val sourceAlpha = enterSourceAlpha(time)
    return ContainerFrame(
        interpolateBounds(source, full, geometry), lerp(sourceCorner, fullCorner, geometry),
        containerAlpha = 1f - sourceAlpha,
        detailAlpha = ((time - 1f / 3f) * 1.5f).coerceIn(0f, 1f),
        sourceAlpha = sourceAlpha,
        surfaceBlend = geometry,
    )
}

internal fun containerBackFrame(frame: ContainerFrame, progress: Float, fromLeft: Boolean, corner: Float): ContainerFrame {
    val bounds = frame.bounds
    val shift = bounds.width * OverlayMotion.MAX_SHIFT_RATIO * progress * if (fromLeft) 1f else -1f
    return frame.copy(
        bounds = shrinkBounds(bounds, .05f * progress).translate(shift, 0f),
        radius = lerp(frame.radius, corner, progress),
    )
}

internal fun containerExit(
    start: ContainerFrame, target: Rect?, targetCorner: Float,
    geometry: Float, time: Float, reduced: Boolean,
): ContainerFrame {
    if (target == null || reduced) {
        return start.copy(
            bounds = if (reduced) start.bounds else shrinkBounds(start.bounds, .02f * geometry),
            containerAlpha = start.containerAlpha * (1f - time),
            sourceAlpha = lerp(start.sourceAlpha, 1f, time),
        )
    }
    val revealSource = exitSourceReveal(geometry)
    return ContainerFrame(
        interpolateBounds(start.bounds, target, geometry), lerp(start.radius, targetCorner, geometry),
        containerAlpha = start.containerAlpha * (1f - revealSource),
        // 先让详情随容器明显收拢，正文淡完时容器已接近来源行的尺寸。
        detailAlpha = start.detailAlpha * (1f - ((geometry - .12f) / .76f).coerceIn(0f, 1f)),
        sourceAlpha = lerp(start.sourceAlpha, 1f, revealSource),
        surfaceBlend = lerp(start.surfaceBlend, 0f, geometry),
    )
}

private fun shrinkBounds(bounds: Rect, insetFraction: Float): Rect {
    val dx = bounds.width * insetFraction
    val dy = bounds.height * insetFraction
    return Rect(bounds.left + dx, bounds.top + dy, bounds.right - dx, bounds.bottom - dy)
}

internal fun enterSourceAlpha(time: Float): Float = 1f - (time * 3f).coerceIn(0f, 1f)

internal fun exitSourceReveal(geometry: Float): Float =
    ((geometry - .84f) / .16f).coerceIn(0f, 1f)
