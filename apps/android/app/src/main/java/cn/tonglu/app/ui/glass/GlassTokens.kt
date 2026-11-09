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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import cn.tonglu.app.ui.AppTheme
import cn.tonglu.app.ui.AppColors

/**
 * 液态玻璃的**统一视觉参数**。
 *
 * 顶栏（[GlassTopBar]）和底栏（[LiquidBottomTabs]）都从这里取值 ——
 * 想调玻璃的厚薄、通透度或深浅，改这一个文件，两条栏一起变，不会出现"顶栏比底栏更透"的割裂感。
 */
internal object GlassTokens {

    /** 主题跟随应用的主题选择，顶栏、底栏与输入面板同步。 */
    val DarkGlass: Boolean get() = AppTheme.darkState

    /** 玻璃底色（浅色主题）：乳白底色，配合柔和阴影与高光形成可见面板 */
    val ContainerLight = Color(0xFFFFFFFF)

    /** 玻璃底色（深色主题） */
    val ContainerDark = Color(0xFF34373E)

    /** A lifted smoke surface needs a lighter accent than the page canvas. */
    val AccentOnGlass: Color get() = if (DarkGlass) lerp(AppColors.Accent, Color.White, .65f) else AppColors.Accent

    /** 更凝实面板沿用同一乳白底色，以不透明度和光影区分密度。 */
    val PanelSolidLight = Color(0xFFFFFFFF)

    /** 更凝实面板沿用同一烟灰底色。 */
    val PanelSolidDark = ContainerDark

    /** 深色更凝实，避免白色图片穿透后降低控件可读性。 */
    val ContainerAlpha: Float get() = if (DarkGlass) .88f else .78f

    /** 主题色（浅色主题）：对齐 App 的 Accent。2026-10-11 随浅色 Accent 一起加深到 #2563EB。 */
    val AccentLight = Color(0xFF2563EB)

    /**
     * 主题色（深色主题）：对齐 `AppColors` 的深色 Accent（ThemeColorTest 断言两处相等，防止悄悄分叉）。
     * 2026-10-10 随深色 Accent 一起从 #6E9BF0 加深到 #4173CC —— 玻璃上的图标/文字 tint
     * 对深色玻璃底仍有 ≥3.0:1（非文字 UI 元件阈值），换来的不是「两种蓝」。
     */
    val AccentDark = Color(0xFF4173CC)

    /** 以宽范围模糊和柔光形成磨砂，边缘保留轻折射。 */
    const val BlurDp = 28f

    /** 透镜折射 X 半径（dp）：边缘把背景"掰弯"的距离 —— 玻璃感的主要来源 */
    const val LensXDp = 8f

    /** 透镜折射 Y 半径（dp） */
    const val LensYDp = 12f

    /**
     * 色散（chromaticAberration）：折射时边缘把 RGB 拆开，出现黄/蓝彩边。
     * 曾经开它是「液态感」的来源，但页面左右滑动时彩边跟着背景 smear，
     * 用户反馈「出现黄色和蓝色的泛光」→ 2026-10-03 关掉。
     * 折射（把背景掰弯）还在，只是不再分色；要找回效果改回 true 即可。
     */
    const val ChromaticAberration = false

    // 底板只折射窄边缘；拖动胶囊保留独立的强透镜，避免整栏背景被拉进图标区。
    const val PanelRefractionHeightDp = 6f
    const val PanelRefractionAmountDp = 10f
    const val PanelBlurDp = 28f
    val PanelSurfaceAlpha: Float get() = if (DarkGlass) .88f else .80f
    /** 按住时材质稍加凝实，与输入面板的反馈一致。 */
    val PanelPressedSurfaceAlpha: Float get() = if (DarkGlass) .92f else .88f
    const val PanelPressedBlurDp = 32f
    const val PanelPressedRefractionHeightDp = 8f
    const val PanelPressedRefractionAmountDp = 14f

    /** 常驻高光：静止时玻璃边缘的轮廓光（有它才像一块玻璃，不是一块灰板） */
    val RestHighlightAlpha: Float get() = if (DarkGlass) .24f else .42f

    /** 按压时增强边缘光。 */
    const val PressHighlightGain = 0.10f

    /** 常驻阴影：让玻璃"浮"在页面之上 */
    val RestShadowAlpha: Float get() = if (DarkGlass) .28f else .15f

    /** 按压时阴影在常驻基础上再加多少 */
    const val PressShadowGain = 0.08f

    /** 常驻内阴影半径 / 强度：玻璃内侧的一圈暗边 */
    const val RestInnerShadowRadiusDp = 4f
    const val RestInnerShadowAlpha = 0.06f

    /** 按压时内侧柔影的增量。 */
    const val PressInnerShadowRadiusGainDp = 8f
    const val PressInnerShadowAlphaGain = 0.08f

    // ---------------- 顶栏尺寸 ----------------

