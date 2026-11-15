package cn.tonglu.app.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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

    /** 记录元素来源。[exit] 用于「从 A 打开、关闭时回到 B」，例如弹窗卡片展开成笔记、关闭回到课表格子。 */
    fun arm(enter: Rect, exit: Rect = enter) {
        if (enter.width <= 0f || enter.height <= 0f) return
        this.enter = enter
        this.exit = exit.takeIf { it.width > 0f && it.height > 0f } ?: enter
        armedAt = SystemClock.uptimeMillis()
    }

    fun touch(x: Float, y: Float) {
        touch = Offset(x, y)
        touchedAt = SystemClock.uptimeMillis()
    }

    /** 取走来源（进场矩形, 收回矩形）；只能取一次。 */
    fun take(touchSizePx: Float): Pair<Rect, Rect>? {
        val now = SystemClock.uptimeMillis()
        val element = enter?.takeIf { now - armedAt <= TTL_MS }?.let { it to (exit ?: it) }
        val finger = touch?.takeIf { now - touchedAt <= TTL_MS }
        enter = null; exit = null; touch = null
        if (element != null) return element
        if (finger == null) return null
        val half = touchSizePx / 2f
        val r = Rect(finger.x - half, finger.y - half, finger.x + half, finger.y + half)
        return r to r
    }
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
