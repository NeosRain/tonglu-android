package cn.tonglu.app.data

import cn.tonglu.app.data.listening.PracticeMode
import cn.tonglu.app.data.listening.QuestionAttempt
import cn.tonglu.app.data.question.BankQuestion
import cn.tonglu.app.data.question.QuestionBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习计划：生成器（docs/plan/56 §6.2/§6.3）+ EF06 完成诚实性（2026-10-18 收口）。
 *
 * 本文件在 EF06 收口时重写：旧版用例还停留在「模板合成通用任务」的年代，
 * 与 0887d20 之后的真实题库模板签名不一致（测试源集无法编译）。现在同时锁死：
 *  - 模板只生成带真实 bankId / 真实题数的可执行任务；
 *  - AI 只做白名单选择题，清单外的行丢弃；
 *  - **完成度一律从真实作答现算**：历史手勾的题库任务没有基准证据时只标「待核对」，
 *    不计入计划完成度，也不改写任何真实作答记录。
 */
class PlanDraftTest {

    private fun bank(id: String, title: String, questions: Int = 1) = QuestionBank(
        id = id, title = title, sourceNote = "平台自建练习题，不是任何考试的真题", version = 1,
        questions = (1..questions).map { n ->
            BankQuestion("q$n", "single-choice", "第 $n 题", listOf("a", "b"), 0, "解析")
        },
    )

    private fun attempt(key: String, at: Long, correct: Boolean = false) = QuestionAttempt(
        questionKey = key, answeredAt = at, correct = correct, mode = PracticeMode.QUIZ,
    )

    private fun task(
        id: String = "t",
        bankId: String? = "b1",
        count: Int = 2,
        status: String = PlanTask.PENDING,
        baseline: Long? = null,
    ) = PlanTask(
        id = id, kind = "practice", title = "做题", resourceRef = "题组",
        bankId = bankId, questionCount = count, status = status, baselineAt = baseline,
    )

    private fun plan(vararg tasks: PlanTask) = LearningPlan(
        id = "plan-1", subjectId = "english", goal = "推进题组", level = "",
        active = true, tasks = tasks.toList(),
    )

    // ------------------------------------------------------------ template（离线路径）

    @Test
    fun `模板只生成带真实题组 id 与题数的可执行任务`() {
        val plan = PlanDraft.template("CET4 核心词", listOf(bank("b1", "题组 A", 4)))
        assertEquals("三天各一条练习任务", 3, plan.tasks.size)
        assertTrue("每条任务都必须有真实题组 id", plan.tasks.all { it.bankId == "b1" })
        assertEquals("题数来自真实题组", listOf(4, 4, 4), plan.tasks.map { it.questionCount })
        assertTrue("任务标题带上真实题组名与题数", plan.tasks.all { it.title.contains("题组 A") && it.title.contains("4 题") })
        assertTrue("完成规则必须可执行", plan.tasks.all { it.successRule.contains("不同题目") })
    }

    @Test
    fun `模板生成的计划一律是草稿 未确认不生效`() {
        val plan = PlanDraft.template("CET4 核心词", listOf(bank("b1", "题组 A")))
        assertFalse("生成只产出 draft，active 必须为 false", plan.active)
        assertTrue("所有任务初始都是 pending", plan.tasks.all { it.status == PlanTask.PENDING })
        assertFalse("没有任何任务一生成就算完成", plan.tasks.any { it.isDone() })
        assertTrue("草稿任务没有确认基准", plan.tasks.all { it.baselineAt == null })
    }

    @Test
    fun `本机没有可做题组时不再合成点不进去的占位任务`() {
        val plan = PlanDraft.template(null, emptyList())
        assertTrue("没有真实资源就不输出任务卡", plan.tasks.isEmpty())
        assertEquals("每天推进一组练习", plan.goal)
    }

    @Test
    fun `没有词书时目标文字不出现空的书名号`() {
        val blank = PlanDraft.template("   ", listOf(bank("b1", "题组 A")))
        assertEquals("空白书名同样按没有处理", "每天推进一组练习", blank.goal)
        assertEquals("有书名时用真实书名", "推进《CET4 核心词》",
            PlanDraft.template("CET4 核心词", listOf(bank("b1", "题组 A"))).goal)
    }

