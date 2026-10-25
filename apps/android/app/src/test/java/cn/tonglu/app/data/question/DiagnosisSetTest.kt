package cn.tonglu.app.data.question

import cn.tonglu.app.ui.learning.buildDiagnosisSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词汇诊断抽题规则（docs/plan/56 §4.4 / docs/plan/57 §5.3 T6）。
 *
 * 抽题是**纯函数**，所以能在这里锁死三条纪律：
 *  1. 确定性 —— 同一份题库每次抽到的题完全一样（否则「上次 8/10、这次 6/10」
 *     分不清是水平变了还是题变了）；
 *  2. 分层不跨层凑数 —— 某层不够就取满该层，**不去别的层借**（借了会歪曲难度分布）；
 *  3. 封顶 30 题 —— §4.4 的 5–8 分钟预算。
 */
class DiagnosisSetTest {

    private fun bank(id: String, counts: Map<String, Int>): QuestionBank {
        val questions = mutableListOf<BankQuestion>()
        for (tier in Difficulty.order) {
            repeat(counts[tier] ?: 0) { i ->
                questions += BankQuestion(
                    id = "$tier-$i",
                    type = "single-choice",
                    prompt = "题 $tier-$i",
                    options = listOf("a", "b", "c", "d"),
                    answer = 0,
                    explanation = "解析",
                    difficulty = tier,
                )
            }
        }
        return QuestionBank(
            id = id, title = "测试题组", sourceNote = "平台自建练习题，不是任何考试的真题",
            version = 1, questions = questions,
        )
    }

    @Test
    fun `各层拿满配额时合计不超过三十题`() {
        val rich = bank("rich", mapOf(Difficulty.BASIC to 50, Difficulty.CORE to 50, Difficulty.ADVANCED to 50))
        val picks = buildDiagnosisSet(listOf(rich))
        assertEquals("封顶 30 题（§4.4 的 5–8 分钟预算）", 30, picks.size)
        assertEquals(10, picks.count { it.question.difficulty == Difficulty.BASIC })
        assertEquals(12, picks.count { it.question.difficulty == Difficulty.CORE })
        assertEquals(8, picks.count { it.question.difficulty == Difficulty.ADVANCED })
    }

    @Test
    fun `同一份题库两次抽取结果完全一致`() {
        val b = bank("stable", mapOf(Difficulty.BASIC to 20, Difficulty.CORE to 20, Difficulty.ADVANCED to 20))
        val first = buildDiagnosisSet(listOf(b)).map { "${it.bank.id}:${it.question.id}" }
        val second = buildDiagnosisSet(listOf(b)).map { "${it.bank.id}:${it.question.id}" }
        assertEquals("确定性抽题：同一份题库每次结果必须一样", first, second)
    }

    @Test
    fun `某层不足时只取满该层 不跨层凑数`() {
        // basic 只有 3 题（配额 10），core 只有 2 题（配额 12），advanced 充足
        val thin = bank("thin", mapOf(Difficulty.BASIC to 3, Difficulty.CORE to 2, Difficulty.ADVANCED to 40))
        val picks = buildDiagnosisSet(listOf(thin))
        assertEquals("basic 层不足就 3 题，不向 core 借", 3, picks.count { it.question.difficulty == Difficulty.BASIC })
        assertEquals("core 层不足就 2 题", 2, picks.count { it.question.difficulty == Difficulty.CORE })
        assertEquals("advanced 拿满 8 题", 8, picks.count { it.question.difficulty == Difficulty.ADVANCED })
        assertEquals("合计 13 题（3+2+8）", 13, picks.size)
    }

    @Test
    fun `多层题库混合抽取时按层独立取满`() {
        val a = bank("a", mapOf(Difficulty.BASIC to 4, Difficulty.CORE to 0, Difficulty.ADVANCED to 0))
        val b = bank("b", mapOf(Difficulty.BASIC to 20, Difficulty.CORE to 20, Difficulty.ADVANCED to 20))
        val picks = buildDiagnosisSet(listOf(a, b))
        // basic 配额 10：a 给 4，b 按声明顺序补 6 → 正好 10
        assertEquals(10, picks.count { it.question.difficulty == Difficulty.BASIC })
        assertEquals(12, picks.count { it.question.difficulty == Difficulty.CORE })
        assertEquals(8, picks.count { it.question.difficulty == Difficulty.ADVANCED })
    }

    @Test
    fun `没有可用题组时返回空列表 不抛异常`() {
        assertTrue(buildDiagnosisSet(emptyList()).isEmpty())
    }

    /** 残题（选项不足 / 答案越界）不能进自测 —— 那是「无从判分」的题。 */
    @Test
    fun `不可判分的题不进抽取`() {
        val broken = QuestionBank(
            id = "broken", title = "残缺题组", sourceNote = "平台自建练习题，不是任何考试的真题",
            version = 1,
            questions = listOf(
                BankQuestion("bad", "single-choice", "题", listOf("a"), 0, "解析", Difficulty.BASIC),
                BankQuestion("good", "single-choice", "题", listOf("a", "b"), 0, "解析", Difficulty.BASIC),
            ),
        )
        val picks = buildDiagnosisSet(listOf(broken))
        assertEquals("只有一道可判分的题", 1, picks.size)
        assertEquals("good", picks.first().question.id)
    }
}
