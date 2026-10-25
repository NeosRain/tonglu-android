package cn.tonglu.app.widget

import cn.tonglu.app.data.competition.CompetitionDeadline
import cn.tonglu.app.data.competition.CompetitionKind
import cn.tonglu.app.data.competition.CompetitionPrecision
import cn.tonglu.app.data.competition.CompetitionVerification
import cn.tonglu.app.data.competition.Subscription
import cn.tonglu.app.data.exam.ExamCategory
import cn.tonglu.app.data.exam.ExamCountdown
import cn.tonglu.app.data.exam.ExamDateSource
import cn.tonglu.app.data.exam.ExamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TodayWidgetDeadlines] 的单测 —— 钉住「小部件上显示哪几条倒计时、写什么字」。
 *
 * ## 为什么必须测
 *
 * 小部件没有 UI 可点、没有 dump 可读，走查只能靠肉眼截图；而这里的边界特别多：
 * 「今天截止」「日期待核实」「已结束」「没置顶」「取消订阅」「两类互相挤」。
 * 靠走查一个个造数据是不可能覆盖的，所以口径全部固化成固定时间戳的用例。
 *
 * ## 两条最容易写错的（都在下面有专门用例）
 *
 * 1. **时区**：国家考证与竞赛都按**北京时间**日界算「今天」，不是设备时区。
 *    所以「今天」这个基准值用 [beijingNoon] 造 —— 它落在北京时间的正午，
 *    无论按哪种日界算都是同一天，不会在 UTC 日界附近抖。
 * 2. **未知期限不倒计时**：`CompetitionCountdown` 对日期没核实的返回
 *    `unknown = true` + 「日期待核实」。小部件格子很贵，**这类必须被挡在外面** ——
 *    放进去就是把一节课的位置换成一个没有数字的标签。
 */
class TodayWidgetDeadlinesTest {

    private val day = 24L * 3600 * 1000

    /** 北京时间某天正午的 epoch 毫秒 —— 「今天」的固定基准，避开日界抖动。 */
    private fun beijingNoon(date: String): Long =
        ExamCountdown.dateToEpochDay(date)!! * day + 4L * 3600 * 1000

    private fun exam(
        id: String,
        title: String,
        date: String,
        pinned: Boolean = true,
        dateSource: String = ExamDateSource.USER,
    ) = ExamEvent(
        id = id,
        title = title,
        date = date,
        category = ExamCategory.NATIONAL,
        pinned = pinned,
        dateSource = dateSource,
    )

    private fun deadline(
        id: String,
        date: String,
        countable: Boolean = true,
        kind: String = CompetitionKind.REGISTRATION,
    ) = CompetitionDeadline(
        id = id,
        kind = kind,
        precision = CompetitionPrecision.DATE,
        verificationStatus = CompetitionVerification.VERIFIED,
        date = date,
        deadlineAt = null,
        deadlineDate = date,
        display = "当日截止，时间以原文为准",
        countable = countable,
        sourceExcerpt = null,
        revision = 1,
    )

    private fun sub(
        id: String,
        title: String,
        deadline: CompetitionDeadline?,
        enabled: Boolean = true,
        invalidated: Boolean = false,
    ) = Subscription(
        id = id,
        eventId = "event-$id",
        deadlineId = deadline?.id ?: "deadline-$id",
        title = title,
        organizer = null,
        sourceUrl = null,
        invalidated = invalidated,
        changed = false,
        enabled = enabled,
        reminderPolicy = "3d",
        deadline = deadline,
    )

    private fun pick(
        exams: List<ExamEvent> = emptyList(),
        examsEnabled: Boolean = true,
        subs: List<Subscription> = emptyList(),
        now: String = "2026-10-16",
    ) = TodayWidgetDeadlines.pick(exams, examsEnabled, subs, beijingNoon(now))

    // ── 考试 ─────────────────────────────────────────────────────────────

    @Test
    fun `考试 - 天数与文案直接用 ExamCountdown 的口径`() {
        val items = pick(exams = listOf(exam("e1", "六级笔试", "2026-10-28")))
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals(TodayWidgetDeadlines.Item.Kind.EXAM, item.kind)
        assertEquals("考试", item.kind.tag)
        assertEquals("六级笔试", item.title)
        // 10-16 → 10-28 = 12 天。文案必须是 ExamCountdown 自己产出的那一句。
        assertEquals("还有 12 天", item.label)
        assertEquals(12, item.days)
        assertTrue("12 天不算临近", !item.urgent)
    }

    @Test
    fun `考试 - 今天考试不说还有 0 天`() {
        val items = pick(exams = listOf(exam("e1", "四级笔试", "2026-10-16")))
        assertEquals("今天考试", items.first().label)
        assertEquals(0, items.first().days)
        // 当天**不算**「临近」：ExamCountdown 的 urgent 只覆盖 UPCOMING（还没到），
        // 当天已经由文案「今天考试」本身表达到位，再加一个「临近」是重复的。
        // （第一版这里我断言了 true —— 是判据写错，不是生产代码错。）
        assertTrue("当天不是 UPCOMING，urgent 应为 false", !items.first().urgent)
    }

    @Test
    fun `考试 - 日期待核实的文案带问号`() {
        val items = pick(
            exams = listOf(exam("e1", "教师资格", "2026-11-01", dateSource = ExamDateSource.RULE)),
        )
        assertEquals("还有 16 天?", items.first().label)
    }

