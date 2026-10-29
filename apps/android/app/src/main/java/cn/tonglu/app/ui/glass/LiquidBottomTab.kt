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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

internal val LocalSelectTab =
    staticCompositionLocalOf<(Int) -> Unit> { {} }

internal val LocalTabInputEnabled = staticCompositionLocalOf { true }

/**
 * 「手指按住 / 松开」通道：把单个 tab 的按压状态上报给父组件，由父组件决定胶囊怎么变大。
 *
 * 为什么要单独开一条通道：[LocalSelectTab] 只在**切换了标签**时才动（内部有
 * `if (currentIndex != index)` 的保护），所以按住当前已选中的那个 tab 是没有反馈的。
 * 而「按住就该变大」跟切不切页无关，必须有独立于选中逻辑的按压事件。
 *
 * 第一个参数是 tab 索引：父组件靠它判断这次按压要不要拉起"重"反馈
 * （详见 [cn.tonglu.app.ui.glass.DampedDragAnimation.press]），
 * 停留当前页连点不该反复重跑整块玻璃的模糊与折射。
 */
internal val LocalPressTab =
    staticCompositionLocalOf<(Int, Boolean) -> Unit> { { _, _ -> } }

/**
 * 底部导航栏的单个标签项。
 *
 * 点击时同时做三件事：
 * 1. 通知父级切换页面（[onClick]）
 * 2. 通过 [LocalSelectTab] 让指示器胶囊以弹簧物理滑到对应槽位
 * 3. 通过 [LocalPressTab] 把按下 / 松开上报给父级 —— 按住任意一个 tab（哪怕就是当前这个）
 *    都会让胶囊放大、透镜折射增强，松手回弹
 *
 * 注意 [index] 必须传对：父组件靠它决定指示器落点，传错会出现"先跳到首项再滑回来"的抖动。
 */
@Composable
fun RowScope.LiquidBottomTab(
    index: Int = 0,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    val inputEnabled = LocalTabInputEnabled.current
    val selectTab = LocalSelectTab.current
    val pressTab = LocalPressTab.current
    val interactionSource = remember { MutableInteractionSource() }

    // 用交互流而不是 collectIsPressedAsState：后者的初始值 false 会在首次组合时
    // 多触发一次"松开"，把指示器刚滑出去的动画提前泄气
    LaunchedEffect(interactionSource, inputEnabled) {
        if (!inputEnabled) return@LaunchedEffect
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> pressTab(index, true)
                is PressInteraction.Release -> pressTab(index, false)
                is PressInteraction.Cancel -> pressTab(index, false)
            }
        }
    }

    Column(
        modifier
            .clip(Capsule())
            .then(if (inputEnabled) Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = {
                    onClick()
                    selectTab(index)
                }
            ) else Modifier)
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                // 按压时整项放大（1.0 → 1.2），由父组件的 pressProgress 驱动
                val scaleValue = scale()
                scaleX = scaleValue
                scaleY = scaleValue
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
