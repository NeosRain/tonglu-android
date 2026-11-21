package cn.tonglu.app.data.speech

import org.junit.Assert.*
import org.junit.Test

class TtsDownloadMetricsTest {
    @Test fun 首次进度立即发布() {
        assertNotNull(TtsDownloadMetrics(1000).sample(1024, 4096, 1000))
    }

    @Test fun 网络块不会逐块触发重组() {
        val metrics = TtsDownloadMetrics(0)
        assertNotNull(metrics.sample(1, 10000, 1))
        assertNull(metrics.sample(2, 10000, 200))
        assertNotNull(metrics.sample(3, 10000, 251))
    }

    @Test fun 完成进度不受节流丢失() {
        val metrics = TtsDownloadMetrics(0)
        metrics.sample(9, 10, 100)
        assertEquals(1f, metrics.sample(10, 10, 101)!!.progress)
    }

    @Test fun 速率与剩余时间来自实际字节() {
        val snapshot = TtsDownloadMetrics(0).sample(1048576, 5242880, 5000)!!
        assertEquals(209715L, snapshot.bytesPerSecond)
        assertEquals("1.0 MB / 5.0 MB · 205 KB/s · 约0分20秒", snapshot.label)
        assertEquals(0.2f, snapshot.progress)
    }

    @Test fun 未知大小不显示假剩余时间() {
        val snapshot = TtsDownloadMetrics(0).sample(1048576, -1, 5000)!!
        assertEquals("1.0 MB / 未知大小 · 205 KB/s", snapshot.label)
        assertEquals(0f, snapshot.progress)
    }

    @Test fun 零字节无除零或假估时() {
        val snapshot = TtsDownloadMetrics(5).sample(0, 10, 5)!!
        assertEquals(0L, snapshot.bytesPerSecond)
        assertTrue(snapshot.label.endsWith("估算中…"))
    }

    @Test fun 大小按1024平方换算并保留一位小数() {
        assertEquals("1.0 MB", formatTtsMegabytes(1048576))
        assertEquals("101.9 MB", formatTtsMegabytes(106849894))
    }

    @Test fun 剩余时间需要同时满足五秒和一MB() {
        assertEquals("估算中…", ttsRemainingTimeLabel(1048576, 5242880, 4999, 209715))
        assertEquals("估算中…", ttsRemainingTimeLabel(1048575, 5242880, 5000, 209715))
        assertEquals("约0分20秒", ttsRemainingTimeLabel(1048576, 5242880, 5000, 209715))
    }
}
