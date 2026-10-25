package cn.tonglu.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorTest {
    @Test fun lightValuesRemainBitExact() {
        val legacy = listOf(0xFF6B7280, 0xFF9CA3AF, 0xFF4B5563, 0xFF374151,
            0xFFD9480F, 0xFF12833B, 0xFFB26A00, 0xFFF0F1F4, 0xFFEDEFF3,
            0xFFFFFFFF, 0xFFFFF3E0, 0xFFE8F0FE)
        legacy.forEach { value ->
            val original = Color(value)
            assertEquals(original, themeColor(original, Color.Black, isDark = false))
            assertEquals(original, noticeBackground(original, isDark = false))
            assertEquals(original, noticeForeground(original, isDark = false))
        }
    }

    @Test fun darkUsesSemanticDestination() {
        assertEquals(Color(0xFF272B33),
            themeColor(Color.White, Color(0xFF272B33), isDark = true))
        assertEquals(Color.White, AppColors.OnAccent)
    }

    @Test fun noticePairsHaveReadableDarkContrast() {
        listOf(0xFFFFF3E0 to 0xFFB26A00, 0xFFE8F0FE to 0xFF254E78,
            0xFFFFEBEE to 0xFFB91C1C, 0xFFE8F5E9 to 0xFF12833B).forEach { (bg, fg) ->
            val background = noticeBackground(Color(bg), isDark = true)
            val foreground = noticeForeground(Color(fg), isDark = true)
            val contrast = (foreground.luminance() + .05f) / (background.luminance() + .05f)
            assertTrue("notice contrast=$contrast", contrast >= 4.5f)
        }
    }

    // ============ 深色语义色槽的 WCAG 钉子（BUGS A31：深色 OnAccent/Accent 曾只有 2.76:1） ============
    // 判据与 work/sweep67/theme_contrast.py 一致：正文/压在强调色上的字 ≥4.5，
    // 强调色当文字或非文字元件 ≥3.0。断言引用 DarkPalette 真实值 —— 改值不过这关就红。

    private fun contrastOf(fg: Color, bg: Color): Float =
        (maxOf(fg.luminance(), bg.luminance()) + .05f) /
            (minOf(fg.luminance(), bg.luminance()) + .05f)

    @Test fun darkOnAccentSitsReadableOnAccent() {
        val c = contrastOf(AppColors.OnAccent, DarkPalette.Accent)
        assertTrue("深色 OnAccent/Accent=$c（白字压在强调色上，需 ≥4.5；" +
            "改浅 Accent 前先看 DarkPalette.Accent 注释里的双向约束）", c >= 4.5f)
    }

    @Test fun darkAccentStaysVisibleOnDarkSurfaces() {
        listOf(
            "Canvas" to DarkPalette.Canvas,
            "Surface" to DarkPalette.Surface,
            "ChipIdle" to DarkPalette.ChipIdle,
        ).forEach { (name, bg) ->
            val c = contrastOf(DarkPalette.Accent, bg)
            assertTrue("深色 Accent/$name=$c（强调色当文字/图标，需 ≥3.0）", c >= 3f)
        }
    }

    @Test fun darkBodyTextStaysReadable() {
        assertTrue(contrastOf(DarkPalette.TextPrimary, DarkPalette.Canvas) >= 4.5f)
        assertTrue(contrastOf(DarkPalette.TextPrimary, DarkPalette.Surface) >= 4.5f)
        assertTrue(contrastOf(DarkPalette.TextSecondary, DarkPalette.Canvas) >= 4.5f)
        assertTrue(contrastOf(DarkPalette.TextSecondary, DarkPalette.Surface) >= 4.5f)
    }

    @Test fun glassAccentDarkFollowsAppAccent() {
        // 防止玻璃层与页面层各自维护一份深色强调色后悄悄分叉（出现「两种蓝」）。
        assertEquals(DarkPalette.Accent, cn.tonglu.app.ui.glass.GlassTokens.AccentDark)
    }

    // ============ 浅色侧的同一对钉子（2026-10-11 补） ============
    // A31 只钉了**深色**，于是浅色 Accent 长期停在 4.18:1 没人拦 ——
    // 「钉了一侧就以为两侧都钉住了」是这次的教训。下面两条对称补上。
    // `AppTheme.darkState` 默认 false（单测里没人改过它），所以 AppColors.Accent
    // 在这两条里取的就是**浅色**值。

    @Test fun lightOnAccentSitsReadableOnAccent() {
        val c = contrastOf(AppColors.OnAccent, AppColors.Accent)
        assertTrue("浅色 OnAccent/Accent=$c（白字压在强调色上，需 ≥4.5；" +
            "改浅色品牌蓝前先看 AppColors.Accent 的 KDoc）", c >= 4.5f)
    }

    @Test fun lightAccentStaysVisibleOnLightSurfaces() {
        val c = contrastOf(AppColors.Accent, AppColors.Canvas)
        assertTrue("浅色 Accent/Canvas=$c（强调色当文字/图标，需 ≥3.0）", c >= 3f)
    }

    @Test fun glassAccentLightFollowsAppAccent() {
        // 与上面那条对称：浅色下玻璃层也不能自己留一份旧蓝。
        assertEquals(AppColors.Accent, cn.tonglu.app.ui.glass.GlassTokens.AccentLight)
    }
}
