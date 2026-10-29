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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * 液态玻璃底部标签栏（移植自 AndroidLiquidGlass 官方实现）。
 *
 * 全物理响应 Tab 点击与指示器滑动：
 * - 点击标签：1.2x 按压缩放 + 透镜折射增强，指示器以弹簧曲线滑到新标签
 * - 拖拽指示器：随手指拉伸变形并跨标签滑行，松手吸附到最近的标签
 *
 * ## 三层结构（关键）
 * 1. **底层**：玻璃面板 `GlassTokens.BarPanelHeightDp`（模糊 + 边缘折射 + 容器色），整条落在页面之上
 * 2. **中层**：`GlassTokens.BarContentHeightDp`、`alpha(0f)` 不可见，但被导出成 [tabsBackdrop]；内容用
 *    `ColorFilter.tint(accentColor)` 染成主题色 —— 它是"指示器要折射的东西"
 * 3. **顶层**：指示器胶囊，宽 1/tabsCount，backdrop 取
 *    `rememberCombinedBackdrop(backdrop, tabsBackdrop)`
 *
 * 第 2、3 层这个组合是精髓：胶囊里透出的不是原始页面，而是**已经染色的图标经过玻璃折射**，
 * 所以滑到哪里，哪里的图标就被"吸"进胶囊里放大变亮。直接把页面内容喂给胶囊是做不出这个效果的。
 */
