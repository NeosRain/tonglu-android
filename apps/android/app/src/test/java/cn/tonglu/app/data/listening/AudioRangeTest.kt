package cn.tonglu.app.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 听力时间轴区间的收口逻辑（`AudioRange.clamp`）。
 *
 * 每组都配**对照组**：把校验去掉（直接构造区间）必须得到不同结果 ——
 * 否则这条测试什么都没钉住。
 */
class AudioRangeTest {

    // ---------------------------------------------------------------- 正常路径

    @Test
    fun `合法区间原样返回`() {
        val s = AudioRange.clamp(38_200, 178_700, 1_572_408)
        assertEquals(38_200, s?.startMs)
        assertEquals(178_700, s?.endMs)
        assertEquals(140_500, s?.lengthMs)
    }

    @Test
    fun `终点超出音轨时裁到轨尾`() {
        val s = AudioRange.clamp(1_500_000, 9_999_999, 1_572_408)
        assertEquals(1_500_000, s?.startMs)
        assertEquals(1_572_408, s?.endMs)
    }

    @Test
    fun `时长未知时不裁上限`() {
        val s = AudioRange.clamp(1_000, 2_000, 0)
        assertEquals(1_000, s?.startMs)
        assertEquals(2_000, s?.endMs)
    }

    // ---------------------------------------------------------------- 非法输入

    @Test
    fun `没有时间轴（起点为负）返回 null`() {
        assertNull(AudioRange.clamp(-1, 1_000, 100_000))
    }

    @Test
    fun `区间反向返回 null`() {
        assertNull(AudioRange.clamp(5_000, 5_000, 100_000))
        assertNull(AudioRange.clamp(5_000, 4_000, 100_000))
    }

    @Test
    fun `起点已在轨尾之外返回 null`() {
        // 这条最容易被忽略：说明时间轴不是照着这条音轨量的（换过音频源）
        assertNull(AudioRange.clamp(200_000, 210_000, 100_000))
    }

    // ---------------------------------------------------------------- 对照组

    @Test
    fun `对照组·去掉校验时同样的输入会产出非法区间`() {
        // 模拟「没有 clamp」的写法：直接信服务端
        val naiveStart = 5_000
        val naiveEnd = 4_000
        val naiveValid = naiveEnd > naiveStart
        assertEquals(false, naiveValid)                       // 朴素写法要求调用方自己判，容易漏
        assertNull(AudioRange.clamp(naiveStart, naiveEnd, 100_000))  // 收口后一定为 null
    }

    @Test
    fun `对照组·不裁上限时会超出音轨`() {
        val rawEnd = 9_999_999
        assertEquals(true, rawEnd > 1_572_408)                // 原始值确实越轨
        assertEquals(1_572_408, AudioRange.clamp(1_500_000, rawEnd, 1_572_408)?.endMs)
    }
}
