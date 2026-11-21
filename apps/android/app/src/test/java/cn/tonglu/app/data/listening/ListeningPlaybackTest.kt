package cn.tonglu.app.data.listening

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningPlaybackTest {
    @Test
    fun `原音准备完成时停在开头`() {
        val playback = ListeningPlayback(durationMs = 26_600)

        assertTrue(playback.isLoaded)
        assertFalse(playback.isPlaying)
        assertEquals(0, playback.positionMs)
    }

    @Test
    fun `暂停时改倍速不调用可能恢复播放的平台操作`() {
        val speed = ListeningSpeed()
        val paused = ListeningPlayback(durationMs = 26_600, positionMs = 16_100)
        var playback = paused

        speed.change(1.25f, playback.isPlaying) {
            playback = playback.copy(isPlaying = true, positionMs = 16_200)
        }

        assertEquals(paused, playback)
    }

    @Test
    fun `开始播放时应用暂停期间选定的倍速`() {
        val speed = ListeningSpeed()
        speed.change(1.25f, isPlaying = false) { error("暂停期间不应写入播放器") }
        var applied = 1.0f

        speed.onPlay { applied = it }

        assertEquals(1.25f, applied, 0f)
    }

    @Test
    fun `播放中改倍速立即应用`() {
        var applied = 1.0f

        ListeningSpeed().change(0.8f, isPlaying = true) { applied = it }

        assertEquals(0.8f, applied, 0f)
    }

    @Test
    fun `断网时立即失败且不发起准备`() = runBlocking {
        val failure = runCatching {
            awaitListeningAudio(hasNetwork = false) { error("断网时不应准备原音") }
        }.exceptionOrNull()

        assertTrue(failure is ListeningNetworkUnavailable)
    }

    @Test
    fun `准备超时后下一次请求可以重试成功`() = runBlocking {
        assertEquals(8_000L, LISTENING_AUDIO_TIMEOUT_MS)
        val failure = runCatching {
            awaitListeningAudio(hasNetwork = true, timeoutMs = 25L) { awaitCancellation() }
        }.exceptionOrNull()
        assertTrue(failure is ListeningNetworkUnavailable)

        var prepared = false
        awaitListeningAudio(hasNetwork = true) { prepared = true }
        assertTrue(prepared)
    }
}
