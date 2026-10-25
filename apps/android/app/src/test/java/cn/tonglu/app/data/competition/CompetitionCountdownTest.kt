package cn.tonglu.app.data.competition

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竞赛期限的倒计时与净化（`docs/plan/56` §2.3）。
 *
 * ## 为什么必须有这层测试
 * 规划里三条硬规矩 —— 「未知期限不提醒」「DATE 不补 23:59」「时区固定 UTC+8」——
 * 靠 UI 走查**验不出来**：界面上「当日截止，时间以原文为准」和「23:59 截止」
 * 看起来都很正常，跨时区差一天更是等不到。只有确定性输入能锁住。
 *
 * 所有用例都用固定时刻当「现在」，不依赖跑测试的机器时间。
 */
class CompetitionCountdownTest {

    private fun at(zone: String, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear(); set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    /** 北京时间 2026-10-05 00:30 —— UTC 还是 10-04 16:30，卡在日界两侧 */
    private val beijingEarlyMorning = at("Asia/Shanghai", 2026, 10, 5, 0, 30)

    private fun deadline(
        id: String = "d1",
        date: String? = "2026-10-20",
        precision: String = CompetitionPrecision.DATE,
        at: String? = null,
        verification: String = CompetitionVerification.VERIFIED,
    ) = CompetitionDeadline(
        id = id, kind = CompetitionKind.REGISTRATION, precision = precision,
        verificationStatus = verification,
        date = date, deadlineAt = at, deadlineDate = if (precision == CompetitionPrecision.DATE) date else null,
        display = if (precision == CompetitionPrecision.DATETIME) (at ?: "") else "当日截止，时间以原文为准",
        countable = verification == CompetitionVerification.VERIFIED,
        sourceExcerpt = if (verification == CompetitionVerification.VERIFIED) "原文摘录" else null,
        revision = 1,
    )

    // ── 规矩 2：DATE 精度不补时刻 ────────────────────────────────────────

    @Test
    fun `DATE 精度只认 deadlineDate，不生成任何时刻`() {
        val d = deadline(date = "2026-10-20", precision = CompetitionPrecision.DATE)
        assertEquals("2026-10-20", CompetitionCountdown.dateOf(d))
        assertNull("DATE 不该有 deadlineAt", d.deadlineAt)
        assertEquals("当日截止，时间以原文为准", d.display)
    }

    @Test
    fun `DATE 的界面上没有任何 23_59 的痕迹`() {
        val d = deadline(date = "2026-10-20", precision = CompetitionPrecision.DATE)
        val shown = listOfNotNull(d.display, d.date, d.deadlineAt, d.deadlineDate).joinToString(" ")
        assertFalse("不能出现 23:59", shown.contains("23:59"))
        assertFalse("不能出现 23：59", shown.contains("23：59"))
    }

    // ── 规矩 1：未知期限不倒计时 ────────────────────────────────────────

    @Test
    fun `未核实的期限即使有日期也不算天数`() {
        val d = deadline(date = "2026-10-20", verification = CompetitionVerification.UNCONFIRMED)
        val r = CompetitionCountdown.of(d, beijingEarlyMorning)
        assertTrue("未核实必须标 unknown", r.unknown)
        assertEquals("日期待核实", r.label)
        assertFalse("未核实不能算成临近", r.urgent)
    }

    @Test
    fun `没有日期的期限不算天数`() {
        val d = deadline(date = null, precision = CompetitionPrecision.DATE).copy(countable = false)
        val r = CompetitionCountdown.of(d, beijingEarlyMorning)
        assertTrue(r.unknown)
        assertNull(CompetitionCountdown.dateOf(d))
    }

    @Test
    fun `已核实且有日期才算得出天数`() {
        val d = deadline(date = "2026-10-20", verification = CompetitionVerification.VERIFIED)
        val r = CompetitionCountdown.of(d, beijingEarlyMorning)
        assertFalse(r.unknown)
        // 10-05 → 10-20 = 15 天
        assertEquals(15, r.days)
        assertEquals("还有 15 天", r.label)
        assertFalse(r.urgent)
    }

    // ── 规矩 3：时区固定 UTC+8 ──────────────────────────────────────────

    @Test
    fun `今天截止说的是北京时间那一天`() {
        val d = deadline(date = "2026-10-05")
        val r = CompetitionCountdown.of(d, beijingEarlyMorning)
        assertEquals(CompetitionCountdown.Phase.TODAY, r.phase)
        assertEquals("今天截止", r.label)
    }

    @Test
    fun `UTC 的 ISO 时刻要按东八区折算日界，不能截前 10 位`() {
        // 北京时间 2026-10-21 00:00 == UTC 2026-10-20 16:00
        assertEquals("2026-10-21", CompetitionCountdown.isoToLocalDate("2026-10-20T16:00:00.000Z"))
        // 北京时间 2026-10-20 23:00 == UTC 2026-10-20 15:00 —— 这一天不跨
        assertEquals("2026-10-20", CompetitionCountdown.isoToLocalDate("2026-10-20T15:00:00.000Z"))
        // UTC 未过日界的一早：UTC 10-20 07:00 == 北京 15:00，还是 10-20
        assertEquals("2026-10-20", CompetitionCountdown.isoToLocalDate("2026-10-20T07:00:00Z"))
    }

    @Test
    fun `带偏移的 ISO 也按东八区折算`() {
        // +08:00 写的就是北京时间，原样
        assertEquals("2026-10-20", CompetitionCountdown.isoToLocalDate("2026-10-20T23:00:00+08:00"))
        // -05:00 的 2026-10-20 20:00 = UTC 10-21 01:00 = 北京 10-21 09:00
        assertEquals("2026-10-21", CompetitionCountdown.isoToLocalDate("2026-10-20T20:00:00-05:00"))
    }

    @Test
    fun `解析不出来的时刻返回 null 而不是硬编一个日期`() {
        assertNull(CompetitionCountdown.isoToLocalDate(""))
        assertNull(CompetitionCountdown.isoToLocalDate("不是日期"))
        assertNull(CompetitionCountdown.isoToLocalDate("2026-13-45T00:00:00Z"))
    }

    // ── 已过 / 临近 / 跨年 ──────────────────────────────────────────────

    @Test
    fun `已经过去的期限标已过，且不算临近`() {
        val d = deadline(date = "2026-10-01")
        val r = CompetitionCountdown.of(d, beijingEarlyMorning)
        assertEquals(CompetitionCountdown.Phase.ENDED, r.phase)
        assertEquals("已过", r.label)
        assertFalse(r.urgent)
    }

    @Test
    fun `七天以内算临近`() {
        val d = deadline(date = "2026-10-12")
        val r = CompetitionCountdown.of(d, beijingEarlyMorning)
        assertTrue("10-05 到 10-12 是 7 天，该算临近", r.urgent)
    }

    @Test
    fun `跨年也算得对`() {
        val d = deadline(date = "2027-01-05")
        val r = CompetitionCountdown.of(d, beijingEarlyMorning)
        // 2026-10-05 → 2027-01-05 = 27 + 30 + 31 + 5 = 92 天
        assertEquals(92, r.days)
    }

    // ── 远端净化 ────────────────────────────────────────────────────────

    @Test
    fun `未核实且无日期的期限在净化时被丢掉`() {
        val catalog = RemoteCompetitionCatalog(
            items = listOf(
                RemoteCompetition(
                    id = "c1", title = "有日期的赛事",
                    deadlines = listOf(RemoteCompetitionDeadline(id = "d1", kind = "REGISTRATION", deadlineDate = "2026-11-01")),
                ),
                RemoteCompetition(
                    id = "c2", title = "期限没日期的赛事",
                    deadlines = listOf(RemoteCompetitionDeadline(id = "d2", kind = "REGISTRATION")),
                ),
            ),
        )
        val items = catalog.toCatalog()
        assertEquals("保留赛事公告，丢掉无效期限", 2, items.size)
        assertEquals("c1", items[0].id)
        assertTrue(items[1].deadlines.isEmpty())
    }

    @Test
    fun `缺 id 或标题的赛事被丢掉`() {
        val catalog = RemoteCompetitionCatalog(
            items = listOf(
                RemoteCompetition(id = null, title = "没 id", deadlines = listOf(RemoteCompetitionDeadline(id = "d1", deadlineDate = "2026-11-01"))),
                RemoteCompetition(id = "c1", title = null, deadlines = listOf(RemoteCompetitionDeadline(id = "d2", deadlineDate = "2026-11-01"))),
            ),
        )
        assertTrue(catalog.toCatalog().isEmpty())
    }

    @Test
    fun `期限按报名在前比赛结束在后排序`() {
        val catalog = RemoteCompetitionCatalog(
            items = listOf(
                RemoteCompetition(id = "c1", title = "赛事", deadlines = listOf(
                    RemoteCompetitionDeadline(id = "d1", kind = "COMPETITION_END", deadlineDate = "2026-12-01"),
                    RemoteCompetitionDeadline(id = "d2", kind = "REGISTRATION", deadlineDate = "2026-11-01"),
                    RemoteCompetitionDeadline(id = "d3", kind = "WORK_SUBMIT", deadlineDate = "2026-11-20"),
                )),
            ),
        )
        val kinds = catalog.toCatalog()[0].deadlines.map { it.kind }
        assertEquals(listOf("REGISTRATION", "WORK_SUBMIT", "COMPETITION_END"), kinds)
    }

    @Test
    fun `countable 以服务端为准，不被本地重算覆盖`() {
        val d = RemoteCompetitionDeadline(
            id = "d1", kind = "REGISTRATION", precision = "DATE", deadlineDate = "2026-11-01",
            verificationStatus = "VERIFIED", countable = false, // 服务端说不能倒计时
        )
        val clean = RemoteCompetitionCatalog(items = listOf(RemoteCompetition(id = "c1", title = "x", deadlines = listOf(d))))
            .toCatalog()[0].deadlines[0]
        assertFalse("服务端说 false 就得是 false", clean.countable)
        assertTrue(CompetitionCountdown.of(clean, beijingEarlyMorning).unknown)
    }

    @Test
    fun `订阅项缺 deadlineId 或 eventId 被丢掉`() {
        val list = RemoteSubscriptionList(items = listOf(
            RemoteSubscription(eventId = "c1", deadlineId = null, title = "x"),
            RemoteSubscription(eventId = null, deadlineId = "d1", title = "y"),
            RemoteSubscription(eventId = "c1", deadlineId = "d1", title = "好的"),
        ))
        val cleaned = list.items.orEmpty().mapNotNull { it.toSubscription() }
        assertEquals(1, cleaned.size)
        assertEquals("好的", cleaned[0].title)
    }

    @Test
    fun `nextDeadline 取还没过期的最近一条`() {
        val competition = Competition(
            id = "c1", title = "赛事", organizer = null, academicYear = null, sourceUrl = null,
            revision = 1, verifiedAt = null,
            deadlines = listOf(
                deadline(id = "d1", date = "2026-10-01"),   // 已过
                deadline(id = "d2", date = "2026-10-20"),   // 最近
                deadline(id = "d3", date = "2026-11-20"),
            ),
        )
        assertEquals("d2", competition.nextDeadline(beijingEarlyMorning)?.id)
    }

    @Test
    fun `全部过期时 nextDeadline 给最早那条而不是 null`() {
        val competition = Competition(
            id = "c1", title = "赛事", organizer = null, academicYear = null, sourceUrl = null,
            revision = 1, verifiedAt = null,
            deadlines = listOf(deadline(id = "d1", date = "2026-09-01"), deadline(id = "d2", date = "2026-10-01")),
        )
        assertNotNull(competition.nextDeadline(beijingEarlyMorning))
    }
    @Test
    fun `只有比赛结束日期不能当成可报名`() {
        val competition = RemoteCompetitionCatalog(items = listOf(RemoteCompetition(
            id = "c1", title = "仅有比赛结束日期",
            deadlines = listOf(RemoteCompetitionDeadline(id = "d1", kind = "COMPETITION_END",
                deadlineDate = "2026-11-01", verificationStatus = "VERIFIED")),
        ))).toCatalog().single()
        assertFalse(competitionOpenForRegistration(competition, beijingEarlyMorning))
    }

    @Test
    fun `已核实且尚未截止的报名可展示`() {
        val competition = RemoteCompetitionCatalog(items = listOf(RemoteCompetition(
            id = "c1", title = "报名中",
            deadlines = listOf(RemoteCompetitionDeadline(id = "d1", kind = "REGISTRATION",
                deadlineDate = "2026-11-01", verificationStatus = "VERIFIED")),
        ))).toCatalog().single()
        assertTrue(competitionOpenForRegistration(competition, beijingEarlyMorning))
        assertFalse(competitionOpenForRegistration(competition.copy(deadlines = emptyList()), beijingEarlyMorning))
        assertFalse(competitionOpenForRegistration(competition.copy(deadlines = listOf(
            competition.deadlines.single().copy(countable = false))), beijingEarlyMorning))
    }

    @Test
    fun `没有任何期限仍保留真实赛事标题`() {
        val competition = RemoteCompetitionCatalog(items = listOf(RemoteCompetition(
            id = "c1", title = "公告已发布但报名安排未定",
        ))).toCatalog().single()
        assertEquals("公告已发布但报名安排未定", competition.title)
        assertTrue(competition.deadlines.isEmpty())
        assertFalse(competitionOpenForRegistration(competition, beijingEarlyMorning))
    }

}
