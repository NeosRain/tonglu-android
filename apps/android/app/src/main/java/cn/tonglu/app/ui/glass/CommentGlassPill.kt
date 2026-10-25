package cn.tonglu.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 评论浏览工具专用玻璃胶囊（UI-REPAIR §2 / DESIGN-SYSTEM §6，**局部**组件）。
 *
 * 与旧实现（[InteractiveHighlight] + `GlassActionPanel(Solid)`）的差异：
 * - **四向跟手**：X/Y 都有有界位移（[boundedDrag]，tanh 软限幅），默认横向 ±10dp、纵向 ±8dp；
 *   旧实现只有 translationX 且先夹 ±12dp 再乘 0.3（实际最大 ±3.6dp）。
 * - **局部高光**：跟随手指的径向光画在 [drawBackdrop] 的 `onDrawSurface` 里，被胶囊自己的
 *   shape 裁剪、半径按胶囊短边计 —— 旧实现 `drawRect` 画满整个挂载节点、无 shader 时回退成
 *   均匀白光（「按住填满底部」的根因）。
 * - **长按反馈**：长按进入材质反馈（静止→磨砂），超过 touchSlop 进入拖动；短按仍是真实点击；
 *   拖动后取消点击；子按钮（取消回复 / 分享 / 举报）正常消费自己的事件、不触发胶囊点击。
 * - **材质**：静止清透（0.30 / 5dp），交互逐步磨砂（0.62 / 14dp，按住更白更不透）+ 边缘光增强。
 *   这些是 §6.2 的校准**起始候选**，先交浅深对比再固定；[GlassTokens] 全局参数一个不碰。
 *
 * 🔴 内容（图标/文字）必须画在玻璃**上面**：调用方把内容放进 [content]，本组件最后画它。
 * 🔴 [backdrop] 必须来自兄弟节点（同父采样 = 循环绘制 = SIGSEGV，GlassActionPanel 同款约束）。
 */
@Composable
internal fun CommentGlassPill(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 关掉**点击**语义，但手势（拖动跟手 + 长按材质反馈）照常。
     * 右侧操作组就是这种：它没有整体点击，点空白区不该有反应，但仍要跟手。
     */
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    dragBounds: Pair<Dp, Dp> = 10.dp to 8.dp,
    // 🔴 跟主题走，不能写死浅色（A25 教训，GlassActionPanel 同款默认）
    surfaceColor: Color = if (GlassTokens.DarkGlass) GlassTokens.ContainerDark else GlassTokens.ContainerLight,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxX = with(density) { dragBounds.first.toPx() }
    val maxY = with(density) { dragBounds.second.toPx() }
    val state = remember(scope, maxX, maxY) { CommentPillState(scope, maxX, maxY) }

    Box(
        modifier
            .semantics {
                // 不可点的胶囊不该对无障碍服务谎称「有点击」（右侧操作组）
                if (enabled) {
                    role = Role.Button
                    onClick { onClick(); true }
                }
            }
            .pointerInput(state, enabled, onClick) {
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis.toLong()
                val realSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.onDown(down.position)
                    val longPressJob = scope.launch {
                        delay(longPressTimeout)
                        state.onLongPress()
                    }
                    var released = false
                    try { while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            longPressJob.cancel()
                            state.onUp(change.isConsumed, if (enabled) onClick else NO_OP)
                            released = true
                            break
                        }
                        state.onMove(change.positionChange(), realSlop)
                        // 拖动成立后消费位移：父级不再响应、子按钮的点击判死、自己也不会误发点击
                        if (state.passedSlop) change.consume()
                    } } finally {
                        longPressJob.cancel()
                        // 手指丢失或作用域取消时回位，不能留下挂起的长按/充气状态。
                        if (!released) state.onUp(true, NO_OP)
                    }
                }
            }
            .graphicsLayer {
                // 全部读动画/快照值：拖动逐帧只走绘制，不走重组
                val o = state.dragOffset
                translationX = o.x
                translationY = o.y
                val (sx, sy) = dragStretch(o.x, o.y, maxX, maxY, amount = 0.10f)
                val p = state.press.value.coerceIn(0f, 1f)
                // 长按本身先鼓起，再叠加方向伸缩，原地按住也有可见反馈。
                scaleX = sx * (1f + 0.025f * p)
                scaleY = sy * (1f + 0.08f * p)
            },
        // 玻璃是 48dp 命中框、视觉内容 46dp：垂直居中，别让文字贴着上沿（每边各留 1dp）。
        contentAlignment = Alignment.CenterStart,
    ) {
        // 玻璃层：onDrawSurface 天然按 shape 裁剪 → surface/高光/材质永远不会越出胶囊
        Box(
            Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(commentBlurDp(state.press.value).dp.toPx())
                        lens(8.dp.toPx(), 12.dp.toPx())
                    },
                    highlight = { Highlight.Default.copy(alpha = commentHighlightAlpha(state.press.value)) },
                    shadow = { Shadow(alpha = commentShadowAlpha()) },
                    innerShadow = { InnerShadow(radius = 8.dp, alpha = commentInnerShadowAlpha()) },
                    onDrawSurface = {
                        drawRect(surfaceColor.copy(alpha = commentSurfaceAlpha(state.press.value)))
                        // 局部跟手高光：半径按胶囊短边、中心限在胶囊内 —— 「按住填满底部」的反面
                        val p = state.press.value
                        if (p > 0f) {
                            val radius = size.minDimension * commentHighlightRadiusFactor
                            val c = clampToPill(state.finger, size.width, size.height)
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(Color.White.copy(alpha = 0.28f * p), Color.Transparent),
                                    center = c,
                                    radius = radius,
                                ),
                                radius = radius,
                                center = c,
                            )
                            // 同一手指坐标驱动边缘亮带，不能只移动内部亮斑。
                            drawRoundRect(
                                brush = Brush.radialGradient(
                                    listOf(Color.White.copy(alpha = 0.65f * p), Color.Transparent),
                                    center = c,
                                    radius = size.minDimension * 2f,
                                ),
                                cornerRadius = CornerRadius(size.height / 2f),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                    },
                ),
        )
        // 内容最后画：文字/图标压在玻璃表面之上（A29 教训：顺序反了字就看不见）
        content()
    }
}

