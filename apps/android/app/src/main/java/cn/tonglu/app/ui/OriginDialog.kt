package cn.tonglu.app.ui

import android.animation.ValueAnimator
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.ui.focus.updatePomodoroDialogContentSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal val LocalOriginDialogWindow = staticCompositionLocalOf<Window?> { null }

/** 内容可自行决定何时关闭，例如表单校验或保存成功后再调用 [closeWith]。 */
internal class OriginDialogScope internal constructor(
    private val onDismissRequest: State<() -> Unit>,
    private val dismissEnabled: State<Boolean>,
    private val scope: CoroutineScope,
) {
    private val dismissal = OriginDialogDismissal()
    private val progress = Animatable(if (duration(entering = true) == 0) 1f else 0f)
    internal val back = EdgeBackState()
    internal var backPreview: State<Float>? = null
    private var previewAtClose = 0f
    internal val isClosing: Boolean get() = dismissal.isClosing
    internal val fraction: Float
        get() = progress.value * (1f - .35f * (if (isClosing) previewAtClose else backPreview?.value ?: 0f))

    fun dismiss() {
        if (dismissEnabled.value) closeWith(onDismissRequest.value)
    }

    fun closeWith(action: () -> Unit) {
        dismissal.request(action) { finish ->
            val durationMs = duration(entering = false)
            if (durationMs == 0 || !scope.isActive) {
                finish()
            } else {
                previewAtClose = backPreview?.value ?: 0f
                scope.launch {
                    progress.animateTo(0f, tween(durationMs, easing = FastOutSlowInEasing))
                    finish()
                }
            }
        }
    }

    internal fun duration(entering: Boolean): Int = originDialogDuration(
        entering, scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f,
        Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled(),
    )

    internal suspend fun enter() {
        if (isClosing) return
        val durationMs = duration(entering = true)
        if (durationMs == 0) progress.snapTo(1f)
        else progress.animateTo(1f, tween(durationMs, easing = EmphasizedDecelerate))
    }

    internal fun finishClose() = dismissal.finish()
}

internal class OriginDialogDismissal {
    private var pending: (() -> Unit)? = null
    var isClosing by mutableStateOf(false)
        private set

    fun request(action: () -> Unit, animateThen: (() -> Unit) -> Unit) {
        if (isClosing) return
        isClosing = true
        pending = action
        animateThen(::finish)
    }

    fun finish() {
        val action = pending ?: return
        pending = null
        action()
    }
}

internal fun originDialogDuration(entering: Boolean, animatorScale: Float, animatorsEnabled: Boolean): Int =
    when {
        animatorScale == 0f || !animatorsEnabled -> 0
        entering -> OverlayMotion.ORIGIN_ENTER_MS
        else -> OverlayMotion.ORIGIN_EXIT_MS
    }

internal fun dialogTransformOrigin(sourceOnScreen: Offset, contentOnScreen: Offset, size: Size): TransformOrigin {
    val local = sourceOnScreen - contentOnScreen
    // 来源可能在卡片外，不能把支点限制在卡片边缘。
    return TransformOrigin(local.x / size.width, local.y / size.height)
}

@Composable
private fun rememberOriginDialogScope(onDismissRequest: () -> Unit, dismissEnabled: Boolean): OriginDialogScope {
    val dismiss = rememberUpdatedState(onDismissRequest)
    val enabled = rememberUpdatedState(dismissEnabled)
    val scope = rememberCoroutineScope()
    val actions = remember { OriginDialogScope(dismiss, enabled, scope) }
    // 窗口在退场中被宿主销毁时，仍交付已经确认的操作。
    DisposableEffect(actions) { onDispose { actions.finishClose() } }
    return actions
}

@Composable
internal fun OriginDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    dismissEnabled: Boolean = true,
    content: @Composable OriginDialogScope.() -> Unit,
) {
    val actions = rememberOriginDialogScope(onDismissRequest, dismissEnabled)
    OriginDialogWindow(actions, properties, dismissEnabled) { actions.content() }
}

