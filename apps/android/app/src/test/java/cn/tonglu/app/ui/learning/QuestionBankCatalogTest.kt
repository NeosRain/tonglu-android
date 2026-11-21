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

    @Test fun `缺音源听力题只读 选对或标不认识都不判分`() {
        val bank = bank("cet4-2025-06-1-listening")
        val question = bank.questionList.first()

        assertTrue(question.gradable)
        assertTrue(bank.isMissingListeningAudio(question))
        assertFalse(bank.canGrade(question))
        assertNull(bank.gradeAnswer(question, question.answer, unknown = false))
        assertNull(bank.gradeAnswer(question, -1, unknown = true))
    }

    @Test fun `有原音或朗读文本的听力题及普通文字题仍可判分`() {
        val listening = bank("cet4-2025-06-1-listening")
        val question = listening.questionList.first()
        val original = question.copy(audioUrl = "https://example.org/audio.mp3")
        val narration = question.copy(audioText = "A short recording.")

        assertEquals(true, listening.gradeAnswer(original, 0, unknown = false))
        assertEquals(false, listening.gradeAnswer(narration, 1, unknown = false))
        assertEquals(true, bank("cet4-2025-06-1-reading").gradeAnswer(question, 0, unknown = false))
    }

    @Test fun `继续作答跳过只读听力题`() {
        val bank = bank("cet4-2025-06-1-listening")
        val (first, second) = bank.questionList
        val mixed = bank.copy(questions = listOf(first, second.copy(audioText = "A short recording.")))

        assertEquals(1, nextUnansweredIndex(mixed, emptySet()))
        assertEquals(1, nextUnansweredIndex(mixed, setOf(mixed.keyOf(second))))
        assertEquals(0, nextUnansweredIndex(bank, emptySet()))
    }

    @Test fun `旧错题快照只保留有音源的听力题`() {
        val bank = bank("cet4-2025-06-1-listening")
        val (first, second) = bank.questionList
        val playable = second.copy(audioText = "A short recording.")
        val mixed = bank.copy(questions = listOf(first, playable))
        val oldWrongKeys = mixed.questionList.map { mixed.keyOf(it) }.toSet()

        assertEquals(listOf(playable), reviewQuestions(mixed, oldWrongKeys))
    }

    @Test fun `套卷听力标签区分全缺 部分缺和未下载`() {
        val listening = bank("cet4-2025-06-1-listening")
        val reading = bank("cet4-2025-06-1-reading")
        val mixed = listening.copy(questions = listening.questionList.mapIndexed { index, question ->
            if (index == 0) question.copy(audioUrl = "https://example.org/audio.mp3") else question
        })
        val pending = listening.copy(questions = null, remoteBytes = 100)

        assertEquals("原音暂缺", examBankGroups(listOf(reading, listening)).single().listeningBank?.missingAudioLabel)
        assertEquals("1 题缺原音", mixed.missingAudioLabel)
        assertNull(pending.missingAudioLabel)
        assertNull(reading.missingAudioLabel)
    }

    @Test fun `只有原音直链显示原音联网提示`() {
        val bank = bank("cet4-2025-06-1-listening")
        val narration = bank.questionList.first().copy(audioText = "A short recording.")
        val withNarration = bank.copy(questions = listOf(narration))
        val withOriginal = bank.copy(questions = listOf(narration.copy(audioUrl = "https://example.org/audio.mp3", audioText = null)))

        assertFalse(withNarration.hasOriginalAudio)
        assertTrue(withOriginal.hasOriginalAudio)
        assertNull(withOriginal.missingAudioLabel)
    }

    @Test fun `订正后本轮列表固定便于查看解析`() {
        val bank = bank("cet4-2026-06-1-reading")
        val snapshot = setOf("${bank.id}:q1")
        assertEquals("q1", reviewQuestions(bank, snapshot).single().id)
        // 下一轮重新读取已订正后的空错题集，才清除本题。
        assertTrue(reviewQuestions(bank, emptySet()).isEmpty())
        assertEquals("q1", reviewQuestions(bank, snapshot).single().id)
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
