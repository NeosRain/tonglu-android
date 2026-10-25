package cn.tonglu.app.ui.startup

import org.junit.Assert.*
import org.junit.Test
import cn.tonglu.app.data.startupOwnerBucket

class StartupMotionTest {
    @Test fun startAndEndAreFlat() {
        for (y in listOf(0f, .25f, .5f, .75f, 1f)) {
            assertEquals(0f, startupWave(0f, .5f, y), 0f)
            assertEquals(0f, startupWave(.68f, .5f, y), 0f)
            assertEquals(0f, startupWave(1f, .5f, y), 0f)
        }
    }
    @Test fun waveMovesFromBottomToTop() {
        assertTrue(kotlin.math.abs(startupWave(.13f, .5f, .9f)) > kotlin.math.abs(startupWave(.13f, .5f, .1f)))
        assertTrue(kotlin.math.abs(startupWave(.52f, .5f, .1f)) > kotlin.math.abs(startupWave(.52f, .5f, .9f)))
    }
    @Test fun textOnlyAppearsAfterTilesSettle() {
        assertEquals(0f, startupTextReveal(.67f), 0f)
        assertTrue(startupTextReveal(.8f) in .01f.. .99f)
        assertEquals(1f, startupTextReveal(1f), 0f)
    }
    @Test fun ownerCannotBecomePath() {
        val a = startupOwnerBucket("../alice")
        assertTrue(Regex("[a-f0-9]{64}").matches(a))
        assertNotEquals(a, startupOwnerBucket("bob"))
        assertEquals(a, startupOwnerBucket("../alice"))
    }
}
