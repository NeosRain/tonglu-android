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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.geometry.Size
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
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset

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
 * @param scrollOffsetPx 内容可以滚过顶栏时，提供当前滚动位置以启用夜间渐入
 */
@Composable
fun GlassTopBar(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    barHeight: Dp = GlassTokens.TopBarHeightDp.dp,
    contentPadding: Dp = 12.dp,
    scrollOffsetPx: (() -> Float)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val safeInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val glassEnabled = LocalGlassNavigationEnabled.current
    if (GlassTokens.DarkGlass && (scrollOffsetPx != null || !glassEnabled)) {
        Box(modifier.fillMaxWidth()) {
            DarkTopBarBackground(
                backdrop = backdrop.takeIf { glassEnabled },
                scrollOffsetPx = scrollOffsetPx ?: { Float.POSITIVE_INFINITY },
                modifier = Modifier.matchParentSize(),
            )
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(safeInsets)
                    .padding(top = GlassTokens.TopBarVerticalMarginDp.dp)
                    .height(barHeight)
                    .padding(horizontal = contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
        return
    }
    // 同底栏：口径统一在 GlassTokens.DarkGlass，不单独读系统夜间
    val statusHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val isLight = !GlassTokens.DarkGlass
    val containerColor = if (isLight) GlassTokens.ContainerLight else GlassTokens.ContainerDark
    val hairline = (if (isLight) Color.Black else Color.White).copy(alpha = if (isLight) .07f else .10f)

    Box(
        modifier
            .drawBackdrop(
                backdrop = rememberBottomBackdrop(backdrop),
                // 连同状态栏一整块铺下来的矩形（ColorOS / iOS 的导航栏做法）：
                // 底部圆角 + 投影在全宽栏上读成「两端被裁掉的一条板子」，平板上尤其明显。
                shape = { RectangleShape },
                effects = {
                    vibrancy()
                    blur(28.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0f) },
                shadow = { Shadow(alpha = 0f) },
                onDrawSurface = {
                    drawFrostSurface(containerColor, if (GlassTokens.DarkGlass) .48f else .42f)
                    drawRect(containerColor.copy(alpha = if (GlassTokens.DarkGlass) .48f else .42f), size = Size(size.width, statusHeight.toPx()))
                    // 底边一条发丝线代替投影：分层清楚，又不会在内容上压出一道灰带。
                    drawLine(hairline, Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f), 1f)
                }
            )
            .fillMaxWidth()
            // 留白必须在玻璃绘制节点内部，否则状态栏上方会露出一块空白。
            .windowInsetsPadding(safeInsets)
            .padding(top = GlassTokens.TopBarVerticalMarginDp.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(barHeight).padding(horizontal = contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}


