package cn.tonglu.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class AppMaterialColorsTest {
    @Test fun dialogsMenusAndFixedColorsHaveNoMaterialPaletteFallback() {
        for (dark in listOf(false, true)) {
            val surface = if (dark) DarkPalette.Surface else Color.White
            val text = if (dark) DarkPalette.TextPrimary else Color(0xFF17181C)
            val canvas = if (dark) DarkPalette.Canvas else Color(0xFFF5F6F8)
            val accent = Color(0xFF17634F)
            val scheme = appMaterialColors(dark, canvas, surface, surface, accent, Color.White,
                text, if (dark) DarkPalette.TextSecondary else Color(0xFF3E4247), Color.Gray, Color.Red)
            assertEquals(surface, scheme.surfaceContainerHigh)
            assertEquals(surface, scheme.surfaceContainer)
            assertEquals(surface, scheme.surfaceContainerHighest)
            assertEquals(canvas, scheme.background)
            assertEquals(Color.Transparent, scheme.surfaceTint)
            assertEquals(text, scheme.onSurface)
            assertEquals(accent, scheme.secondary)
            assertEquals(accent, scheme.tertiary)
            assertEquals(accent, scheme.primaryFixed)
            assertEquals(accent, scheme.tertiaryFixed)
            val contrast = (maxOf(text.luminance(),surface.luminance())+.05f) /
                (minOf(text.luminance(),surface.luminance())+.05f)
            assertTrue(contrast >= 4.5f)
        }
    }
}
