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

/** Shared frosted material; density varies while palette, blur and surface light stay consistent. */
internal data class GlassPanelStyle(
    val surfaceAlpha: Float = GlassTokens.ContainerAlpha,
    val blurDp: Float = GlassTokens.BlurDp,
    val highlightAlpha: Float = GlassTokens.RestHighlightAlpha,
    val shadowAlpha: Float = GlassTokens.RestShadowAlpha,
    val innerShadowAlpha: Float = GlassTokens.RestInnerShadowAlpha,
    val refractionHeightDp: Float = GlassTokens.PanelRefractionHeightDp,
    val refractionAmountDp: Float = GlassTokens.PanelRefractionAmountDp,
) {
    companion object {

        // Getters read current theme values instead of freezing the first composition.
        val Default get() = GlassPanelStyle()

        val Bottom get() = GlassPanelStyle(
            surfaceAlpha = GlassBottomTokens.SurfaceAlpha,
            blurDp = GlassBottomTokens.BlurDp,
            highlightAlpha = GlassBottomTokens.HighlightAlpha,
            shadowAlpha = GlassBottomTokens.ShadowAlpha,
            innerShadowAlpha = GlassBottomTokens.InnerShadowAlpha,
            refractionHeightDp = GlassBottomTokens.RefractionHeightDp,
            refractionAmountDp = GlassBottomTokens.RefractionAmountDp,
        )

        val BottomInput get() = Bottom.copy(surfaceAlpha = if (GlassTokens.DarkGlass) .96f else .94f)

        /** Denser version for floating detail actions. */
        val Solid get() = GlassPanelStyle(
            surfaceAlpha = if (GlassTokens.DarkGlass) .92f else .86f,
            blurDp = 32f,
            highlightAlpha = GlassTokens.RestHighlightAlpha,
            shadowAlpha = GlassTokens.RestShadowAlpha + .02f,
            innerShadowAlpha = GlassTokens.RestInnerShadowAlpha,
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
        effects = { vibrancy(); blur(style.blurDp.dp.toPx())
            lens(style.refractionHeightDp.dp.toPx(), style.refractionAmountDp.dp.toPx(),
                chromaticAberration = GlassTokens.ChromaticAberration) },
        highlight = { Highlight.Default.copy(alpha = style.highlightAlpha) },
        shadow = { Shadow(alpha = style.shadowAlpha) },
        innerShadow = { InnerShadow(radius = 8.dp, alpha = style.innerShadowAlpha) },
        onDrawSurface = { drawFrostSurface(surfaceColor, style.surfaceAlpha) },
    ), content = content)
}
