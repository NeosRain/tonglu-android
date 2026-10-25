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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 液态玻璃顶栏容器。
 *
 * 方形玻璃覆盖整段顶部，包括状态栏和挖孔安全区；交互控件在安全区下方。
 * 磨砂和容器底色沿用 [GlassTokens]。
 *
 * ## 前提：顶栏下面必须有内容能滚过去
 * backdrop 采样的是**页面内容层**，所以调用方要把顶栏做成**叠加层**（盖在内容之上），
 * 并给各页面的滚动容器留出顶部内边距（`contentPadding.top`），让列表能滑到顶栏底下。
 * 如果只是在流式布局里空出一块地方再放这个栏，它采样到的是一片空白，玻璃就会变成一块实心板。
 *
 * ## 修饰符顺序（踩过坑）
 * 高度和内边距必须在 [drawBackdrop] **之后**追加，跟底栏保持一致：
 * `drawBackdrop` 画的是它所在节点的实测尺寸，排在它后面的 `height` 才会把节点压到该高度。
 * 反过来（先 height/padding、再 drawBackdrop）会让玻璃把外边距也算进形状里，变成一条胖胶囊。
 * 所以调用方只管摆放（align），安全区和尺寸由本组件负责。
 *
 * @param backdrop 页面内容层的 backdrop（与底栏传的是同一个）
 * @param barHeight 玻璃条本体高度（不含外边距）；默认取自 [GlassTokens.TopBarHeightDp]
 * @param contentPadding 玻璃条内部左右留白
 */
@Composable
fun GlassTopBar(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    barHeight: Dp = GlassTokens.TopBarHeightDp.dp,
    contentPadding: Dp = 12.dp,
    content: @Composable RowScope.() -> Unit,
) {
    // 同底栏：口径统一在 GlassTokens.DarkGlass，不单独读系统夜间
    val isLight = !GlassTokens.DarkGlass
    val containerColor = if (isLight) GlassTokens.ContainerLight else GlassTokens.ContainerDark

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(0.dp) },
                effects = {
                    vibrancy()
                    blur(GlassTokens.BlurDp.dp.toPx())
                },
                // 与底栏同一套常驻光影，两条栏才是「同一块玻璃」
                highlight = { Highlight.Default.copy(alpha = GlassTokens.RestHighlightAlpha) },
                shadow = { Shadow(alpha = GlassTokens.RestShadowAlpha) },
                innerShadow = {
                    InnerShadow(
                        radius = GlassTokens.RestInnerShadowRadiusDp.dp,
                        alpha = GlassTokens.RestInnerShadowAlpha
                    )
                },
                onDrawSurface = {
                    drawRect(containerColor.copy(alpha = GlassTokens.ContainerAlpha))
                }
            )
            .fillMaxWidth()
            // 留白必须在玻璃绘制节点内部，否则状态栏上方会露出一块空白。
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(top = GlassTokens.TopBarVerticalMarginDp.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(barHeight).padding(horizontal = contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}


