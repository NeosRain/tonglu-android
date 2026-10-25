package cn.tonglu.app.data.exam

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考试倒计时的算法回归（`docs/plan/54` M8）。
 *
 * ## 为什么必须有这层测试
 * 真机/模拟器走查只能证明「这个按钮能点」，**跨天、跨年、时区、归档**这些
 * 一年才轮到一次的情况根本等不到。倒计时算错一天，用户看到的就是「明明明天考试
 * 却显示还有 2 天」—— 这种错误靠肉眼看界面永远发现不了，只能靠确定性输入锁住。
 *
 * 所有用例都用**固定时刻**当「现在」（`of(..., nowMillis)`），不依赖跑测试的机器时间。
 */
class ExamCountdownTest {

    /** 造一个确定的时刻：按给定时区的 y/M/d H:mm */
    private fun at(zone: String, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    /** 北京时间 2026-10-05 00:30 —— UTC 还是 10-04 16:30，正好卡在日界两侧 */
    private val beijingEarlyMorning = at("Asia/Shanghai", 2026, 10, 5, 0, 30)

    private fun event(
        id: String = "e1",
        date: String,
        pinned: Boolean = true,
        category: String = ExamCategory.NATIONAL,
        dateSource: String = ExamDateSource.OFFICIAL,
        archivedAt: Long? = null,
    ) = ExamEvent(
        id = id, title = id, subjectKey = ExamSubjects.CET4, date = date,
        category = category, source = ExamSource.NATIONAL, pinned = pinned,
        dateSource = dateSource, archivedAt = archivedAt,
    )

    // ---------- 日期解析 ----------

    @Test fun dateParsesAndRoundTrips() {
        assertEquals(0L, ExamCountdown.dateToEpochDay("1970-01-01"))
        val day = ExamCountdown.dateToEpochDay("2026-12-19")!!
        assertEquals("2026-12-19", ExamCountdown.epochDayToDate(day))
    }

    /** 解析失败必须返回 null —— 返回 0 会变成 1970 年，界面上是个荒唐日期 */
    @Test fun brokenDateIsNullNotEpochZero() {
        assertNull(ExamCountdown.dateToEpochDay(""))
        assertNull(ExamCountdown.dateToEpochDay("abc"))
        assertNull(ExamCountdown.dateToEpochDay("2026-13-01"))
        assertNull(ExamCountdown.dateToEpochDay("2026-02-30"))
        assertNull(ExamCountdown.dateToEpochDay("2026/12/19"))
        assertNull(ExamCountdown.of(event(date = "2026-02-30"), beijingEarlyMorning))
    }

    // ---------- 时区（最关键的坑） ----------

    /**
     * 北京时间 10-05 凌晨，UTC 还是 10-04。
     * 国家考证按 UTC+8 算「今天」必须是 10-05；若退化成 UTC 日界就会算成 10-04
     * —— 差一天，四六级当天早上打开 App 就会显示「还有 1 天」。
     */
    @Test fun nationalExamUsesBeijingDayNotUtcDay() {
        assertEquals(
            ExamCountdown.dateToEpochDay("2026-10-05"),
            ExamCountdown.todayEpochDay(beijingEarlyMorning, fixedUtcPlus8 = true),
        )
        // 同一时刻按 UTC 日界确实是 10-04 —— 证明上面那条不是碰巧对上
        assertEquals(
            ExamCountdown.dateToEpochDay("2026-10-04"),
            beijingEarlyMorning / (24L * 3600 * 1000),
        )
    }

    /** 设备本地日界：东八区机器上应和 UTC+8 一致（用 Calendar 现算期望值，不写死时区） */
    @Test fun localDayMatchesDeviceTimeZone() {
        val cal = Calendar.getInstance().apply { timeInMillis = beijingEarlyMorning }
        val offset = cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)
        val expected = (beijingEarlyMorning + offset) / (24L * 3600 * 1000)
        assertEquals(expected, ExamCountdown.todayEpochDay(beijingEarlyMorning, fixedUtcPlus8 = false))
    }

    // ---------- 倒计时三态 ----------

    @Test fun phasesAreUpcomingTodayAndEnded() {
        val now = at("Asia/Shanghai", 2026, 10, 5, 12, 0)
        val upcoming = ExamCountdown.of(event(date = "2026-12-19"), now)!!
        assertEquals(75, upcoming.days)
        assertEquals("还有 75 天", upcoming.label)
        assertEquals(ExamCountdown.Phase.UPCOMING, upcoming.phase)
        assertFalse(upcoming.urgent)

        // 「今天考试」不能写成「还有 0 天」—— 用户看到 0 只会以为算错了
        val today = ExamCountdown.of(event(date = "2026-10-05"), now)!!
        assertEquals(0, today.days)
        assertEquals("今天考试", today.label)
        assertEquals(ExamCountdown.Phase.TODAY, today.phase)

        val ended = ExamCountdown.of(event(date = "2026-10-01"), now)!!
        assertEquals(-4, ended.days)
        assertEquals("已结束", ended.label)
        assertEquals(ExamCountdown.Phase.ENDED, ended.phase)
    }

