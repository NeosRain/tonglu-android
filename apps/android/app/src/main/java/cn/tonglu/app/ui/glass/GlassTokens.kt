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
import cn.tonglu.app.ui.AppTheme

/**
 * 液态玻璃的**统一视觉参数**。
 *
 * 顶栏（[GlassTopBar]）和底栏（[LiquidBottomTabs]）都从这里取值 ——
 * 想调玻璃的厚薄、通透度或深浅，改这一个文件，两条栏一起变，不会出现"顶栏比底栏更透"的割裂感。
 */
internal object GlassTokens {

    /**
     * 玻璃组件按深色渲染吗？**目前恒为 false**。
     *
     * 原因：MainActivity 里写死了 `colorScheme = lightColorScheme(...)`，
     * 也就是 App 整体**不跟随系统深色** —— 页面背景、文字、酷安风底栏在夜间都还是浅色。
     * 玻璃若单独去读 `isSystemInDarkTheme()`，就会出现「底栏是黑的、页面是白的」的割裂
     * （2026-09-30 实测出来的），所以这里统一按浅色走。
     *
     * 等 App 真正接入深色主题时，只需把这里改成读 App 自己的深色开关，
     * 顶栏和底栏会一起跟着变，不会出现只有一条栏变色的情况。
     */
    // 2026-10-08：按其注释接好 —— 读 App 自己的开关（**不是**系统夜间，别改回去）。
    val DarkGlass: Boolean get() = AppTheme.darkState

    /** 玻璃底色（浅色主题）：比纯白略灰一点，静置时轮廓更清楚 */
    val ContainerLight = Color(0xFFF1F2F5)

    /** 玻璃底色（深色主题） */
    val ContainerDark = Color(0xFF15171C)

    /**
     * 「实体档」玻璃底色（浅色主题）：**纯白**。
     *
     * 2026-10-10 实测：默认档 [ContainerLight]（241,242,245）按 [ContainerAlpha]=0.68
     * 叠在亮度约 245 的页面背景上，结果和背景只差 **1.7** —— 玻璃几乎没有辨识度，
     * 看着像「什么都没有」，而不是「一块玻璃」（酷安那条底边比背后亮 **+15.9**）。
     * 纯白才能把这块条从背景里拉出来。
     */
    val PanelSolidLight = Color(0xFFFFFFFF)

    /**
     * 「实体档」玻璃底色（深色主题）：比 [ContainerDark] 亮一档。
     *
     * 深色下如果沿用 ContainerDark（21,23,28）压 0.88，玻璃会比背景还暗，
     * 又变成「看不见的一条」—— 这里抬高到 (36,40,48)，深色下也能看出是一块。
     */
    val PanelSolidDark = Color(0xFF242830)

    /**
     * 静置时玻璃的不透明度。
     *
     * 这一项直接决定"看得清不清楚"：调高 → 更实、字更清楚，但折射感变弱；
     * 调低 → 更通透、更像玻璃，但内容会和后面的页面糊在一起。
     * 0.4 时太飘，0.72 是"能看清 + 仍有玻璃味"的平衡点。
     */
    const val ContainerAlpha = 0.68f

    /** 主题色（浅色主题）：对齐 App 的 Accent。2026-10-11 随浅色 Accent 一起加深到 #2563EB。 */
    val AccentLight = Color(0xFF2563EB)

    /**
     * 主题色（深色主题）：对齐 `AppColors` 的深色 Accent（ThemeColorTest 断言两处相等，防止悄悄分叉）。
     * 2026-10-10 随深色 Accent 一起从 #6E9BF0 加深到 #4173CC —— 玻璃上的图标/文字 tint
     * 对深色玻璃底仍有 ≥3.0:1（非文字 UI 元件阈值），换来的不是「两种蓝」。
     */
    val AccentDark = Color(0xFF4173CC)

    /**
     * 玻璃效果参数全部对齐 skill 的 **v2 统一规范**（.workbuddy/refs/skill-liquid-glass/
     * guides/best-practices.md 第 1 节，明确写了适用于 LiquidBottomTabs）。
     * 老参数是 blur 8dp / lens 24,24 无色散，糊成一团、看不出折射 —— 所以"不够玻璃"。
     *
     * 规范值：blur 2dp（低模糊保留内容细节）、lens 12/24 + 色散、常驻高光 0.15、
     * 常驻阴影 0.08、常驻内阴影 4dp/0.1，按压时各自线性增强。
     */
    const val BlurDp = 6f

    /** 透镜折射 X 半径（dp）：边缘把背景"掰弯"的距离 —— 玻璃感的主要来源 */
    const val LensXDp = 12f

    /** 透镜折射 Y 半径（dp） */
    const val LensYDp = 24f

    /**
     * 色散（chromaticAberration）：折射时边缘把 RGB 拆开，出现黄/蓝彩边。
     * 曾经开它是「液态感」的来源，但页面左右滑动时彩边跟着背景 smear，
     * 用户反馈「出现黄色和蓝色的泛光」→ 2026-10-03 关掉。
     * 折射（把背景掰弯）还在，只是不再分色；要找回效果改回 true 即可。
     */
    const val ChromaticAberration = false

    // 底板只折射窄边缘；拖动胶囊保留独立的强透镜，避免整栏背景被拉进图标区。
    const val PanelRefractionHeightDp = 12f
    const val PanelRefractionAmountDp = 24f
    const val PanelBlurDp = 8f
    const val PanelSurfaceAlpha = 0.76f
    /** 按住时白色磨砂逐渐透光；松手回到静置强度。 */
    const val PanelPressedSurfaceAlpha = 0.38f
    const val PanelPressedBlurDp = 3f
    const val PanelPressedRefractionHeightDp = 18f
    const val PanelPressedRefractionAmountDp = 28f

    /** 常驻高光：静止时玻璃边缘的轮廓光（有它才像一块玻璃，不是一块灰板） */
    const val RestHighlightAlpha = 0.15f

    /** 按压时高光在常驻基础上再加多少（规范：0.15 + 0.35 * progress） */
    const val PressHighlightGain = 0.35f

    /** 常驻阴影：让玻璃"浮"在页面之上 */
    const val RestShadowAlpha = 0.08f

    /** 按压时阴影在常驻基础上再加多少 */
    const val PressShadowGain = 0.22f

    /** 常驻内阴影半径 / 强度：玻璃内侧的一圈暗边 */
    const val RestInnerShadowRadiusDp = 4f
    const val RestInnerShadowAlpha = 0.10f

    /** 按压时内阴影的增量（半径 8dp、强度 0.3） */
    const val PressInnerShadowRadiusGainDp = 8f
    const val PressInnerShadowAlphaGain = 0.30f

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
    const val SearchBlurDp = 8f
    /** 透镜折射半径（dp）：边缘把背景掰弯的距离，玻璃感的主要来源 */
    const val SearchLensRadiusDp = 10f
    /** 透镜折射位移量（dp） */
    const val SearchLensAmountDp = 14f
    /** 搜索框底色不透明度：0.55 = 看得清文字，又仍透出一点背景 */
    const val SearchSurfaceAlpha = 0.55f
    /** 常驻高光：够亮才像一块玻璃，不是一块灰板 */
    const val SearchHighlightAlpha = 0.22f
    const val SearchShadowAlpha = 0.10f
    const val SearchInnerShadowRadiusDp = 4f
    const val SearchInnerShadowAlpha = 0.12f
    /** 搜索框圆角：10dp 是「方框」与「胶囊」的分水岭，保持不变 */
    const val SearchCornerDp = 10f
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




