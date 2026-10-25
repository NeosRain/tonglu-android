/*
 * Copyright (c) 2026
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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.runtime.getValue

/**
 * 评论输入面板的进出场进度（0 = 完全收起，1 = 完全升起）。
 *
 * ## 为什么单独做一个（而不是复用 `OverlayMotionState`）
 * 输入面板**不是**一层独立的导航目标 —— 它就是详情页内部的一个底部面板。
 * 复用一个带 `onClose` / 系统返回接管的完整 motion 会引入**第二条返回链路**，
 * 与详情页自己的返回打架（plan §四.1 明确要求「不要再堆几个互不约束的 Boolean」）。
 * 这里只做一件事：给面板与遮罩提供一个**可绘制的进度**。
 *
 * ## 🔴 为什么暴露 `State` 而不是 `Float`（BUGS A16 的同一课）
 * 返回 `Float` 的 @Composable 一旦被 `val` 接住，**读值就发生在组合期**，
 * 调用方的重组作用域会被逐帧失效 → 整页每帧重组。
 * 所以这里对外给的是 [panel]/[scrim] 两个 `State`，**只允许在 `graphicsLayer{}` 里读**。
 *
 * ## 时长为什么是 200ms / 就是「初试值」
 * 录屏是 24fps，**不足以**测出精确毫秒（plan §四.3）。200ms 落在建议区间
 * 180–240ms 内，属于初始设计值，**不是酷安实测值**。
 * 键盘自己的时长由系统控制 —— 这里**不**用固定 delay 去「假装同步」。
 */
@Stable
class ComposerMotionState internal constructor() {
    private val progress = Animatable(0f)

    /** 面板位置进度：0 → 1 升起。绘制相位读。 */
    internal val panel: State<Float> = progress.asState()

    /** 遮罩透明度进度：与面板同源，保证「面板升起 = 遮罩出现」不会错拍。 */
    val scrim: State<Float> = progress.asState()

    internal suspend fun to(target: Float) {
        progress.animateTo(target, tween(ENTER_MS, easing = FastOutSlowInEasing))
    }

    /** 面板离屏距离（dp）→ 像素。写成 `GraphicsLayerScope` 的扩展，方便直接在 `graphicsLayer{}` 里用。 */
    fun offsetPx(scope: GraphicsLayerScope): Float =
        (1f - progress.value) * PANEL_HEIGHT_DP * scope.density

    companion object {
        /** 面板进场 / 收起时长（ms）。初试值，见类注释。 */
        const val ENTER_MS = 200

        /**
         * 面板「完全收起」时向下偏移的距离（dp）。
         *
         * 取一个比面板最大高度更大的值（面板最高约 132dp 输入 + 上下留白），
         * 保证收起那一刻它整个在屏幕外 —— 只挪一半会露出上边缘。
         */
        const val PANEL_HEIGHT_DP = 260f
    }
}

/**
 * 记住一个输入面板的进出场 [ComposerMotionState]，并在首次组合时播入场。
 *
 * 用法（**只在绘制相位读值**）：
 * ```
 * val motion = rememberComposerMotion()
 * ...
 * Modifier.graphicsLayer { translationY = motion.offsetPx(this) }
 * ```
 *
 * 面板的**关闭**由调用方直接写 `open = false` 把整块从组合里移除 ——
 * 这里刻意**不**注册任何返回链路，避免和详情页的返回打架（plan §四.1）。
 */
@Composable
fun rememberComposerMotion(): ComposerMotionState {
    val state = remember { ComposerMotionState() }
    LaunchedEffect(state) { state.to(1f) }
    return state
}