    /** 临考 7 天内要强调；第 8 天不强调 */
    @Test fun urgentStartsAtSevenDays() {
        val now = at("Asia/Shanghai", 2026, 10, 5, 12, 0)
        assertTrue(ExamCountdown.of(event(date = "2026-10-07"), now)!!.urgent)
        assertTrue(ExamCountdown.of(event(date = "2026-10-12"), now)!!.urgent)
        assertFalse(ExamCountdown.of(event(date = "2026-10-13"), now)!!.urgent)
    }

    /** 跨年：12-31 看 1-1 是「还有 1 天」，不能被年份差搞成 365 */
    @Test fun newYearBoundaryCountsOneDay() {
        val now = at("Asia/Shanghai", 2026, 12, 31, 23, 50)
        val next = ExamCountdown.of(event(date = "2027-01-01"), now)!!
        assertEquals(1, next.days)
        assertEquals("还有 1 天", next.label)
    }

    /** 闰年：2028 是闰年，2-28 → 3-1 中间隔着 2-29 */
    @Test fun leapDayIsCounted() {
        val now = at("Asia/Shanghai", 2028, 2, 28, 12, 0)
        assertEquals(2, ExamCountdown.of(event(date = "2028-03-01"), now)!!.days)
        val now2 = at("Asia/Shanghai", 2027, 2, 28, 12, 0)
        assertEquals(1, ExamCountdown.of(event(date = "2027-03-01"), now2)!!.days)
    }

    // ---------- 待核实 ----------

    @Test fun unverifiedDateIsFlaggedWithHint() {
        val now = at("Asia/Shanghai", 2026, 10, 5, 12, 0)
        val ruled = ExamCountdown.of(event(date = "2027-03-06", dateSource = ExamDateSource.RULE), now)!!
        assertTrue(ruled.needsVerify)
        assertEquals("按往年规律推算，以官方公告为准", ruled.verifyHint)

        val official = ExamCountdown.of(event(date = "2026-12-19", dateSource = ExamDateSource.OFFICIAL), now)!!
        assertFalse(official.needsVerify)
        assertNull(official.verifyHint)
    }

    // ---------- 首页列表 ----------

    @Test fun homeListKeepsPinnedUpcomingSortedByDays() {
        val now = at("Asia/Shanghai", 2026, 10, 5, 12, 0)
        val far = event("far", "2027-06-12")
        val near = event("near", "2026-10-12")
        val ended = event("ended", "2026-09-01")
        val notPinned = event("off", "2026-11-01", pinned = false)
        val archived = event("archived", "2026-11-01", archivedAt = 1L)

        // 顺序：按剩余天数升序。已结束/未备考/已归档的都不该出现
        val list = ExamCountdown.homeList(listOf(far, near, ended, notPinned, archived), now, limit = 3)
        assertEquals(listOf("near", "far"), list.map { it.id })
    }

    @Test fun homeListLimitsToThreeAndStableOnTies() {
        val now = at("Asia/Shanghai", 2026, 10, 5, 12, 0)
        // 同一天三场：并列时按日期再按名称，顺序必须稳定（不然每次重组都在跳）
        val a = event("A", "2026-12-12")
        val b = event("B", "2026-12-12")
        val c = event("C", "2026-12-12")
        val d = event("D", "2026-12-12")
        assertEquals(
            listOf("A", "B", "C"),
            ExamCountdown.homeList(listOf(d, c, b, a), now, limit = 3).map { it.id },
        )
    }

    // ---------- 归档 ----------

    /** 归档是打标记不是删数据，且只触发一次 */
    @Test fun archiveOnlyAfterThirtyDaysAndOnlyOnce() {
        val now = at("Asia/Shanghai", 2026, 10, 5, 12, 0)
        assertFalse(ExamCountdown.shouldArchive(event(date = "2026-09-20"), now))   // 15 天
        assertFalse(ExamCountdown.shouldArchive(event(date = "2026-09-06"), now))   // 29 天
        assertTrue(ExamCountdown.shouldArchive(event(date = "2026-09-05"), now))    // 30 天
        assertTrue(ExamCountdown.shouldArchive(event(date = "2026-01-01"), now))
        // 已经归档过的不再重复处理
        assertFalse(ExamCountdown.shouldArchive(event(date = "2026-01-01", archivedAt = 1L), now))
        // 未来的永远不归档
        assertFalse(ExamCountdown.shouldArchive(event(date = "2027-01-01"), now))
        // 日期坏了也不要顺手归档（那是数据问题，不是过期）
        assertFalse(ExamCountdown.shouldArchive(event(date = "坏日期"), now))
    }
}