/** 不可点胶囊的占位回调：手势照跑，抬起时什么也不发生（省得每次重组新建 lambda）。 */
private val NO_OP: () -> Unit = {}

// ── 纯函数：单测打在这里（CommentGlassPillTest） ──────────────────────────

/** 高光半径 = 胶囊短边 × 此系数：永远只有胶囊自身尺度，不再出现按整窗尺寸铺光。 */
internal const val commentHighlightRadiusFactor = 1.2f

/**
 * 高光中心必须落在胶囊内：手指坐标是**胶囊局部**坐标，长按后原地按着不动时
 * 也在胶囊里；拖出界时钳到边缘，光不会跑到相邻控件上。
 */
internal fun clampToPill(finger: Offset, width: Float, height: Float): Offset =
    Offset(finger.x.coerceIn(0f, width.coerceAtLeast(0f)), finger.y.coerceIn(0f, height.coerceAtLeast(0f)))

/**
 * 松手要不要真的触发点击。
 *
 * 三条否决，缺一条就会出用户能感知的错：
 * - [passedSlop]：拖过了 → 用户在拖不在点，再触发就是「拖一下把输入框弹开了」；
 * - [longPressed]：长按过了 → 这是材质反馈手势，不是点击；
 * - [upConsumed]：抬起事件已被子控件消费（「取消回复」/分享/举报）→ 再触发就是
 *   「点取消顺带把输入框也打开了」。
 */
internal fun shouldFirePillClick(passedSlop: Boolean, longPressed: Boolean, upConsumed: Boolean): Boolean =
    !passedSlop && !longPressed && !upConsumed

/**
 * 四向有界跟手：未超限线性跟随（跟手），超出按 tanh 软限幅（渐近上限、方向不变）。
 * 零安全：上限 ≤0 属非法布局参数，返回零位移而不是 NaN/Infinity。
 */
internal fun boundedDrag(dx: Float, dy: Float, maxX: Float, maxY: Float): Offset {
    if (maxX <= 0f || maxY <= 0f) return Offset.Zero
    return Offset(maxX * tanh(dx / maxX), maxY * tanh(dy / maxY))
}

/**
 * 方向拉伸：横向拖 → 横向拉长、纵向略收；纵向反之；静止 1:1。
 * 输入用**有界后**的位移，所以拉伸天然封顶（§6.3 的 3%–6% 区间取 5%）。
 */
internal fun dragStretch(dx: Float, dy: Float, maxX: Float, maxY: Float, amount: Float = 0.05f): Pair<Float, Float> {
    if (maxX <= 0f || maxY <= 0f) return 1f to 1f
    val hx = (dx / maxX).coerceIn(-1f, 1f)
    val vy = (dy / maxY).coerceIn(-1f, 1f)
    val h = abs(hx) * amount
    val v = abs(vy) * amount
    return (1f + h - v * 0.6f) to (1f + v - h * 0.6f)
}

