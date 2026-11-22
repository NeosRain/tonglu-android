package cn.tonglu.app.ui.glass

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkGlassBarsTest {
    @Test
    fun scrollFadeClampsToTheScrollRange() {
        assertEquals(0f, topBarFadeFraction(-5f, 120f, reduceMotion = false), 0f)
        assertEquals(0f, topBarFadeFraction(0f, 120f, reduceMotion = false), 0f)
        assertEquals(.5f, topBarFadeFraction(60f, 120f, reduceMotion = false), 0f)
        assertEquals(1f, topBarFadeFraction(120f, 120f, reduceMotion = false), 0f)
        assertEquals(1f, topBarFadeFraction(200f, 120f, reduceMotion = false), 0f)
    }

    @Test
    fun reducedMotionUsesOnlyTransparentAndScrolledStates() {
        assertEquals(0f, topBarFadeFraction(0f, 120f, reduceMotion = true), 0f)
        assertEquals(1f, topBarFadeFraction(1f, 120f, reduceMotion = true), 0f)
    }

    @Test
    fun nightNavigationSelectsTheMaterialForGlassAvailability() {
        val glass = GlassTokens.darkNavigationSurface(glass = true)
        val fallback = GlassTokens.darkNavigationSurface(glass = false)
        assertEquals(Color(0xFF0E0F12), glass.copy(alpha = 1f))
        assertEquals(.50f, glass.alpha, .004f)
        assertEquals(Color(0xFF16171A), fallback.copy(alpha = 1f))
        assertEquals(.92f, fallback.alpha, .004f)
    }

    @Test
    fun disabledSettingSkipsTheShaderProbe() {
        assertFalse(canUseNavigationGlass(enabled = false, sdk = 33) { error("unexpected shader probe") })
    }

    @Test
    fun olderAndroidSkipsTheShaderProbe() {
        assertFalse(canUseNavigationGlass(enabled = true, sdk = 32) { error("unexpected shader probe") })
    }

    @Test
    fun supportedAndroidStillRequiresRuntimeShaders() {
        assertFalse(canUseNavigationGlass(enabled = true, sdk = 33) { false })
        assertTrue(canUseNavigationGlass(enabled = true, sdk = 33) { true })
    }
}