    @Test
    fun `错题任务只在该题组真有错题时生成`() {
        val books = listOf(bank("b1", "题组 A", 4))
        val without = PlanDraft.template("CET4", books)
        assertTrue("没有错题就不生成复习任务", without.tasks.none { it.kind == "review" })
        val withWrong = PlanDraft.template("CET4", books, wrongCounts = mapOf("b1" to 2))
        val review = withWrong.tasks.filter { it.kind == "review" }
        assertEquals("错题任务同样逐天轮换", 3, review.size)
        assertTrue("错题数量来自真实错题键", review.all { it.bankId == "b1" && it.questionCount == 2 })
    }

    @Test
    fun `每个任务都写了完成规则 用户能知道系统怎么判完成`() {
        val plan = PlanDraft.template("CET4 核心词", listOf(bank("b1", "题组 A")))
        assertTrue("successRule 不能空 —— 否则用户会问「系统怎么知道我完成了」",
            plan.tasks.all { it.successRule.isNotBlank() })
    }

    // ------------------------------------------------------------ fromAi（AI 只做白名单选择题）

    @Test
    fun `AI 只能从本机题组清单里挑任务 清单外的 id 直接丢弃`() {
        val books = listOf(bank("b1", "题组 A", 4), bank("b2", "题组 B", 2))
        val plan = PlanDraft.fromAi("b1|先做这组\nb-ghost|不存在的题组", books) { error("不该回退") }
        assertEquals("清单外的行不能变成任务", 1, plan.tasks.size)
        assertEquals("b1", plan.tasks.first().bankId)
        assertEquals("题数来自真实题组", 4, plan.tasks.first().questionCount)
        assertTrue("理由作为推荐说明保留", plan.tasks.first().successRule.contains("先做这组"))
    }

    @Test
    fun `全角分隔符也能解析出推荐理由`() {
        val books = listOf(bank("b1", "题组 A"))
        val plan = PlanDraft.fromAi("b1｜全角理由", books) { error("不该走回退") }
        assertEquals("全角分隔符不能把整行丢掉", 1, plan.tasks.size)
        assertEquals("b1", plan.tasks.first().bankId)
        assertTrue("全角分隔符后的理由必须保留", plan.tasks.first().successRule.contains("全角理由"))
    }

    @Test
    fun `AI 建议全部无效时回退离线模板而不是给空壳`() {
        val books = listOf(bank("b1", "题组 A"))
        var fell = false
        val plan = PlanDraft.fromAi("b-ghost|瞎编\n随便写写", books) {
            fell = true; PlanDraft.template("CET4", books)
        }
        assertTrue("没有任何有效 id 必须回退", fell)
        assertEquals("回退的是真实模板", 3, plan.tasks.size)
    }

    @Test
    fun `AI 建议解析出的一律是草稿`() {
        val books = listOf(bank("b1", "题组 A"))
        val plan = PlanDraft.fromAi("b1|理由", books) { error("不该走回退") }
        assertFalse("AI 建议不能自动生效", plan.active)
        assertTrue("任务仍是 pending", plan.tasks.all { it.status == PlanTask.PENDING })
    }

    @Test
    fun `AI 建议最多挑 6 组 且同组去重`() {
        val books = (1..8).map { bank("b$it", "题组 $it") }
        val text = (1..8).joinToString("\n") { "b$it|理由 $it" } + "\nb1|重复"
        val plan = PlanDraft.fromAi(text, books) { error("不该走回退") }
        assertEquals("上限 6 组", 6, plan.tasks.size)
        assertEquals("同一题组不重复出现", 6, plan.tasks.map { it.bankId }.distinct().size)
    }

    @Test
    fun `发给 AI 的提示词带真实清单和严格输出格式`() {
        val prompt = PlanDraft.aiPrompt(listOf(bank("b1", "题组 A", 4)))
        assertTrue("清单里必须出现真实 id", prompt.contains("b1"))
        assertTrue("清单里必须出现真实标题", prompt.contains("题组 A"))
        assertTrue("清单里必须出现真实题数", prompt.contains("4题"))
        assertTrue("输出格式必须是 题组ID|理由", prompt.contains("题组ID|一句推荐理由"))
        assertTrue("必须明确只准用清单", prompt.contains("只准使用上面清单里的 ID"))
    }

    // ------------------------------------------------------------ 完成证据：真实作答现算

    @Test
    fun `刚生成的计划进度是零而不是满`() {
        val plan = plan(task(bankId = "b1", count = 2, baseline = 1_000L))
        assertEquals("生成成功 ≠ 完成（§6.3 验收）", "0/1", plan.progressText(emptyList(), listOf(bank("b1", "题组 A", 2))))
    }