    @Test
    fun `考试 - 已结束的不显示`() {
        // homeList 里已经排除 ENDED；这条用例是防止有人改成「直接从 events 取」。
        assertTrue(pick(exams = listOf(exam("e1", "上学期期末", "2026-10-01"))).isEmpty())
    }

    @Test
    fun `考试 - 没置顶备考的不显示`() {
        assertTrue(pick(exams = listOf(exam("e1", "六级笔试", "2026-10-28", pinned = false))).isEmpty())
    }

    // ── 竞赛 ─────────────────────────────────────────────────────────────

    @Test
    fun `竞赛 - 可倒计时的给出天数`() {
        val items = pick(subs = listOf(sub("s1", "蓝桥杯", deadline("d1", "2026-10-19"))))
        assertEquals(1, items.size)
        assertEquals(TodayWidgetDeadlines.Item.Kind.COMPETITION, items.first().kind)
        assertEquals("竞赛", items.first().kind.tag)
        assertEquals("蓝桥杯", items.first().title)
        assertEquals("还有 3 天", items.first().label)
        assertTrue("3 天属于临近", items.first().urgent)
    }

    @Test
    fun `竞赛 - 日期待核实的一条都不进来`() {
        // countable=false → CompetitionCountdown.of 返回 unknown → 必须被挡掉。
        val items = pick(subs = listOf(sub("s1", "某比赛", deadline("d1", "2026-10-19", countable = false))))
        assertTrue("未知期限不能占小部件的位置", items.isEmpty())
    }

    @Test
    fun `竞赛 - 已过的不显示`() {
        assertTrue(pick(subs = listOf(sub("s1", "老比赛", deadline("d1", "2026-10-10")))).isEmpty())
    }

    @Test
    fun `竞赛 - 取消订阅与失效的不显示`() {
        val off = sub("s1", "已取消", deadline("d1", "2026-10-19"), enabled = false)
        val broken = sub("s2", "已失效", deadline("d2", "2026-10-20"), invalidated = true)
        assertTrue(pick(subs = listOf(off, broken)).isEmpty())
    }

    @Test
    fun `竞赛 - 没有期限字段的不显示`() {
        assertTrue(pick(subs = listOf(sub("s1", "空壳", null))).isEmpty())
    }

    @Test
    fun `竞赛 - 同一场只占一条（报名与提交取最近的）`() {
        val items = pick(
            subs = listOf(
                sub("s1", "蓝桥杯", deadline("d1", "2026-11-20", kind = CompetitionKind.WORK_SUBMIT)),
                sub("s2", "蓝桥杯", deadline("d2", "2026-10-25", kind = CompetitionKind.REGISTRATION)),
            ),
        )
        assertEquals("同一场竞赛的两个期限只能占一条", 1, items.size)
        assertEquals("取最近的那个期限", "还有 9 天", items.first().label)
    }

    // ── 组合规则 ─────────────────────────────────────────────────────────

    @Test
    fun `两类都露脸 - 考试再多也不能挤掉竞赛`() {
        val items = pick(
            exams = listOf(
                exam("e1", "六级笔试", "2026-10-19"),
                exam("e2", "计算机二级", "2026-10-21"),
                exam("e3", "考研", "2026-12-20"),
            ),
            subs = listOf(sub("s1", "蓝桥杯", deadline("d1", "2026-11-05"))),
        )
        assertEquals("只显示 2 条", 2, items.size)
        assertEquals(
            "一条考试 + 一条竞赛",
            listOf(TodayWidgetDeadlines.Item.Kind.EXAM, TodayWidgetDeadlines.Item.Kind.COMPETITION),
            items.map { it.kind },
        )
        // 最近的考试在前（3 天 < 20 天）
        assertEquals("六级笔试", items[0].title)
        assertEquals("蓝桥杯", items[1].title)
    }

    @Test
    fun `只有一类时补第二条`() {
        val items = pick(
            exams = listOf(exam("e1", "六级笔试", "2026-10-19"), exam("e2", "计算机二级", "2026-10-21")),
        )
        assertEquals("没有竞赛就该把位置给第二场考试", 2, items.size)
        assertEquals(listOf("六级笔试", "计算机二级"), items.map { it.title })
    }

    @Test
    fun `按剩余天数升序`() {
        val items = pick(
            exams = listOf(exam("e1", "远的", "2026-12-20")),
            subs = listOf(sub("s1", "近的", deadline("d1", "2026-10-18"))),
        )
        assertEquals("越近越上面", listOf("近的", "远的"), items.map { it.title })
    }

    @Test
    fun `考试开关关掉 - 考试一条都不出，竞赛不受影响`() {
        val exams = listOf(exam("e1", "六级笔试", "2026-10-19"))
        val subs = listOf(sub("s1", "蓝桥杯", deadline("d1", "2026-10-25")))
        val on = pick(exams = exams, examsEnabled = true, subs = subs)
        val off = pick(exams = exams, examsEnabled = false, subs = subs)
        assertEquals(2, on.size)
        assertEquals("关掉后只剩竞赛", 1, off.size)
        assertEquals(TodayWidgetDeadlines.Item.Kind.COMPETITION, off.first().kind)
    }

    @Test
    fun `control_什么都不订阅时必须是空`() {
        // 对照：证明上面的「不显示」不是所有输入都返回空。
        assertTrue(pick().isEmpty())
        assertTrue("有数据时必须非空", pick(exams = listOf(exam("e1", "六级笔试", "2026-10-19"))).isNotEmpty())
    }
}