@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    // 不读 isSystemInDarkTheme：App 强制浅色主题，玻璃单独变深色会和页面割裂。
    // 口径统一在 GlassTokens.DarkGlass，接入深色主题时改那一处即可。
    val isLightTheme = !GlassTokens.DarkGlass
    // 主题色与玻璃通透度统一走 GlassTokens —— 顶栏共用同一套参数，两条栏才是"同一块玻璃"
    val accentColor =
        if (isLightTheme) GlassTokens.AccentLight
        else GlassTokens.AccentDark
    val containerColor =
        if (isLightTheme) GlassTokens.ContainerLight.copy(GlassTokens.PanelSurfaceAlpha)
        else GlassTokens.ContainerDark.copy(GlassTokens.PanelSurfaceAlpha)

    val latestSelectedIndex by rememberUpdatedState(selectedTabIndex)
    val latestOnTabSelected by rememberUpdatedState(onTabSelected)
    val tabsBackdrop = rememberLayerBackdrop()
    // 按 skill 的 exportedBackdrop 导出底板，胶囊采样磨砂结果而非原始页面。
    val panelBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var currentIndex by remember {
        mutableIntStateOf(latestSelectedIndex())
    }

    val offsetAnimation = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        // 槽位宽度 = （面板宽 - 左右各内缩）/ 数量。
        // 内缩量取 GlassTokens.BarCapsulePaddingHorizontalDp —— 胶囊和 tab 用的是同一套内缩，
        // 胶囊中心才对得准槽位中心，同时第 0 / 最后一个 tab 的胶囊不会贴到面板边。
        val barWidth = constraints.maxWidth
        val capsulePaddingPx = with(density) { GlassTokens.BarCapsulePaddingHorizontalDp.dp.toPx() }
        val tabWidth = with(density) {
            (barWidth.toFloat() - capsulePaddingPx * 2f) / tabsCount
        }

        // 指示器胶囊比单个槽位略宽（更饱满），但**中心仍对准槽位** ——
        // 所以左右各多出 overhang，位移时要补偿回去，否则整体会往右偏
        val indicatorWidth = tabWidth * GlassTokens.IndicatorWidthScale
        val indicatorWidthDp = with(density) { indicatorWidth.toDp() }
        val indicatorOverhang = (indicatorWidth - tabWidth) / 2f

        // 拖拽时整条面板轻微位移（±4dp），松手弹回 —— 酷安式的边界回弹感
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / barWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val dampedDragAnimation = remember(animationScope, tabWidth, isLtr, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = latestSelectedIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = GlassTokens.PressedScale,
                onDragStarted = { position ->
                    val physicalIndex = (position.x - capsulePaddingPx) / tabWidth - 0.5f
                    val index = if (isLtr) physicalIndex else tabsCount - 1f - physicalIndex
                    updateValue(index)
                    press(heavy = index.fastRoundToInt() != latestSelectedIndex())
                },
                onDragCancelled = {
                    animateToValue(latestSelectedIndex().toFloat())
                    animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                },
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat(), morph = didDrag)
                    latestOnTabSelected(targetIndex)
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, position ->
                    val physicalIndex = (position.x - capsulePaddingPx) / tabWidth - 0.5f
                    updateValue(if (isLtr) physicalIndex else tabsCount - 1f - physicalIndex)
                }
            )
        }

        // 外部选中索引变化（例如代码里直接切页）时，指示器跟着走
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { latestSelectedIndex() }
                .collectLatest { index ->
                    if (currentIndex != index && !dampedDragAnimation.isDragging) {
                        currentIndex = index
                        dampedDragAnimation.animateToValue(index.toFloat())
                    }
                }
        }

        // 点击标签时的平滑滑行动画。
        // 注意：这里不再回调 onTabSelected —— 点击路径由 LiquidBottomTab 的 onClick 负责，
        // 拖拽路径在 onDragStopped 里单独回调。
        val selectTab: (Int) -> Unit = remember(dampedDragAnimation) {
            { index ->
                if (currentIndex != index) {
                    currentIndex = index
                    dampedDragAnimation.animateToValue(index.toFloat())
                }
            }
        }

        // 按住 / 松开：由 LiquidBottomTab 通过 LocalPressTab 上报。
        // 与 selectTab 分开是必要的 —— selectTab 有"索引没变就什么都不做"的保护，
        // 那样按住**当前已选中**的 tab 就没有任何反馈了。
        //
        // 但反馈分轻重：按住**已经是当前页**的那个 tab 时只给轻微缩放，不拉起
        // 完整的透镜/模糊斜坡 —— 那种点击不切页，而完整斜坡会让整块玻璃每帧重跑
        // blur + lens，停留当前页连点会把整页和底栏一起拖垮（实测见 work/perf/tap_jank.py）。
        // 切到别的页仍然是完整反馈，手感不变。
        val pressTab: (Int, Boolean) -> Unit = remember(dampedDragAnimation) {
            { index, pressed ->
                if (pressed) dampedDragAnimation.press(heavy = index != latestSelectedIndex())
                else dampedDragAnimation.release()
            }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // 手势坐标固定在整条底栏上；按下任何标签就把胶囊移到手指处。
        // 不能挂在会平移、形变的胶囊上，否则其他标签只在松手点击后才移动。
        Box(Modifier.fillMaxWidth().then(dampedDragAnimation.modifier), contentAlignment = Alignment.CenterStart) {
        // ---------- 第 1 层：底层玻璃面板 ----------
        CompositionLocalProvider(
            LocalSelectTab provides selectTab,
            LocalPressTab provides pressTab,
        ) {
            Row(
                Modifier
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        exportedBackdrop = panelBackdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            val press = dampedDragAnimation.pressProgress
                            blur(lerp(GlassTokens.PanelBlurDp, GlassTokens.PanelPressedBlurDp, press).dp.toPx())
                            lens(
                                lerp(GlassTokens.PanelRefractionHeightDp, GlassTokens.PanelPressedRefractionHeightDp, press).dp.toPx(),
                                lerp(GlassTokens.PanelRefractionAmountDp, GlassTokens.PanelPressedRefractionAmountDp, press).dp.toPx(),
                                chromaticAberration = GlassTokens.ChromaticAberration
                            )
                        },
                        // 使用 skill 的低强度常驻轮廓，避免额外描边和厚重阴影。
                        highlight = {
                            Highlight.Default.copy(alpha = GlassTokens.RestHighlightAlpha + GlassTokens.PressHighlightGain * dampedDragAnimation.pressProgress)
                        },
                        shadow = {
                            Shadow(alpha = GlassTokens.RestShadowAlpha + GlassTokens.PressShadowGain * dampedDragAnimation.pressProgress)
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(radius = 4.dp + 8.dp * progress, alpha = 0.10f + 0.30f * progress)
                        },
                        // 按压时面板整体微微放大（16dp 相对自身宽度）
                        layerBlock = {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = {
                            drawRect(containerColor.copy(alpha = lerp(GlassTokens.PanelSurfaceAlpha, GlassTokens.PanelPressedSurfaceAlpha, dampedDragAnimation.pressProgress)))
                        }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(GlassTokens.BarPanelHeightDp.dp)
                    .fillMaxWidth()
                    .padding(
                        horizontal = GlassTokens.BarCapsulePaddingHorizontalDp.dp,
                        vertical = GlassTokens.BarCapsulePaddingVerticalDp.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        // ---------- 第 2 层：不可见的"染色图标"层，供指示器折射 ----------
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            },
            LocalTabInputEnabled provides false,
            LocalSelectTab provides selectTab,
            LocalPressTab provides pressTab,
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .graphicsLayer {
                        val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, dampedDragAnimation.pressProgress)
                        scaleX = scale
                        scaleY = scale
                    }
                    // 仅导出图标，不给整块底色染蓝，也不重复录制按压高光。
                    .height(GlassTokens.BarContentHeightDp.dp)
                    .fillMaxWidth()
                    .padding(horizontal = GlassTokens.BarCapsulePaddingHorizontalDp.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        // ---------- 第 3 层：指示器胶囊 ----------
        Box(
            Modifier
                .padding(horizontal = GlassTokens.BarCapsulePaddingHorizontalDp.dp)
                .graphicsLayer {
                    val panelScale = 1f + 16.dp.toPx() / barWidth * dampedDragAnimation.pressProgress
                    val center = if (isLtr) capsulePaddingPx + (dampedDragAnimation.value + 0.5f) * tabWidth
                        else barWidth - capsulePaddingPx - (dampedDragAnimation.value + 0.5f) * tabWidth
                    val expansionOffset = (center - barWidth / 2f) * (panelScale - 1f)
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth - indicatorOverhang + panelOffset + expansionOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth - indicatorOverhang + panelOffset + expansionOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(panelBackdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        // 色散统一走 GlassTokens.ChromaticAberration（2026-10-03 用户反馈黄蓝泛光后默认关）
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = GlassTokens.ChromaticAberration
                        )
                    },
                    // 胶囊恢复 skill 的按压轮廓，静置时不叠加一圈硬亮边。
                    highlight = { Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress) },
                    shadow = { Shadow(alpha = dampedDragAnimation.pressProgress) },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 8.dp * progress, alpha = progress)
                    },                    // 松手瞬间按速度做非均匀拉伸：X 拉扁、Y 略压，像被甩出去的液体
                    layerBlock = {
                        // 形变只在"真的按下去"的时候才跟着走（pressProgress > 0）。
                        // 轻按压（停在当前页连点）pressProgress 恒为 0，但 1.0↔1.04 的轻微弹簧
                        // 每帧都会改这两个值 —— 每改一帧，这层 drawBackdrop 就把 blur + lens
                        // 重跑一遍，连点等于持续重绘（实测见 work/perf/tap_jank.py）。
                        // 拖动胶囊 / 切页时 pressProgress → 1，形变与甩出去的拉伸完全不变。
                        val morph = dampedDragAnimation.pressProgress
                        scaleX = lerp(1f, dampedDragAnimation.scaleX, morph)
                        scaleY = lerp(1f, dampedDragAnimation.scaleY, morph)
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f * morph).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f * morph).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        // 静止时的灰底是胶囊「存在感的全部来源」（lens/shadow 静止时都是 0），
                        // 0.03 太浅看不见，统一走 GlassTokens.IndicatorRestAlpha
                        drawRect(
                            if (isLightTheme) Color.Black.copy(GlassTokens.IndicatorRestAlpha)
                            else Color.White.copy(GlassTokens.IndicatorRestAlpha),
                            alpha = 1f - progress * 0.7f
                        )
                    }
                )
                .height(GlassTokens.BarContentHeightDp.dp)
                .width(indicatorWidthDp)
        )
        }
    }
}





