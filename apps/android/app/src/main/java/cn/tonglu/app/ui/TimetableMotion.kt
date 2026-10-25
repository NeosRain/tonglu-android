package cn.tonglu.app.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

/**
 * 从首页卡片的实际位置展开，所有关闭入口都先缩回卡片，再移除窗口。
 * 课表只按全屏尺寸布局一次；逐帧变化留在绘制层，避免重排网格和周次 Pager。
 */
@Composable
internal fun AnimatedTimetableWindow(
    originBounds: Rect,
    returnBounds: Rect = originBounds,
    onDismiss: () -> Unit,
    content: @Composable (close: () -> Unit, dragHandle: Modifier) -> Unit,
) {
    val motion = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestReturnBounds by rememberUpdatedState(returnBounds)
    val density = LocalDensity.current
    val closeThreshold = with(density) { 72.dp.toPx() }
    var closing by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var dragPixels by remember { mutableFloatStateOf(0f) }
    var windowHeight by remember { mutableFloatStateOf(1f) }

    fun displayedProgress() = (motion.value - dragFraction).coerceIn(0f, 1f)

    val close: () -> Unit = {
        if (!closing) {
            closing = true
            // 满屏课表是「课表」tab 最常用的二级界面：BACK、下拉收起、关闭按钮全走这里。
            // 收起动画结束窗口移除后，紧跟着的快速点击会命中刚露出来的顶栏 → 先开冷却。
            OverlayDismissGuard.arm()
            scope.launch {
                // 接续手指当前所在位置，关闭按钮、返回键、下拖共用同一条反向动画。
                val start = displayedProgress()
                dragFraction = 0f
                motion.snapTo(start)
                motion.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
                latestDismiss()
            }
        }
    }
    val latestClose by rememberUpdatedState(close)

    // 2026-10-06 补左滑跟手返回：满屏课表是 Dialog（独立 Window），系统预测性返回进不来，
    // 之前只有「下拖收起 / 点把手 / 返回键」三条路，左边缘横滑没有任何反馈。
    // 这里叠一层自识别拖动（与阅读器/书架同一套 OverlayMotion），提交后走上面同一条 close。
    val edgeBack = rememberEdgeBackState()
    val predictiveBack = OverlayMotion.enabledState
    val backProgress = rememberEdgeBackProgressState(edgeBack)
    LaunchedEffect(Unit) { edgeBack.onCommit = { latestClose() } }

    LaunchedEffect(Unit) {
        motion.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        opened = true
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        SideEffect {
            // 系统的窗口淡入/淡出不能叠加在卡片动画上，也不能在收起前恢复默认遮罩。
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setWindowAnimations(0)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !AppTheme.darkState
            }
        }

        val dragHandle = Modifier.pointerInput(closeThreshold) {
            var accepted = false
            detectVerticalDragGestures(
                onDragStart = {
                    accepted = !closing && motion.value >= 0.999f && !motion.isRunning
                    dragPixels = 0f
                },
                onDragEnd = {
                    if (accepted) {
                        if (dragPixels >= closeThreshold) latestClose()
                        else scope.launch {
                            val start = displayedProgress()
                            dragFraction = 0f
                            motion.snapTo(start)
                            motion.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 450f))
                        }
                    }
                    accepted = false
                },
                onDragCancel = {
                    if (accepted) scope.launch {
                        val start = displayedProgress()
                        dragFraction = 0f
                        motion.snapTo(start)
                        motion.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 450f))
                    }
                    accepted = false
                },
            ) { change, amount ->
                if (accepted) {
                    change.consume()
                    dragPixels = (dragPixels + amount).coerceAtLeast(0f)
                    dragFraction = (dragPixels / windowHeight.coerceAtLeast(closeThreshold * 4)).coerceAtMost(0.45f)
                }
            }
        }

        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { windowHeight = it.height.toFloat() }
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.10f * displayedProgress())) },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .edgeSwipeBack(edgeBack, enabled = predictiveBack)
                    .graphicsLayer {
                    val p = displayedProgress()
                    // 在全屏切换周次可能改变首页卡片高度，收起应回到此时的实际卡片。
                    val targetBounds = if (opened) latestReturnBounds else originBounds
                    val start = targetBounds.takeIf { it.width > 0f && it.height > 0f }
                        ?: Rect(size.width * 0.06f, size.height * 0.16f, size.width * 0.94f, size.height * 0.84f)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = start.width / size.width + (1f - start.width / size.width) * p
                    scaleY = start.height / size.height + (1f - start.height / size.height) * p
                    translationX = start.left * (1f - p)
                    translationY = start.top * (1f - p)
                    shape = RoundedCornerShape(24.dp * (1f - p))
                    clip = true
                    // 开头/结尾只做短暂交接，让首页原卡片接住缩放后的课表。
                    alpha = (p * 5f).coerceIn(0f, 1f)
                    // 左滑跟手：位移/缩放/淡出叠加在卡片展开动画之上（不替换，理由同 ArticleReader）。
                    applyEdgeBack(backProgress.value, edgeBack.fromLeft, fade = false)
                },
            ) {
                content(close, dragHandle)
            }
        }
    }
}
