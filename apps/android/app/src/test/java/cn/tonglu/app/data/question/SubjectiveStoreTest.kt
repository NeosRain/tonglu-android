package cn.tonglu.app.data.question

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主观题本地作答的纯逻辑（槽位 C · plan §5 第 4 条「作答体验」）。
 *
 * **这里钉的核心是那条红线：主观题不判分。**
 * 所以最后两条是**对照组**：`SubjectiveAnswer` 里不许出现任何对错/分数字段，
 * 草稿也不许被当成「已提交」—— 那等于把没交出去的东西说成交了。
 *
 * 另一件要钉的是「坏数据不能打崩界面」：Gson 会绕过构造器把非空 String 塞成 null，
 * 本项目已记过多次，所以 [`parseAnswers`] 必须逐字段兜底。
 */
class SubjectiveStoreTest {

    private val now = 1_700_000_000_000L

    /** 只存草稿 = 没提交。这跟客观题「选了选项」完全不同，不能混。 */
    @Test
    fun `只存草稿 不算提交`() {
        val a = SubjectiveAnswer(draft = "写了一版")
        assertFalse(a.hasSubmitted)
        assertEquals(0L, a.submittedAt)
    }

    /** 提交 = 有正文 + 有时间戳，两者缺一都不算「交过」。 */
    @Test
    fun `提交要有正文与时间戳`() {
        assertTrue(SubjectiveAnswer(submitted = "交的这版", submittedAt = now).hasSubmitted)
        assertFalse("只有时间戳没有正文，不算提交",
            SubjectiveAnswer(submitted = "", submittedAt = now).hasSubmitted)
    }

    /** 交完之后又改了草稿 → 要能提醒用户「要不要再交一次」。 */
    @Test
    fun `交完又改草稿 能识别出来`() {
        val a = SubjectiveAnswer(draft = "第二版", submitted = "第一版", submittedAt = now)
        assertTrue(a.draftNewerThanSubmit)
        // 对照：草稿没改 → 不该提醒
        val same = SubjectiveAnswer(draft = "第一版", submitted = "第一版", submittedAt = now)
        assertFalse("草稿没变就别提醒", same.draftNewerThanSubmit)
    }

    /** 🔴 对照组：这个类里**不许**有任何对错 / 分数字段 —— 主观题判不了分。 */
    @Test
    fun `主观题作答里没有对错或分数字段`() {
        val fields = SubjectiveAnswer::class.java.declaredFields.map { it.name }.toSet()
        val banned = listOf("correct", "score", "right", "wrong", "grade", "isCorrect", "answer")
        val hit = banned.filter { it in fields }
        assertTrue("主观题作答不该有判分字段，实到：$hit", hit.isEmpty())
        // 该有的五样一个都不能少。
        // ⚠️ 这里**不做集合精确相等**：JVM 可能带合成字段，精确相等会脆到
        // 「加个无关字段就红」，那反而把真正的红线（有没有判分字段）淹没了。
        val needed = setOf("draft", "submitted", "submittedAt", "note", "updatedAt")
        val missing = needed - fields
        assertTrue("缺字段：$missing", missing.isEmpty())
    }

    /** 🔴 对照组：草稿不许被当成已提交。 */
    @Test
    fun `草稿不会被当成已提交`() {
        val a = SubjectiveAnswer(draft = "写了但没交", submitted = "", submittedAt = 0)
        assertFalse("写了草稿 ≠ 提交了", a.hasSubmitted)
        assertEquals("", a.submitted)
    }

    /** 坏 JSON 不能把界面打崩 —— 解析失败退回空 map。 */
    @Test
    fun `坏 JSON 退回空 不抛异常`() {
        assertTrue(parseAnswers("这不是 JSON").isEmpty())
        assertTrue(parseAnswers("").isEmpty())
        assertTrue(parseAnswers("{}").isEmpty())
        assertTrue(parseAnswers("null").isEmpty())
    }

    /** Gson 绕过构造器塞 null 时，逐字段兜底成空串，界面拿到的一定不是 null。 */
    @Test
    fun `字段缺失退回空串 不返回 null`() {
        // JSON 里只给了 questionKey，三个文本字段都缺（Gson 会塞 null）
        val map = parseAnswers("""{"b1:q1":{}}""")
        val a = map["b1:q1"]
        assertTrue("要能解析出来", a != null)
        assertEquals("", a!!.draft)
        assertEquals("", a.submitted)
        assertEquals("", a.note)
        assertFalse(a.hasSubmitted)
    }

    /** 空 key 的条目要丢掉（题目键是存储主键，空的会变成孤儿数据）。 */
    @Test
    fun `空题目键丢掉`() {
        assertTrue(parseAnswers("""{"":{"draft":"x"}}""").isEmpty())
    }
}
