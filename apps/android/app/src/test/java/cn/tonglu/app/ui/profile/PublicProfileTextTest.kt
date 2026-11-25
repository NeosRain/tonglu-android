package cn.tonglu.app.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicProfileTextTest {
    @Test
    fun `profile field limit preserves whole emoji`() {
        assertEquals("摄影🌲", limitProfileText("摄影🌲读书", 3))
    }
}
