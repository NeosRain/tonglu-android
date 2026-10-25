package cn.tonglu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发帖正文长度规则（[PostBodyLimit]）。
 *
 * ## 为什么这几条非测不可
 * 它坏掉时**界面上完全看不出来**，而且都只在边界上出问题：
 * - 判定写成 `>=` → 正好写满 20000 字的用户**永远发不出去**，还不知道为什么
 *   （他删一个字符就变成 19999 了，看起来应该能发，实际是上一个状态被拒）；
 * - `remaining` 忘了 `coerceAtLeast(0)` → 界面显示「还可输入 -37 字」；
 * - `WARN_AT` 和 `POST_BODY_MAX` 脱钩 → 提示在该出现的时候不出现。
 *
 * 另一端（后端 DTO 的 `@Length(1, 20000)`）单测够不到 —— 不在同一个 classpath 上，
 * 由 `scripts/audit_cross_end_limits.py` 的「帖子正文」契约守着。**两边一起才叫契约。**
 */
class PostLimitsTest {

    /** [POST_BODY_MAX] 是**跨端契约值**，不是随手定的。改它必须同时改后端 DTO。 */
    @Test
    fun `上限是 20000，和后端 DTO 对齐`() {
        assertEquals(20000, POST_BODY_MAX)
    }

    /** 提示阈值必须**跟着上限走**。有人改了上限却忘了这个 18000，这条会红。 */
    @Test
    fun `提示阈值是上限的九成`() {
        assertEquals(POST_BODY_MAX * 9 / 10, PostBodyLimit.WARN_AT)
        assertEquals(18000, PostBodyLimit.WARN_AT)
        // 优先级陷阱：写成 `POST_BODY_MAX / (10 * 9)` 会得到 222，也"看起来合理"
        assertTrue("阈值不能大于上限", PostBodyLimit.WARN_AT < POST_BODY_MAX)
        assertTrue("阈值不能太小，否则等于一直显示", PostBodyLimit.WARN_AT > POST_BODY_MAX / 2)
    }

    /** 边界：正好 20000 字**必须算合法**（后端 `@Length(1, 20000)` 是闭区间）。 */
    @Test
    fun `正好写满上限仍然是合法的`() {
        assertFalse(PostBodyLimit.overLimit("字".repeat(POST_BODY_MAX)))
        assertEquals(0, PostBodyLimit.remaining("字".repeat(POST_BODY_MAX)))
        assertEquals(0, PostBodyLimit.overBy("字".repeat(POST_BODY_MAX)))
    }

    /** 边界的三点取样，确认翻转点**恰好**在 20001。 */
    @Test
    fun `翻转点在 20001 这一个字上`() {
        assertFalse(PostBodyLimit.overLimit("字".repeat(POST_BODY_MAX - 1)))
        assertFalse(PostBodyLimit.overLimit("字".repeat(POST_BODY_MAX)))
        assertTrue(PostBodyLimit.overLimit("字".repeat(POST_BODY_MAX + 1)))
    }

    /** **对照组**：把判定改成 `>=` 必须红 —— 否则上面三条没在测任何东西。 */
    @Test
    fun `对照组 若判定写成大于等于则写满的用户发不出去`() {
        val atLimit = "字".repeat(POST_BODY_MAX)
        val wrong = atLimit.length >= POST_BODY_MAX          // 故意复刻错误的写法
        assertTrue("这个对照组要能复现出「写满=被拒」", wrong)
        assertFalse("正确实现不拦它", PostBodyLimit.overLimit(atLimit))
    }

    /** 超限之后剩余量**不能变负数**（否则界面文案会变成「还可输入 -37 字」）。 */
    @Test
    fun `超限后剩余量为零而不是负数`() {
        val over = "字".repeat(POST_BODY_MAX + 37)
        assertEquals(0, PostBodyLimit.remaining(over))
        assertEquals(37, PostBodyLimit.overBy(over))
    }

    /** 提示的出现/不出现，也在边界上验一次。 */
    @Test
    fun `提示只在到达阈值后出现`() {
        assertFalse(PostBodyLimit.shouldWarn("字".repeat(PostBodyLimit.WARN_AT - 1)))
        assertTrue(PostBodyLimit.shouldWarn("字".repeat(PostBodyLimit.WARN_AT)))
    }

    /**
     * **口径**：和后端一样按 UTF-16 code unit 数。
     * 这条钉住的是「有人把 `length` 改成 `codePointCount`」这种"更正确"的改法 ——
     * 那会让客户端比后端宽松（emoji 从 2 变 1），变成新的跨端口径 bug。
     */
    @Test
    fun `口径是 UTF-16 code unit，和后端 class-validator 一致`() {
        assertEquals(2, PostBodyLimit.used("😀"))            // 代理对，算 2
        assertEquals(2, POST_BODY_MAX.let { "😀".length })   // 与 JS 的 String.length 同值
        // 20000 个 emoji = 40000 code unit > 上限 → 客户端也认为超了（不会放过）
        assertTrue(PostBodyLimit.overLimit("😀".repeat(POST_BODY_MAX)))
    }

    /** 空串是「不够长」而不是「太长」—— 这条护栏只管上限，下限由「发布」按钮的 isNotBlank 管。 */
    @Test
    fun `空正文不算超限`() {
        assertFalse(PostBodyLimit.overLimit(""))
        assertEquals(POST_BODY_MAX, PostBodyLimit.remaining(""))
        assertFalse(PostBodyLimit.shouldWarn(""))
    }
}