    @Test
    fun `基准之后不同题目作答达标才算完成`() {
        val task = task(status = PlanTask.DONE, baseline = 1_000L)
        val attempts = listOf(
            attempt("b1:q1", 1_001L),
            attempt("b1:q2", 1_002L),
            attempt("b1:q1", 1_003L),
        )
        assertEquals("重答同一题只算一道", 2, task.answeredDistinctSinceBaseline(attempts))
        assertEquals(PlanTaskProof.VERIFIED, task.proof(attempts, listOf(bank("b1", "题组 A", 2))))
        val plan = plan(task)
        assertEquals(1, plan.doneCount(attempts))
        assertEquals("1/1", plan.progressText(attempts))
        assertEquals(0, plan.unverifiedDoneCount(attempts))
    }

    @Test
    fun `基准之前的作答不能算进计划完成`() {
        val task = task(status = PlanTask.DONE, baseline = 1_000L)
        val attempts = listOf(attempt("b1:q1", 999L), attempt("b1:q2", 1_000L))
        assertEquals("基准前一条都不算", 1, task.answeredDistinctSinceBaseline(attempts))
        assertEquals("只差一道也不算完成", PlanTaskProof.PENDING, task.proof(attempts, listOf(bank("b1", "题组 A", 3))))
        assertEquals("历史 DONE 不计入完成", 0, plan(task).doneCount(attempts))
    }

    @Test
    fun `历史题库任务没有确认基准时手勾 DONE 只显示待核对且不计完成`() {
        val task = task(status = PlanTask.DONE, baseline = null)
        assertEquals(PlanTaskProof.UNVERIFIED, task.proof(emptyList(), listOf(bank("b1", "题组 A", 2))))
        val plan = plan(task)
        assertEquals("不可证明的 DONE 不能算真实进度", 0, plan.doneCount(emptyList()))
        assertEquals("但要告诉用户有一条待核对", 1, plan.unverifiedDoneCount(emptyList()))
        assertEquals("0/1 完成", "0/1", plan.progressText(emptyList()))
    }

    @Test
    fun `有基准但作答不足时 历史 DONE 也按未完成重算`() {
        val task = task(status = PlanTask.DONE, baseline = 1_000L)
        val attempts = listOf(attempt("b1:q1", 1_001L))
        assertEquals(PlanTaskProof.PENDING, task.proof(attempts, listOf(bank("b1", "题组 A", 2))))
        assertEquals(0, plan(task).doneCount(attempts))
        assertEquals("这条不是「待核对」，是明确未达标", 0, plan(task).unverifiedDoneCount(attempts))
    }

    @Test
    fun `其他题组的作答不会串进本任务的完成度`() {
        val task = task(status = PlanTask.PENDING, baseline = 1_000L)
        val attempts = listOf(attempt("b2:q1", 1_001L), attempt("b2:q2", 1_002L), attempt("legacy:x:q1", 1_003L))
        assertEquals(0, task.answeredDistinctSinceBaseline(attempts))
        assertEquals(PlanTaskProof.PENDING, task.proof(attempts, listOf(bank("b1", "题组 A", 2))))
    }

    @Test
    fun `任务缺题数时用本机题组真实题数兜底`() {
        val task = task(count = 0, baseline = 1_000L)
        val attempts = listOf(attempt("b1:q1", 1_001L), attempt("b1:q2", 1_002L))
        val books = listOf(bank("b1", "题组 A", 2))
        assertEquals(2, task.requiredQuestionCount(books))
        assertEquals(PlanTaskProof.VERIFIED, task.proof(attempts, books))
    }

    @Test
    fun `题组不在本机又缺题数时 无法证明完成`() {
        val task = task(count = 0, status = PlanTask.DONE, baseline = 1_000L)
        assertEquals(0, task.requiredQuestionCount(emptyList()))
        assertEquals(PlanTaskProof.UNVERIFIED, task.proof(emptyList(), emptyList()))
    }

    @Test
    fun `无题组的自报任务保留手动勾选 但标记为手动`() {
        val done = task(bankId = null, status = PlanTask.DONE)
        assertEquals(PlanTaskProof.MANUAL, done.proof(emptyList()))
        assertTrue("手动勾选仍计入计划进度", done.proof(emptyList()).countsAsProgress)
        val skipped = task(bankId = null, status = PlanTask.SKIPPED)
        assertEquals("skipped 不算完成", PlanTaskProof.PENDING, skipped.proof(emptyList()))
        assertEquals(0, plan(skipped).doneCount(emptyList()))
    }