@Composable
internal fun OriginAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable OriginDialogScope.() -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (OriginDialogScope.() -> Unit)? = null,
    icon: @Composable (OriginDialogScope.() -> Unit)? = null,
    title: @Composable (OriginDialogScope.() -> Unit)? = null,
    text: @Composable (OriginDialogScope.() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
    dismissEnabled: Boolean = true,
) {
    OriginDialog(onDismissRequest, properties, dismissEnabled) {
        val paneLabel = stringResource(androidx.compose.material3.R.string.m3c_dialog)
        Surface(
            modifier.widthIn(min = 280.dp, max = 560.dp).semantics { paneTitle = paneLabel },
            shape = shape, color = containerColor, tonalElevation = tonalElevation,
        ) {
            val typography = MaterialTheme.typography
            Column(Modifier.padding(24.dp)) {
                if (icon != null) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)) {
                        CompositionLocalProvider(LocalContentColor provides iconContentColor) { icon() }
                    }
                }
                if (title != null) {
                    Box(Modifier.align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally)
                        .padding(bottom = 16.dp)) {
                        CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                            ProvideTextStyle(typography.headlineSmall) { title() }
                        }
                    }
                }
                if (text != null) {
                    Box(Modifier.weight(1f, fill = false).padding(bottom = 24.dp)) {
                        CompositionLocalProvider(LocalContentColor provides textContentColor) {
                            ProvideTextStyle(typography.bodyMedium) { text() }
                        }
                    }
                }
                Box(Modifier.align(Alignment.End)) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                        ProvideTextStyle(typography.labelLarge) {
                            OriginAlertButtons {
                                dismissButton?.invoke(this@OriginDialog)
                                confirmButton()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OriginAlertButtons(content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val rowGap = 12.dp.roundToPx()
        val rows = mutableListOf<MutableList<Placeable>>()
        var rowWidth = 0
        for (measurable in measurables) {
            val button = measurable.measure(constraints)
            if (rows.isEmpty() || rowWidth + gap + button.width > constraints.maxWidth) {
                rows.add(mutableListOf(button))
                rowWidth = button.width
            } else {
                rows.last().add(button)
                rowWidth += gap + button.width
            }
        }
        val rowWidths = rows.map { row -> row.sumOf { it.width } + (row.size - 1) * gap }
        val height = rows.sumOf { row -> row.maxOf { it.height } } + (rows.size - 1).coerceAtLeast(0) * rowGap
        val width = constraints.constrainWidth(rowWidths.maxOrNull() ?: 0)
        layout(width, constraints.constrainHeight(height)) {
            // 横排时主按钮靠尾端，换行时仍排在次按钮上方。
            var y = 0
            for (index in rows.indices.reversed()) {
                val row = rows[index]
                var x = width - rowWidths[index]
                for (button in row) {
                    button.placeRelative(x, y)
                    x += button.width + gap
                }
                y += row.maxOf { it.height } + rowGap
            }
        }
    }
}

@Composable
private fun OriginDialogWindow(
    actions: OriginDialogScope,
    properties: DialogProperties,
    dismissEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val parentView = LocalView.current
    val origin = remember { LaunchOrigin.takeDialogOrigin(parentView.mainWindowCenterOnScreen()) }
    Dialog(onDismissRequest = actions::dismiss, properties = properties) {
        val view = LocalView.current
        val window = remember(view) { checkNotNull(view.dialogWindow()) }
        DisposableEffect(window) {
            val attributes = window.attributes
            if (attributes.flags and FLAG_DIM_BEHIND != 0) {
                // 遮罩跟着这扇窗口绘制，子窗口接管焦点时仍能透过其透明区域看见。
                val scrim = Color.Black.copy(alpha = attributes.dimAmount)
                window.setBackgroundDrawable(ColorDrawable(scrim.toArgb()))
                window.clearFlags(FLAG_DIM_BEHIND)
            }
            onDispose { }
        }
        DisposableEffect(window, properties.usePlatformDefaultWidth, properties.decorFitsSystemWindows) {
            // 给卡片留出可画到来源点的范围。内容仍按自身尺寸居中测量。
            window.setLayout(MATCH_PARENT, MATCH_PARENT)
            window.setWindowAnimations(0)
            updatePomodoroDialogContentSize(window.decorView, IntSize.Zero)
            onDispose { updatePomodoroDialogContentSize(window.decorView, null) }
        }
        RecordDialogLaunchTouches(window)
        val preview = rememberEdgeBackProgressState(actions.back, holdOnCommit = true)
        SideEffect { actions.backPreview = preview }
        val backOwner = remember(view) { checkNotNull(view.findViewTreeOnBackPressedDispatcherOwner()) }
        // 绑定 Dialog 自己的 dispatcher，让输入法先处理它的返回。
        CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner) {
            SystemBackProgress(actions.back, properties.dismissOnBackPress && dismissEnabled,
                preview = OverlayMotion.enabledState && actions.duration(entering = false) != 0 && !actions.isClosing,
                onCommit = actions::dismiss)
        }

        var rootCenter by remember { mutableStateOf(Offset.Unspecified) }
        var contentPosition by remember { mutableStateOf(Offset.Unspecified) }
        var contentBounds by remember { mutableStateOf(Rect.Zero) }
        fun sourcePoint(): Offset = if (origin.isSpecified) origin else rootCenter
        fun cardScale(): Float = lerp(.12f, 1f, actions.fraction)
        fun visibleBounds(): Rect {
            val source = sourcePoint()
            if (!source.isSpecified || !contentPosition.isSpecified) return contentBounds
            val pivot = source - contentPosition + contentBounds.topLeft
            val scale = cardScale()
            return Rect(
                pivot + (contentBounds.topLeft - pivot) * scale,
                pivot + (contentBounds.bottomRight - pivot) * scale,
            )
        }
        val laidOut = contentPosition.isSpecified
        LaunchedEffect(actions, laidOut) { if (laidOut) actions.enter() }
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned {
                    rootCenter = it.localToScreen(Offset(it.size.width / 2f, it.size.height / 2f))
                }
                .onPreviewKeyEvent { actions.isClosing }
                .pointerInput(actions, properties.dismissOnClickOutside) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (actions.isClosing) {
                            down.consume()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } else if (properties.dismissOnClickOutside && down.position !in visibleBounds()) {
                            down.consume()
                            val up = waitForUpOrCancellation(PointerEventPass.Initial)
                            if (up != null && up.position !in visibleBounds()) actions.dismiss()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.onGloballyPositioned {
                    contentPosition = it.localToScreen(Offset.Zero)
                    contentBounds = it.boundsInRoot()
                    updatePomodoroDialogContentSize(window.decorView, it.size)
                }.graphicsLayer {
                    val source = sourcePoint()
                    if (source.isSpecified && contentPosition.isSpecified && size.width > 0f && size.height > 0f) {
                        transformOrigin = dialogTransformOrigin(source, contentPosition, size)
                    }
                    scaleX = cardScale()
                    scaleY = scaleX
                    alpha = actions.fraction
                },
                content = { CompositionLocalProvider(LocalOriginDialogWindow provides window, content = content) },
            )
        }
    }
}
