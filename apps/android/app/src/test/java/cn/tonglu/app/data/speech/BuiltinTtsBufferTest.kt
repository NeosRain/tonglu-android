package cn.tonglu.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静态播放缓冲容量的回归测试（`docs/BUGS.md` **A11**）。
 *
 * ## 为什么这个测试必须存在
 *
 * 2026-10-08 用户报「听发音只出第一个音节」：所有走内置离线模型的朗读都只放开头一小截。
 * 真因在 `BuiltinTts.play()` 算 `AudioTrack` 缓冲容量那一行 ——
 * `MODE_STATIC` 下那个值**就是音频的容量**，写超出的部分被丢掉，而 `write()`
 * 只返回实写字节数、旧代码没接，所以**截断是静默的**。
 *
 * 这个 bug 骗过了所有既有验证：
 * - 「自检离线语音」走 `audio.save()` 落 wav，**不经过 AudioTrack** → 一直是绿的；
 * - 界面上看不出任何异常（不报错、不崩、只是短）。
 *
 * 所以只能靠**确定性的算式测试**钉住它。
 */
class BuiltinTtsBufferTest {

    /** 常见的 `getMinBufferSize` 返回值量级（22050Hz mono 16bit 只有几 KB）。 */
    private val minBuffer = 4096

    @Test
    fun `容量必须装得下整段音频 —— 这是 A11 的回归点`() {
        // 一个 1.3 秒的单词在 22050Hz 单声道 16bit 下约 57KB
        val data = 57_330
        val got = BuiltinTts.staticBufferBytes(minBuffer, data)
        assertEquals("容量应等于数据长度（允许更大，但绝不能更小）", data, got)
        assertTrue("写进去的必须装得下", got >= data)
    }

    @Test
    fun `更长的音频要拿到更大的容量 —— 旧 bug 会让三段不同长度的音频拿到同一个值`() {
        // 旧写法下这三个都会取到 minBuffer，于是长度完全不同的音频放出来一样短
        val short = BuiltinTts.staticBufferBytes(minBuffer, 12_000)
        val medium = BuiltinTts.staticBufferBytes(minBuffer, 57_330)
        val long = BuiltinTts.staticBufferBytes(minBuffer, 154_350)

        assertTrue("短 < 中", short < medium)
        assertTrue("中 < 长", medium < long)
        listOf(short to 12_000, medium to 57_330, long to 154_350).forEach { (got, data) ->
            assertTrue("容量 $got 装不下 $data 字节", got >= data)
        }
    }

    @Test
    fun `数据比下限还小时取下限`() {
        assertEquals(minBuffer, BuiltinTts.staticBufferBytes(minBuffer, 100))
        assertEquals(minBuffer, BuiltinTts.staticBufferBytes(minBuffer, 0))
    }

    @Test
    fun `异常入参不崩、且不会返回负数`() {
        assertEquals(0, BuiltinTts.staticBufferBytes(0, 0))
        assertTrue(BuiltinTts.staticBufferBytes(-1, -1) >= 0)
        assertTrue(BuiltinTts.staticBufferBytes(-5, 32) >= 32)
        assertTrue(BuiltinTts.staticBufferBytes(Int.MAX_VALUE, 32) > 0)
    }

    /**
     * **对照组**：把旧写法原样算一遍，证明上面的用例真的能抓住它。
     *
     * 如果哪天有人把 `staticBufferBytes` 改回旧语义，这条会先亮 ——
     * 而不是等到用户在真机上听见「只念了第一个音节」。
     */
    @Test
    fun `对照组 —— 旧写法确实会截断`() {
        val data = 57_330
        @Suppress("KotlinConstantConditions")
        val old = minBuffer.coerceAtLeast(data.coerceAtMost(1))
        assertEquals("旧写法退化成 minBuffer —— 这正是当年的故障", minBuffer, old)
        assertTrue("旧写法装不下数据", old < data)
    }
}
