package cn.tonglu.app.ui.learning

import cn.tonglu.app.data.question.BankQuestion
import cn.tonglu.app.data.question.QuestionBank
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.google.gson.Gson

class QuestionBankCatalogTest {
    private fun bank(id: String) = QuestionBank(id, id, sourceNote = "测试夹具",
        questions = listOf(BankQuestion("q1", options = listOf("甲", "乙"), answer = 0),
            BankQuestion("q2", options = listOf("甲", "乙"), answer = 1)))

    @Test fun `按考次套卷分组且题型顺序固定`() {
        val groups = examBankGroups(listOf(bank("cet6-2025-12-2-reading"), bank("cet6-2025-12-2-listening"),
            bank("cet4-2026-06-1-reading"), bank("cet4-vocabulary")))
        assertEquals(2, groups.size)
        assertEquals("cet4-2026-06-1", groups[0].id)
        assertEquals(listOf("cet6-2025-12-2-listening", "cet6-2025-12-2-reading"), groups[1].banks.map { it.id })
    }

    @Test fun `标题含四级也不能误分到真题`() {
        assertNull(examGroupId(bank("cet4-vocabulary").copy(title = "四级方向")))
        assertNull(examGroupId(bank("cet4-2026-13-1-reading")))
        assertNull(examGroupId(bank("cet4-2026-06-4-reading")))
    }

    @Test fun `收起不会丢其他题组且行键不冲突`() {
        val banks = listOf(bank("cet4-2026-06-1-reading"), bank("cet4-2025-06-1-reading"), bank("cet4-vocabulary"))
        val rows = bankCatalogRows(banks, "all", mapOf("cet4-2026-06-1" to false))
        assertEquals(2, rows.count { it is BankCatalogRow.Group })
        assertEquals(1, rows.count { it is BankCatalogRow.Bank })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test fun `四六级与专项筛选不会混入其他分类`() {
        val banks = listOf(bank("cet4-2026-06-1-reading"), bank("cet6-2025-12-2-listening"), bank("cet4-vocabulary"))
        assertTrue(bankCatalogRows(banks, "cet6", emptyMap()).filterIsInstance<BankCatalogRow.Bank>().all { it.bank.id.startsWith("cet6-2025") })
        assertEquals("cet4-vocabulary", bankCatalogRows(banks, "other", emptyMap()).filterIsInstance<BankCatalogRow.Bank>().single().bank.id)
        assertTrue(bankCatalogRows(listOf(bank("cet4-vocabulary")), "cet4", emptyMap()).isEmpty())
    }

    @Test fun `继续到首道未做题且不认另一题库历史`() {
        val bank = bank("cet4-2026-06-1-reading")
        assertEquals(0, nextUnansweredIndex(bank, setOf("other:q1")))
        assertEquals(1, nextUnansweredIndex(bank, setOf("${bank.id}:q1")))
        assertEquals(0, nextUnansweredIndex(bank, bank.questionList.map { bank.keyOf(it) }.toSet()))
    }

    @Test fun `缺答案题不阻断下一道可作答题`() {
        val bank = bank("cet4-2026-06-1-reading")
        val changed = bank.copy(questions = listOf(BankQuestion("writing")) + bank.questionList)
        assertEquals(1, nextUnansweredIndex(changed, emptySet()))
    }

    @Test fun `订正后本轮列表固定便于查看解析`() {
        val bank = bank("cet4-2026-06-1-reading")
        val snapshot = setOf("${bank.id}:q1")
        assertEquals("q1", reviewQuestions(bank, snapshot).single().id)
        // 下一轮重新读取已订正后的空错题集，才清除本题。
        assertTrue(reviewQuestions(bank, emptySet()).isEmpty())
        assertEquals("q1", reviewQuestions(bank, snapshot).single().id)
    }

    @Test fun `题内AI不能用固定身份分享会话`() {
        val path = "cn/tonglu/app/ui/learning/QuestionBankScreen.kt"
        val file = listOf("src/main/java", "app/src/main/java", "../app/src/main/java")
            .map { File(it, path) }.first { it.isFile }
        val source = file.readText()
        val quiz = source.substringAfter("private fun BankQuizPane(")
        assertTrue(quiz.substringBefore(") {").contains("owner: String"))
        assertTrue(quiz.contains("owner = owner,"))
        assertFalse(quiz.contains("owner = \"question\","))
        assertFalse(source.contains("wrongCount = remember(bank.id, revision)"))
        assertTrue(quiz.contains("aiQuestionKey = bank.keyOf(question)"))
        assertTrue(quiz.contains("scopeKey = aiQuestionKey,"))
    }

    @Test fun `旧题库缺原卷链接不崩且非网页协议不进入浏览器`() {
        val json = Gson().toJson(bank("legacy"))
        assertFalse(json.contains("sourceUrl"))
        val old = Gson().fromJson(json, QuestionBank::class.java)
        assertNull(old.sourceLink)
        assertNull(old.copy(sourceUrl = "file:///private").sourceLink)
        assertNull(old.copy(sourceUrl = "javascript:alert(1)").sourceLink)
        assertEquals("https://example.org/paper.pdf", old.copy(sourceUrl = " https://example.org/paper.pdf ").sourceLink)
    }

    @Test fun `写作翻译可阅读但不伪造客观判分或放行残卷`() {
        val prompt = BankQuestion("q01", type = "prompt", prompt = "写一篇短文", answer = -1)
        val writing = bank("cet4-2025-06-1-writing").copy(questions = listOf(prompt))
        assertTrue(writing.usable)
        assertFalse(prompt.gradable)
        assertTrue(writing.copy(id = "cet6-2025-12-2-translation").usable)
        assertFalse(writing.copy(id = "cet4-2025-06-1-listening").usable)
        assertFalse(writing.copy(questions = listOf(prompt.copy(prompt = ""))).usable)
    }
}
