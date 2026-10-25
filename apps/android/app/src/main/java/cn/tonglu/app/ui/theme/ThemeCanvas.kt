package cn.tonglu.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 壁纸是否启用。**故意做成极小的纯状态对象**：`AppColors.Canvas` 每个重组都会读它，
 * 这里不能持有 Bitmap / Context / 协程，否则单测加载 AppColors 就会碰到 Android 主线程调度器。
 */
internal object ThemeCanvas {

    /**
     * 有壁纸时页面容器留出的透明度。
     *
     * 0.58 是实机走查值：首页/学习/广场/我的没有额外底色，壁纸直接透出来；
     * 社团页还会再叠一层 Canvas，58% 仍能看见壁纸纹理，同时正文压在模糊图上可读。
     */
    private const val WALLPAPER_ALPHA = 0.72f

    /**
     * 「卡片透光」用户档位（0..1，由 [ThemeWallpaper] 持久化并回灌）。
     *
     * 🔴 透光端点按 WCAG 相对亮度钉死（用户 2026-10-19 反馈「文字反色搞坏了」的教训）：
     * 旧公式最透端 alpha=0.35，深色卡压在壁纸亮斑上会退化成灰底白字，可读性崩。
     * 现在浅深分开限制遮光率下限：
     * - 浅色卡（白底深字）：最透 alpha=0.62。最坏情况壁纸显示亮度≈0.81（伽马域，
     *   veil 上限 0.9 混默认 dim），混合后深字对比 ≈11:1，亮斑局部也不破 4.5。
     * - 深色卡（深底白字）：最透 alpha=0.70。深色语义要求卡片保持「暗」，遮光率
     *   下限更高；最坏壁纸亮斑（veil 压后 ≤0.24）混入后白字对比 ≥7:1。
     * 公式：浅 alpha = 0.90 - 0.28×透光，深 alpha = 0.92 - 0.22×透光。
     * 默认 0.55 → 浅 0.75 / 深 0.80：玻璃感明显，且和旧版观感连续。
     */
    var cardGlass by mutableFloatStateOf(0.55f)
        private set

    fun applyCardGlass(value: Float) {
        cardGlass = value.coerceIn(0f, 1f)
    }

    var wallpaperActive by mutableStateOf(false)
        private set

    fun setActive(active: Boolean) {
        wallpaperActive = active
    }

    fun resolve(base: Color): Color =
        if (wallpaperActive) base.copy(alpha = WALLPAPER_ALPHA) else base

    /**
     * 卡片/面板底色。没壁纸时**原样返回**（默认路径逐字节不变，审计与单测仍按不透明算）；
     * 有壁纸时按透光档位只降 alpha、色相不动，浅深各有遮光率下限（见 [cardGlass] 注）。
     */
    fun resolveCard(base: Color, dark: Boolean): Color {
        if (!wallpaperActive) return base
        val alpha = if (dark) 0.92f - 0.22f * cardGlass else 0.90f - 0.28f * cardGlass
        return base.copy(alpha = alpha)
    }
}
