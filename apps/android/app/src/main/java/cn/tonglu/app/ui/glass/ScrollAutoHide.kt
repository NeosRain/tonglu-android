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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 往上滑多少才收起底栏（比显示阈值大，避免轻轻一碰就藏） */
private val HideThreshold = 56.dp

/** 往下滑多少就立刻放出底栏 */
private val ShowThreshold = 20.dp

/**
 * 底栏「上滑隐藏 / 下滑出现」控制器。
 *
 * 用法：把 [connection] 挂到**页面滚动容器的祖先**上（`Modifier.nestedScroll(connection)`），
 * 再用 [hiddenFraction] 驱动底栏的 `translationY`：
 *
 * ```
 * val barHider = rememberBarAutoHide()
 * Column(Modifier.nestedScroll(barHider.connection)) { ... 各页面 ... }
 * GlassBottomBar(
 *     modifier = Modifier.graphicsLayer { translationY = hideDistancePx * barHider.hiddenFraction }
 * )
 * ```
 *
 * 为什么不用 `LazyListState`：各页面滚动容器不统一（5 个页面各一个 LazyColumn，
 * 广场外面还套了下拉刷新），从 nested scroll 这一层统一拦最省事，也不用给每个页面传 state。
 */
class BarAutoHide internal constructor(
    private val scope: CoroutineScope,
    private val hideThreshold: Float,
    private val showThreshold: Float,
) {
    /** 0f = 完全露出，1f = 完全收起 */
    private val animation = Animatable(0f)

    /** 未达阈值前的位移累积；方向一变就清零重算 */
    private var accumulated = 0f

    val hiddenFraction: Float get() = animation.value

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            // Observe before a collapsing page header consumes the gesture. A short page
            // may never scroll its list, but its header must still uncover the group tabs.
            val dy = available.y
            if (dy == 0f) return Offset.Zero

            // 手指反向的瞬间就重新计步，否则「来回蹭」也能把位移攒够、出现误触发
            accumulated = if (accumulated * dy < 0f) dy else accumulated + dy

            if (accumulated > showThreshold && animation.targetValue > 0f) {
                accumulated = 0f
                scope.launch {
                    animation.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))
                }
            } else if (accumulated < -hideThreshold && animation.targetValue < 1f) {
                accumulated = 0f
                scope.launch {
                    animation.animateTo(1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))
                }
            }
            return Offset.Zero
        }
    }

    /** 强制立刻放出底栏（换页、进全屏页返回等场景） */
    fun show() {
        accumulated = 0f
        if (animation.value == 0f) return
        scope.launch {
            animation.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
        }
    }
}

@Composable
fun rememberBarAutoHide(): BarAutoHide {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val hidePx = with(density) { HideThreshold.toPx() }
    val showPx = with(density) { ShowThreshold.toPx() }
    return remember(scope, hidePx, showPx) { BarAutoHide(scope, hidePx, showPx) }
}
