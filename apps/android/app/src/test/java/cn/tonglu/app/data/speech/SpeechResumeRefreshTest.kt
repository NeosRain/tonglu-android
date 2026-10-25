package cn.tonglu.app.data.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechResumeRefreshTest {
    @Test fun `首次进入不重复初始化`() {
        val refresh = SpeechResumeRefresh()
        assertFalse(refresh.onResume())
        assertFalse(refresh.onResume())
    }

    @Test fun `离开后回来只刷新一次且可重复返回`() {
        val refresh = SpeechResumeRefresh()
        repeat(3) {
            refresh.onPause()
            refresh.onPause()
            assertTrue(refresh.onResume())
            assertFalse(refresh.onResume())
        }
    }
}