/** 材质端点（2026-10-13 用户拍板翻转）：**静止清透 → 交互磨砂**（按住更白、更不透）。 */
internal const val commentRestSurfaceAlpha = 0.30f
internal const val commentRestBlurDp = 5f
internal const val commentActiveSurfaceAlpha = 0.62f
internal const val commentActiveBlurDp = 14f

internal fun commentSurfaceAlpha(progress: Float): Float =
    commentRestSurfaceAlpha + (commentActiveSurfaceAlpha - commentRestSurfaceAlpha) * progress.coerceIn(0f, 1f)

internal fun commentBlurDp(progress: Float): Float =
    commentRestBlurDp + (commentActiveBlurDp - commentRestBlurDp) * progress.coerceIn(0f, 1f)

/** 边缘光随交互增强（Solid 档 0.28 为静止基线）。 */
internal fun commentHighlightAlpha(progress: Float): Float =
    GlassPanelStyle.Solid.highlightAlpha + 0.10f * progress.coerceIn(0f, 1f)

internal fun commentShadowAlpha(): Float = GlassPanelStyle.Solid.shadowAlpha

internal fun commentInnerShadowAlpha(): Float = GlassPanelStyle.Solid.innerShadowAlpha

// ── 手势状态机 ────────────────────────────────────────────────────────────

/**
 * 长按 → 材质反馈；touchSlop 后 → 二维拖动（消费事件、取消点击）；松手 → 弹簧回位。
 * 松手事件已被子按钮消费时不触发点击（否则「取消回复」会顺带打开输入框）。
 *
 * 🔴 为什么拖动位移用普通 state 而不是 [Animatable]：手势回调在
 * `awaitEachGesture` 里，那个作用域是 `@RestrictsSuspension` 的 —— **不允许**在里面
 * 调 `Animatable.snapTo`（编译期就拦，报 "restricted coroutine scope"）。
 * 而拖动必须**同步**落值，用 `scope.launch { snapTo() }` 会迟一帧，跟手就发虚。
 * 所以：拖动位移同步写 state（零延迟），**只有松手后的弹簧回位**才用 [Animatable]
 * 走动画，逐帧把值写回同一个 state。
 */
internal class CommentPillState(
    private val scope: CoroutineScope,
    private val maxX: Float,
    private val maxY: Float,
) {
    val press = Animatable(0f)
    var dragOffset by mutableStateOf(Offset.Zero)
        private set
    var finger by mutableStateOf(Offset.Zero)
        private set

    var passedSlop = false
        private set
    private val settle = Animatable(Offset.Zero, Offset.VectorConverter)
    private var settleJob: Job? = null
    private var pressJob: Job? = null
    private var accumulated = Offset.Zero
    private var gestureOrigin = Offset.Zero
    private var longPressed = false

    fun onDown(position: Offset) {
        // 上一次回位动画还在跑就再按下：先掐掉，否则两个动画同时写 dragOffset 会打架
        settleJob?.cancel()
        settleJob = null
        pressJob?.cancel()
        gestureOrigin = dragOffset
        accumulated = Offset.Zero
        passedSlop = false
        longPressed = false
        finger = position
    }

    fun onMove(delta: Offset, realSlop: Float) {
        if (delta == Offset.Zero) return
        // 拖动成立后同样更新：旧版只在touchSlop以内更新，光效会停在按下处。
        finger += delta
        accumulated += delta
        if (!passedSlop && accumulated.getDistance() > realSlop) {
            passedSlop = true
            animatePress(1f)
        }
        if (passedSlop) {
            dragOffset = boundedDrag(
                accumulated.x + gestureOrigin.x, accumulated.y + gestureOrigin.y, maxX, maxY,
            )
        }
    }

    fun onUp(upConsumed: Boolean, onClick: () -> Unit) {
        if (shouldFirePillClick(passedSlop, longPressed, upConsumed)) onClick()
        val from = dragOffset
        animatePress(0f)
        settleJob = scope.launch {
            settle.snapTo(from)
            settle.animateTo(Offset.Zero, backSpec) { dragOffset = value }
        }
    }

    /** 长按：进入材质反馈，但不取消后续的拖动可能（按住不动 → 反馈亮起，再拖 → 跟手）。 */
    fun onLongPress() {
        if (!passedSlop) {
            longPressed = true
            animatePress(1f)
        }
    }

    private fun animatePress(target: Float) {
        pressJob?.cancel()
        pressJob = scope.launch { press.animateTo(target, pressSpec) }
    }

    internal companion object {
        val pressSpec = spring(0.5f, 300f, 0.001f)
        val backSpec = spring(0.6f, 350f, Offset.VisibilityThreshold)
    }
}
