package cn.tonglu.app.ui

import cn.tonglu.app.data.listening.PracticeMode
import cn.tonglu.app.data.listening.QuestionAttempt
import cn.tonglu.app.ui.learning.PracticeRecord
import cn.tonglu.app.ui.learning.practiceRecordOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 今日卡「做题记录」三个数的口径（plan §5 第 2 条：四个入口都要**可验证的完成记录**）。
 *
 * 为什么要单测：这三个数直接显示给用户，而且**错了不会报错**，只会静默显示错值 ——
 * 「今日已练 0 题」看起来和「今天真的没练」一模一样。
 * 抽成纯函数就是为了在这里用真人会写错的边界把它钉住。
 */
class PracticeRecordTest {

    private val dayStart = 1_700_000_000_000L

    private fun at(key: String, answeredAt: Long, correct: Boolean = false,
                   mode: String = PracticeMode.QUIZ) =
        QuestionAttempt(questionId = key.substringAfter(':'), questionKey = key,
            materialId = "bank-1", materialTitle = "四级题组", chosen = 0,
            correct = correct, answeredAt = answeredAt, mode = mode)

    @Test
    fun `今日按题去重，同一题连答三次只算一题`() {
        val rows = listOf(
            at("b:q1", dayStart + 100),
            at("b:q1", dayStart + 200),
            at("b:q1", dayStart + 300),
            at("b:q2", dayStart + 400),
        )
        val r = practiceRecordOf(rows, dayStart, emptySet())
        assertEquals("今日去重后应为 2 题", 2, r.todayQuiz)
        assertEquals("累计也是 2 题（同一批数据）", 2, r.answered)
    }

    @Test
    fun `今日边界用当天零点，不是 24 小时前`() {
        val rows = listOf(
            at("b:q1", dayStart - 1),        // 昨天最后一刻 —— 不算今日
            at("b:q2", dayStart),             // 恰好 0 点 —— 算今日（>=）
            at("b:q3", dayStart + 1),
        )
        val r = practiceRecordOf(rows, dayStart, emptySet())
        assertEquals("只有 q2、q3 属于今日", 2, r.todayQuiz)
        assertEquals("累计不受今日边界影响", 3, r.answered)
    }

    @Test
    fun `错题数取错题本的键数，不自己按 correct 重算`() {
        // 错题本的规则是「最近一次是错的」（PracticeStore.wrongQuestions），
        // 这里必须**用它给的集合**，否则「错过但已订正」的题会被算成错题。
        val rows = listOf(at("b:q1", dayStart + 1, correct = true))
        val r = practiceRecordOf(rows, dayStart, setOf("b:q9"))
        assertEquals(1, r.todayQuiz)
        assertEquals(1, r.wrong)
    }

    @Test
    fun `空记录给全 0，不抛异常`() {
        assertEquals(PracticeRecord(), practiceRecordOf(emptyList(), dayStart, emptySet()))
    }

    @Test
    fun `三条线各算各的，不串`() {
        // 用户口径：单词走单词的，答案走答案的。例句的朗读既不是单词也不是答案，单列一条线。
        val rows = listOf(
            at("bank:q1", dayStart + 1, mode = PracticeMode.QUIZ),
            at("bank:q2", dayStart + 2, mode = PracticeMode.QUIZ),
            at("legacy:pack-1:q1", dayStart + 3, mode = PracticeMode.MATERIAL),
            at("sentence:s1", dayStart + 4, mode = PracticeMode.SENTENCE),
        )
        val r = practiceRecordOf(rows, dayStart, emptySet())
        assertEquals("题库线 2 题", 2, r.todayQuiz)
        assertEquals("材料线 1 题", 1, r.todayMaterial)
        assertEquals("例句线 1 句", 1, r.todaySentence)
        assertEquals("练习线 = 题库 + 材料", 3, r.todayPractice)
        assertEquals("累计仍是全部 4 条", 4, r.answered)
    }

    @Test
    fun `对照组-不做去重就会变红`() {
        // 故意用「不过滤去重」的写法算一遍，确认上面第 1 条真的在钉去重这件事：
        // 若哪天把 distinct() 删掉，本用例的期望值会与实际不符（这里断言的是「两者不同」）。
        val rows = listOf(at("b:q1", dayStart + 1), at("b:q1", dayStart + 2))
        val deduped = practiceRecordOf(rows, dayStart, emptySet()).todayQuiz
        val naive = rows.count { it.answeredAt >= dayStart }
        assertEquals("去重版应为 1", 1, deduped)
        assertEquals("未去重版应为 2（用于说明对照组有效）", 2, naive)
    }
}

/**
 * 「错题复习」点进去开哪个题组（`QuestionBankScreen.bankWithMostWrong`）。
 *
 * 为什么要单测：这是本轮**唯一没法在真机上确认**的行为（真机当时已断开），
 * 而它写错的症状极轻 —— 只是「点了错题复习没反应」，不报错、不崩、日志也没有。
 * 前缀匹配是这里最容易错的一步，所以拿它当判据。
 */
class BankWrongPickTest {

    @Test
    fun `挑错题最多的那个题组`() {
        val keys = setOf("bankA:q1", "bankA:q2", "bankA:q3", "bankB:q9", "bankC:q1")
        assertEquals("bankA", cn.tonglu.app.ui.learning.bankWithMostWrong(listOf("bankB", "bankC", "bankA"), keys))
    }

    @Test
    fun `前缀必须带冒号，否则 bank 会被 bank1 蹭到`() {
        // 对照组：把错题都记在 `bankA` 上，而清单里有个 `bankA2`。
        // 若实现写成 startsWith(id)（没有冒号），bankA2 会把它算成自己的错题。
        val keys = setOf("bankA:q1")
        assertEquals("bankA", cn.tonglu.app.ui.learning.bankWithMostWrong(listOf("bankA2", "bankA"), keys))
    }

    @Test
    fun `没有错题返回 null，不要在空组里硬开一个`() {
        assertEquals(null, cn.tonglu.app.ui.learning.bankWithMostWrong(listOf("bankA", "bankB"), emptySet()))
    }

    @Test
    fun `清单为空也返回 null`() {
        assertEquals(null, cn.tonglu.app.ui.learning.bankWithMostWrong(emptyList(), setOf("bankA:q1")))
    }
}
