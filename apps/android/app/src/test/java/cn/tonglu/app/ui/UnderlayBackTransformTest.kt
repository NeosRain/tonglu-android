package cn.tonglu.app.ui

import org.junit.Assert.*
import org.junit.Test

class UnderlayBackTransformTest {
    @Test fun idleAndCommittedPositionsMatchAfterOverlayDisposal() {
        for (left in listOf(true, false)) {
            assertEquals(underlayBackTransform(0f, left), underlayBackTransform(1f, left))
            assertEquals(UnderlayBackTransform(0f, 1f), underlayBackTransform(1.2f, left))
            assertEquals(UnderlayBackTransform(0f, 1f), underlayBackTransform(-.1f, left))
        }
    }
    @Test fun revealedPageMovesTowardsOriginWithoutOvershooting() {
        for (left in listOf(true, false)) {
            var lastShift = Float.MAX_VALUE
            var lastScale = 0f
            for (step in 1..100) {
                val value = underlayBackTransform(step / 100f, left)
                val shift = kotlin.math.abs(value.translationRatio)
                assertTrue(shift <= lastShift)
                assertTrue(value.scale >= lastScale && value.scale <= 1f)
                assertTrue(shift <= OverlayMotion.UNDERLAY_PARALLAX)
                lastShift = shift; lastScale = value.scale
            }
            assertEquals(0f, lastShift, 0f)
            assertEquals(1f, lastScale, 0f)
        }
    }
    @Test fun rightEdgeMirrorsLeftEdge() {
        for (p in listOf(.1f, .5f, .99f)) {
            val left = underlayBackTransform(p, true)
            val right = underlayBackTransform(p, false)
            assertEquals(-left.translationRatio, right.translationRatio, 0f)
            assertEquals(left.scale, right.scale, 0f)
        }
    }
}
