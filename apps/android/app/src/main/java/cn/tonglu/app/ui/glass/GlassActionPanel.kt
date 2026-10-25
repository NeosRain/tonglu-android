package cn.tonglu.app.ui.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * 一块玻璃的**可调参数**。
 *
 * 独立成 data class 而不是往 [GlassActionPanel] 上堆五个形参：
 * 位置参数一多就容易传错位，且这里要能整体替换（「默认档 / 实体档」），
 * 逐项传会让调用点把样式细节散在各处。
 *
 * 🔴 默认值**全部**取自 [GlassTokens]，所以 [GlassPanelStyle.Default] 的行为
 * 与引入本类之前**逐项一致** —— 已存在的调用点（顶栏、底栏、搜索框、阅读器、
 * 笔记编辑器、发帖器）不受影响。想改全局观感请改 [GlassTokens]，
 * 想只改某一处请传这里的另一个档位。
 */
internal data class GlassPanelStyle(
    val surfaceAlpha: Float = GlassTokens.ContainerAlpha,
    val blurDp: Float = GlassTokens.BlurDp,
    val highlightAlpha: Float = GlassTokens.RestHighlightAlpha,
    val shadowAlpha: Float = GlassTokens.RestShadowAlpha,
    val innerShadowAlpha: Float = GlassTokens.RestInnerShadowAlpha,
) {
    companion object {

        /** 默认档：与引入本类之前完全一致。 */
        val Default = GlassPanelStyle()

        /**
         * 实体档：**详情页底部两块条**专用（2026-10-10 用户选 C 档）。
         *
         * 为什么需要它：默认档在浅色背景上「条 − 背后」的亮度差只有 **−1.7**
         * （酷安那条是 **+15.9**），玻璃几乎融进背景，用户反馈「没有酷安的好」。
         * 这档对症下药 —— 底色压实到 0.88、模糊 6→16dp（背后内容明显被抹糊）、
         * 高光 0.15→0.28、阴影 0.08→0.20（勾出轮廓、让它浮起来）。
         *
         * 副作用要认：alpha 0.88 意味着**几乎不透**，玻璃的「通透感」会让位于
         * 「看得见的一块」。这是刻意的选择 —— 录屏里酷安那条就是实心的。
         */
        val Solid = GlassPanelStyle(
            surfaceAlpha = 0.88f,
            blurDp = 16f,
            highlightAlpha = 0.28f,
            shadowAlpha = 0.20f,
            innerShadowAlpha = 0.14f,
        )
    }
}

/**
 * Shared glass chrome, sampling the scrolling page rather than itself.
 *
 * `shape` 默认是浮起来的圆角胶囊（24dp）。贴着屏幕底边的「停靠条」要传
 * [RoundedCornerShape] 只圆上两个角 —— 圆角贴底会因为系统手势条留白露出来，
 * 看着像一条悬空的卡片而不是底栏。
 *
 * ⚠️ `backdrop` 必须来自**兄弟节点**的 `rememberLayerBackdrop()`：
 * 被采样的内容要单独包一层 `Modifier.layerBackdrop(backdrop)`，
 * 不能把它和本组件包在同一个父节点里 —— 那是循环绘制，会 SIGSEGV。
 */
@Composable
internal fun GlassActionPanel(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /**
     * 这一块玻璃用哪档参数。默认 [GlassPanelStyle.Default]，即沿用 [GlassTokens] 全局值；
     * 详情页底部两块条传 [GlassPanelStyle.Solid]。
     */
    style: GlassPanelStyle = GlassPanelStyle.Default,
    // 🔴 默认值必须**跟着主题走**，不能写死浅色。
    //
    // 2026-10-10 深色走查实测：这个参数原先是 `GlassTokens.ContainerLight` 常量默认值，
    // 于是所有**没显式传参**的调用点（详情页底部两块条、评论输入面板……）在深色下
    // 仍是浅灰白底 —— 一格刺眼的白条压在深色画布上（截图 `walk-detail-dark.png`）。
    // 顶栏/底栏（走 LiquidBottomTabs）当年接好了 `DarkGlass`，这里漏了。
    //
    // 用 `@Composable` 默认参数读 `DarkGlass` 是合法的（Compose 支持 composable 默认参数），
    // 这样 8 个调用点里显式传色的（ArticleReader 的 `background` 等）行为完全不变。
    surfaceColor: Color = if (GlassTokens.DarkGlass) GlassTokens.ContainerDark else GlassTokens.ContainerLight,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { vibrancy(); blur(style.blurDp.dp.toPx()); lens(8.dp.toPx(), 12.dp.toPx()) },
        highlight = { Highlight.Default.copy(alpha = style.highlightAlpha) },
        shadow = { Shadow(alpha = style.shadowAlpha) },
        innerShadow = { InnerShadow(radius = 8.dp, alpha = style.innerShadowAlpha) },
        onDrawSurface = { drawRect(surfaceColor.copy(alpha = style.surfaceAlpha)) },
    ), content = content)
}
