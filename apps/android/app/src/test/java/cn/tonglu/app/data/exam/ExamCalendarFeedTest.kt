package cn.tonglu.app.data.exam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 国家考证年度表**上云**后的容错回归（#5b）。
 *
 * 这一层的风险不在算法，而在「服务端给回来的东西能不能用」：
 * 年度表一旦被换成坏的，用户看到的是空白日历页 —— 而且**本地缓存会一直留着坏数据**，
 * 每次开页面都失败一次。所以「不合法就返回 null（退回内置表）」必须被锁住。
 */
class ExamCalendarFeedTest {

    private fun entry(
        id: String = "a", title: String = "测试", date: String = "2026-12-12",
        group: String = "英语", endDate: String? = null, source: String? = "official",
    ) = RemoteExamEntry(
        id = id, title = title, subjectKey = ExamSubjects.CET4, group = group,
        date = date, endDate = endDate, dateSource = source, sourceLabel = "来源：测试",
    )

    private fun calendar(
        schemaVersion: Int? = 1, revision: String? = "r1",
        groups: List<String>? = listOf("英语", "教资"), exams: List<RemoteExamEntry>? = listOf(entry()),
    ) = RemoteExamCalendar(
        enabled = true, schemaVersion = schemaVersion, revision = revision,
        verifiedAt = "2026-10-04", groups = groups, exams = exams,
    )

    /** 正常那份要能转成客户端结构，并如实标上「这是服务端来的」。 */
    @Test fun validRemoteBecomesOnlineCalendar() {
        val parsed = calendar().toCalendar()
        assertNotNull(parsed)
        assertTrue(parsed!!.online)
        assertEquals(1, parsed.exams.size)
        assertEquals("2026-10-04", parsed.verifiedAt)
        assertEquals(listOf("英语", "教资"), parsed.groups)
    }

    /** schemaVersion 不是 1 = 结构变了，老代码读不懂 → 一律退回内置表，不能硬吃。 */
    @Test fun unknownSchemaVersionIsRejected() {
        assertNull(calendar(schemaVersion = 2).toCalendar())
        assertNull(calendar(schemaVersion = null).toCalendar())
    }

    @Test fun emptyOrMalformedExamsAreRejected() {
        assertNull(calendar(exams = emptyList()).toCalendar())
        assertNull(calendar(exams = null).toCalendar())
        // id 空 → 「已加入」判定会错位
        assertNull(calendar(exams = listOf(entry(id = ""))).toCalendar())
        // 日期坏 → 根本算不出倒计时
        assertNull(calendar(exams = listOf(entry(date = "2026-02-30"))).toCalendar())
        // id 重复 → 加入备考会加错那条
        assertNull(calendar(exams = listOf(entry(), entry())).toCalendar())
    }

    /**
     * 一条坏记录**不该**把整份表作废：过滤掉它，剩下的照常能用。
     * 服务端偶尔夹带一条脏数据，用户就没得用了 —— 那太脆。
     */
    @Test fun oneBrokenEntryIsDroppedNotFatal() {
        val parsed = calendar(exams = listOf(entry(date = "坏日期"), entry(id = "b"))).toCalendar()
        assertNotNull(parsed)
        assertEquals(listOf("b"), parsed!!.exams.map { it.id })
    }

    /** 服务端没给分类就用内置那 9 分类，筛选条不能变成空的。 */
    @Test fun missingGroupsFallsBackToBuiltin() {
        val parsed = calendar(groups = emptyList()).toCalendar()
        assertNotNull(parsed)
        assertEquals(NationalExamSeed.Group.ALL, parsed!!.groups)
    }

    /** 日期来源没写就按「推算」处理 —— 宁可多标一个「待核实」，也不能把没核实的当已核实。 */
    @Test fun missingDateSourceTreatedAsRule() {
        val parsed = calendar(exams = listOf(entry(source = null))).toCalendar()
        assertNotNull(parsed)
        assertEquals(ExamDateSource.RULE, parsed!!.exams.first().dateSource)
        assertTrue(ExamDateSource.needsVerify(parsed.exams.first().dateSource))
    }

    /** 内置表全量：每条日期都要能解析，否则 `upcoming` 会静默少一场。 */
    @Test fun builtinTableHasOnlyParsableDates() {
        val all = NationalExamSeed.all()
        assertTrue(all.isNotEmpty())
        all.forEach { item ->
            assertNotNull("「${item.title}」的日期解析不出来", ExamCountdown.dateToEpochDay(item.date))
            item.endDate?.let { end -> assertNotNull(ExamCountdown.dateToEpochDay(end)) }
        }
        assertEquals(all.size, all.map { it.id }.distinct().size)
    }

    /** `upcoming` 只滤掉已考完的，且按日期升序。 */
    @Test fun upcomingFiltersPastAndSortsByDate() {
        val today = ExamCountdown.dateToEpochDay("2026-12-01")!!
        val list = NationalExamSeed.upcoming(today)
        assertTrue(list.isNotEmpty())
        list.forEach { assertTrue(ExamCountdown.dateToEpochDay(it.date)!! >= today) }
        assertEquals(list.map { it.date }.sorted(), list.map { it.date })
    }
}