    @Test
    fun `计划完成度只统计可证明的完成`() {
        val verified = task(id = "a", bankId = "b1", count = 2, status = PlanTask.DONE, baseline = 1_000L)
        val unverified = task(id = "b", bankId = "b2", count = 2, status = PlanTask.DONE, baseline = null)
        val manual = task(id = "c", bankId = null, status = PlanTask.DONE)
        val attempts = listOf(attempt("b1:q1", 1_001L), attempt("b1:q2", 1_002L))
        val plan = plan(verified, unverified, manual)
        assertEquals("真实作答 1 项 + 手动 1 项；历史不可证明 DONE 不算", 2, plan.doneCount(attempts))
        assertEquals("2/3", plan.progressText(attempts))
        assertEquals(1, plan.unverifiedDoneCount(attempts))
    }

    @Test
    fun `重算完成度不会改动真实作答记录`() {
        val attempts = listOf(attempt("b1:q1", 1_001L, correct = true))
        val before = attempts.toList()
        task(status = PlanTask.DONE, baseline = 1_000L).proof(attempts, listOf(bank("b1", "题组 A", 2)))
        plan(task(status = PlanTask.DONE, baseline = 1_000L)).progressText(attempts)
        assertEquals("只读重算，作答记录一条不改", before, attempts)
    }

    // ------------------------------------------------------------ 资源引用校验

    @Test
    fun `题组不存在时如实报告任务引用失效`() {
        val task = PlanTask(id = "t", kind = "practice", title = "做题", resourceRef = "已被下架的题组")
        assertFalse(task.resolvesIn(listOf(bank("b1", "在的题组"))))
        assertTrue(task.resolvesIn(listOf(bank("b1", "已被下架的题组"))))
    }

    @Test
    fun `没有资源引用的任务永远视为成立`() {
        val task = PlanTask(id = "t", kind = "review", title = "复习错题", resourceRef = null)
        assertTrue(task.resolvesIn(emptyList()))
    }

    @Test
    fun `题组按 id 匹配也算成立`() {
        val task = PlanTask(id = "t", kind = "practice", title = "做题", resourceRef = "bank-42")
        assertTrue(task.resolvesIn(listOf(bank("bank-42", "名字对不上也没关系"))))
    }

    // ------------------------------------------------------------ 重生成不叠加旧建议

    /**
     * 走查实证的缺陷（2026-10-05）：连点两次「生成 3 天计划」原本会留下**两条标题一模一样**
     * 的未确认建议，用户分不清该确认哪一条。修法是「新的 draft 替换旧的 draft，已确认的保留」。
     */
    @Test
    fun `重生成只保留最新一份未确认建议`() {
        fun apply(existing: List<LearningPlan>, draft: LearningPlan) =
            listOf(draft) + existing.filter { it.active }

        val books = listOf(bank("b1", "题组 A"))
        val draft1 = PlanDraft.template("CET4", books)
        val draft2 = PlanDraft.template("CET4", books).copy(id = "plan-2")
        val after1 = apply(emptyList(), draft1)
        assertEquals(1, after1.size)
        val after2 = apply(after1, draft2)
        assertEquals("连点两次不应留下两条未确认建议", 1, after2.size)
        assertEquals("plan-2", after2.first().id)
        assertNotEquals("两条建议不是同一个对象", draft1.id, draft2.id)
    }

    @Test
    fun `重生成不会动已确认的计划`() {
        fun apply(existing: List<LearningPlan>, draft: LearningPlan) =
            listOf(draft) + existing.filter { it.active }

        val books = listOf(bank("b1", "题组 A"))
        val active = PlanDraft.template("CET4", books).copy(id = "plan-active", active = true)
        val draftA = PlanDraft.template("CET4", books).copy(id = "draft-1")
        val draftB = PlanDraft.template("CET4", books).copy(id = "draft-2")
        val after = apply(apply(listOf(active), draftA), draftB)
        assertEquals("已确认的既有安排必须保留", listOf("draft-2", "plan-active"), after.map { it.id })
        assertTrue("已确认那条仍然是 active", after.first { it.id == "plan-active" }.active)
    }
}