    /**
     * 顶栏玻璃条**本体**高度（dp）。
     * 搜索和快捷按钮所在行的高度；状态栏安全区另算在玻璃内部。
     */
    const val TopBarHeightDp = 52f

    /** 安全区下方到控件行的内部间距（dp），同样绘制玻璃 */
    const val TopBarVerticalMarginDp = 8f

    /**
     * 顶栏控件区占位总高（52 + 8 = 60dp），不含状态栏安全区。
     * 页面加上安全区后用作 `contentPadding.top`，内容才能从玻璃下方滚过。
     * 改尺寸时这一组常量一起变，不要再往别处写死数字。
     */
    const val TopBarInsetDp = TopBarHeightDp + TopBarVerticalMarginDp

    // ---------------- 顶栏搜索框（局部玻璃）----------------

    /**
     * 搜索框自己是**一块独立的磨砂玻璃**，嵌在顶栏玻璃之上。
     *
     * 之前的做法只在顶栏玻璃上铺了一层 `alpha=0.12` 的浅色，等于近乎全透 ——
     * 背景一花，框里的字和图标就糊在一起（用户反馈「可视度特别低、磨砂度不够」）。
     * 现在按 skill 的 [LiquidSearchBar] 给搜索框独立做 blur + 折射 + 光影，
     * 底色也调实到能看清字，但比顶栏整体更收敛，避免跳出成第二块顶栏。
     */
    const val SearchBlurDp = 28f
    /** 透镜折射半径（dp）：边缘把背景掰弯的距离，玻璃感的主要来源 */
    const val SearchLensRadiusDp = 6f
    /** 透镜折射位移量（dp） */
    const val SearchLensAmountDp = 8f
    /** 搜索框比外层稍凝实，形成独立的圆润磨砂面。 */
    val SearchSurfaceAlpha: Float get() = if (DarkGlass) .90f else .86f
    /** 常驻高光：够亮才像一块玻璃，不是一块灰板 */
    val SearchHighlightAlpha: Float get() = if (DarkGlass) .22f else .40f
    val SearchShadowAlpha: Float get() = if (DarkGlass) .24f else .12f
    const val SearchInnerShadowRadiusDp = 4f
    const val SearchInnerShadowAlpha = 0.04f
    /** 36dp高搜索框采用完整圆角。 */
    const val SearchCornerDp = 18f
    /** 清除按钮直径与叉号尺寸：原来 16dp/11dp 太浅，几乎看不见 */
    const val SearchClearSizeDp = 18f
    const val SearchClearIconDp = 12f

    // ---------------- 底栏尺寸 ----------------

    /** skill 的 64/56dp 比例缩至 56/48dp，上下各留 4dp。 */
    const val BarPanelHeightDp = 56f
    const val BarContentHeightDp = 48f

    /** 静置胶囊使用轻灰底；按压时逐渐透明，让折射图标显现。 */
    const val IndicatorRestAlpha = 0.08f
    /** 顶栏左右外边距（dp） */
    const val BarHorizontalMarginDp = 22f

    /**
     * 底栏**左右**外边距（dp）：22dp。
     * 曾经误缩到 34dp 试图满足「宽度缩小点」，结果只是把条变短、比例上更显胖 —— 改回来了。
     * 要动行的观感请调 [BarPanelHeightDp]（厚度），不要动这里。
     */
    const val BarSideMarginDp = 22f

    /**
     * 底栏**下**外边距（dp）：决定这条玻璃离屏幕底边多远 —— 「提高点」就是调它。
     * 10 → 14 → 18，一路往上抬，和左上角/右上角的留白更搭。
     * 上滑隐藏的位移是按这个常量现算的（见 TongluApp 底栏的 graphicsLayer），改它不用另动别处。
     */
    const val BarVerticalMarginDp = 28f

    /** 采用单槽宽胶囊，左右留 6dp 空隙，上下留 4dp。 */
    const val BarCapsulePaddingHorizontalDp = 6f

    /** 内容层上下方向的自然内缩（dp）=（面板高 - 内容高）/ 2 */
    const val BarCapsulePaddingVerticalDp = (BarPanelHeightDp - BarContentHeightDp) / 2f

    /**
     * 指示器胶囊相对单个槽位的宽度倍数。
     * 1.0 = 正好一格；调大 → 胶囊更宽更饱满（相邻图标会被盖住一点，正常）。
     */
    const val IndicatorWidthScale = 1.0f

    /** 按住时胶囊放大到多少倍（官方取 78/56 ≈ 1.39） */
    const val PressedScale = 78f / 56f
}

/** Shared frost tint and broad, soft surface light; the backdrop still provides the real blur. */
internal fun DrawScope.drawFrostSurface(color: Color, alpha: Float) {
    drawRect(color.copy(alpha = alpha))
    val dark = GlassTokens.DarkGlass
    drawRect(Brush.verticalGradient(listOf(
        Color.White.copy(alpha = if (dark) .06f else .18f),
        Color.Transparent,
        Color.Black.copy(alpha = if (dark) .035f else .018f),
    )))
}




