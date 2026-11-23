package cn.tonglu.app.ui.glass

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkTabMotionTest {
    @Test
    fun nightLensUsesSofterHighlightsAndInnerShadows() {
        val light = GlassTokens.navigationLensMaterial(dark = false)
        val dark = GlassTokens.navigationLensMaterial(dark = true)

        assertEquals(NavigationLensMaterial(1f, 8f, 1f), light)
        assertTrue(dark.highlightAlpha > 0f && dark.highlightAlpha < light.highlightAlpha)
        assertTrue(dark.innerShadowRadiusDp > 0f && dark.innerShadowRadiusDp < light.innerShadowRadiusDp)
        assertTrue(dark.innerShadowAlpha > 0f && dark.innerShadowAlpha < light.innerShadowAlpha)
    }

    @Test
    fun nightTintFollowsDistanceFromTheIndicator() {
        val accent = Color(0xFFABC8F5)
        val inactive = GlassTokens.DarkNavigationInactive

        assertEquals(accent, GlassTokens.darkNavigationTabTint(accent, position = 1f, index = 1))
        assertEquals(lerp(inactive, accent, .75f), GlassTokens.darkNavigationTabTint(accent, position = 1.25f, index = 1))
        assertEquals(lerp(inactive, accent, .25f), GlassTokens.darkNavigationTabTint(accent, position = 1.25f, index = 2))
        assertEquals(inactive, GlassTokens.darkNavigationTabTint(accent, position = 1.25f, index = 0))
        assertEquals(inactive, GlassTokens.darkNavigationTabTint(accent, position = 2f, index = 1))
    }

    @Test
    fun opaqueIndicatorSpringTravelsThroughIntermediatePositions() {
        val animation = TargetBasedAnimation(
            animationSpec = navigationTabSpring(),
            typeConverter = Float.VectorConverter,
            initialValue = 0f,
            targetValue = 2f,
        )

        assertEquals(0f, animation.getValueFromNanos(0L), .001f)
        val inFlight = animation.getValueFromNanos(32_000_000L)
        assertTrue(inFlight > 0f && inFlight < 2f)
        assertEquals(2f, animation.getValueFromNanos(animation.durationNanos), .001f)
    }
}
