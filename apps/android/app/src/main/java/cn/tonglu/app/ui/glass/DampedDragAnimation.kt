/*
 * Copyright (c) 2026
 *
 * This file is derived from AndroidLiquidGlass (Backdrop):
 * https://github.com/Kyant0/AndroidLiquidGlass
 * Copyright (c) Kyant0 and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.tonglu.app.ui.glass

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 按下滑向触点，拖动跟手，松手吸附。 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    val onDragCancelled: DampedDragAnimation.() -> Unit = {},
) {
    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 0.001f)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)
    private val velocityTracker = VelocityTracker()
    private var positionJob: Job? = null
    private var shapeJob: Job? = null
    private var settleJob: Job? = null
    private var pressJob: Job? = null
    private var latestTarget = initialValue
    var didDrag: Boolean = false
        private set

    var isDragging by mutableStateOf(false)
        private set
    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = latestTarget
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
    val pressProgress: Float get() = pressAnimation.value.coerceIn(0f, 1f)
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    // 在 Initial 阶段拿到胶囊手势；消费水平移动，避免被页面 Pager 接走。
    val modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            settleJob?.cancel(); positionJob?.cancel()
            latestTarget = value
            isDragging = true
            didDrag = false
            // 整条底栏统一处理指针，子标签只保留无障碍点击。
            down.consume()
            press(heavy = false)
            onDragStarted(down.position)
            var released = false
            var pending = Offset.Zero
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        released = true
                        break
                    }
                    val delta = change.position - change.previousPosition
                    pending += delta
                    if (!didDrag && kotlin.math.abs(pending.x) > viewConfiguration.touchSlop) {
                        didDrag = true
                        activatePress()
                    }
                    if (delta != Offset.Zero) {
                        change.consume()
                        // 使用稳定底栏坐标，不累加移动胶囊自身坐标造成的偏差。
                        onDrag(size, change.position)
                    }
                }
            } finally {
                isDragging = false
                if (released) onDragStopped() else onDragCancelled()
                if (!released) release()
            }
        }
    }

    /**
     * @param heavy false = 只给轻微缩放（1.04），不拉起完整透镜/模糊斜坡。
     *
     * 用途：按住**已经是当前页**的那个 tab。那种点击不切页、没啥可反馈的，
     * 但完整斜坡会让整块玻璃的 blur + lens + vibrancy 每帧重跑 ——
     * 停在当前页连点，实测把整页和底栏一起拖垮（work/perf/tap_jank.py：
     * 按住 120ms 连点 12 次烧掉 235 jiffies，是瞬间点击的 6 倍，静置的 200 多倍）。
     * 切页和拖胶囊仍然走 heavy = true，手感不变。
     */
    fun press(heavy: Boolean) {
        settleJob?.cancel()
        velocityTracker.resetTracking()
        velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(latestTarget, 0f))
        // 短点击仅轻微缩放；按住或开始拖动才启用完整透镜与底板变化。
        pressJob?.cancel()
        animateShape(0f, initialScale * 1.04f)
        if (!heavy) {
            pressJob = null
            return
        }
        pressJob = animationScope.launch {
            delay(90)
            // 避免激活时取消正在执行的自身协程。
            pressJob = null
            activatePress()
        }
    }

    private fun activatePress() {
        pressJob?.cancel()
        pressJob = null
        animateShape(1f, pressedScale)
    }
    fun release() { if (!isDragging) { pressJob?.cancel(); animateShape(0f, initialScale) } }

    private fun animateShape(press: Float, scale: Float) {
        shapeJob?.cancel()
        shapeJob = animationScope.launch {
            launch { pressAnimation.animateTo(press, spring(1f, 700f, 0.001f)) }
            launch { scaleXAnimation.animateTo(scale, spring(0.6f, 250f, 0.001f)) }
            launch { scaleYAnimation.animateTo(scale, spring(0.7f, 250f, 0.001f)) }
        }
    }

    fun updateValue(value: Float) {
        // 同步累积目标位置，避免高频事件排队时丢掉手指位移。
        latestTarget = value.coerceIn(valueRange)
        val target = latestTarget
        velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(target, 0f))
        val trackedVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
        positionJob?.cancel()
        positionJob = animationScope.launch {
            valueAnimation.snapTo(target)
            velocityAnimation.snapTo(trackedVelocity)
        }
    }

    fun glideToPress(value: Float) {
        val target = value.coerceIn(valueRange)
        latestTarget = target
        positionJob?.cancel()
        // A tap needs travel from the selected tab to the finger; drag events take over immediately.
        positionJob = animationScope.launch {
            valueAnimation.animateTo(target, spring(0.9f, 550f, visibilityThreshold))
        }
    }

    fun animateToValue(value: Float, morph: Boolean = false) {
        latestTarget = value.coerceIn(valueRange)
        val target = latestTarget
        positionJob?.cancel()
        settleJob?.cancel()
        // 拖动中外部页面变化不应抢走手指的位置。
        if (isDragging) return
        pressJob?.cancel()
        if (morph) animateShape(1f, pressedScale) else animateShape(0f, initialScale)
        settleJob = animationScope.launch {
            coroutineScope {
                launch { valueAnimation.animateTo(target, if (morph) spring(0.85f, 650f, visibilityThreshold) else tween(220, easing = FastOutSlowInEasing)) }
                launch { velocityAnimation.animateTo(0f, spring(1f, 450f, 0.001f)) }
                if (morph) {
                    // 🔴 修复「松手回位明显一顿」（2026-10-13 真机确认）：旧时序是
                    // 「充气态飞完全程 → 落位后才 release() 放气」，两段式在落位瞬间有
                    // 形态+速度的跳变，观感就是卡一下；且整段飞行 pressProgress≡1，
                    // 面板 blur 与胶囊 lens 全程停在按压档，渲染最贵的一段恰好最长。
                    // 现在改为与飞行并行放气：先保形 100ms（保留甩出去的拉伸），
                    // 然后边飞边缩，落位时已是正常胶囊 —— 一段连续运动。
                    delay(100)
                    animateShape(0f, initialScale)
                }
            }
            release()
        }
    }
}



