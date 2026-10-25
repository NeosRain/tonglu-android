package cn.tonglu.app.notify

import cn.tonglu.app.data.competition.CompetitionDeadline
import cn.tonglu.app.data.competition.CompetitionKind
import cn.tonglu.app.data.competition.CompetitionPrecision
import cn.tonglu.app.data.competition.CompetitionVerification
import cn.tonglu.app.data.competition.Subscription
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竞赛截止提醒的排期纯函数（notify/DeadlineReminder.plan）。
 *
 * 锁的都是走查验不出来的东西：
 * - 时区口径（固定 UTC+8）—— 跨日界的时刻排错 8 小时界面完全看不出来；
 * - DATE 精度不编钟点（提醒落在 09:00，不是 23:59）；
 * - 「已过提醒点但还没到截止」的兜底（否则用户订阅剩 2 天的比赛时收不到任何提醒，
 *   toast 里「截止前会提醒你」就是空话）。
 *
 * 所有用例固定「现在」，不依赖跑测试的机器时间。
 */
class DeadlineReminderPlanTest {

    private fun at(zone: String, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear(); set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private val DAY_MS = 24L * 3600 * 1000

    /** 北京时间 2026-10-15 12:00 */
    private val now = at("Asia/Shanghai", 2026, 10, 15, 12, 0)

    private fun sub(
        id: String = "s1",
        policy: String? = "3d",
        enabled: Boolean = true,
        invalidated: Boolean = false,
        precision: String = CompetitionPrecision.DATETIME,
        deadlineAt: String? = "2026-11-01T09:00:00.000Z",
        deadlineDate: String? = null,
        date: String? = null,
        kind: String = CompetitionKind.REGISTRATION,
        countable: Boolean = true,
    ) = Subscription(
        id = id, eventId = "e1", deadlineId = "d1",
        title = "蓝桥杯", organizer = null, sourceUrl = null,
        invalidated = invalidated, changed = false,
        enabled = enabled, reminderPolicy = policy,
        deadline = CompetitionDeadline(
            id = "d1", kind = kind, precision = precision,
            verificationStatus = CompetitionVerification.VERIFIED,
            date = date ?: deadlineAt?.let { cn.tonglu.app.data.competition.CompetitionCountdown.isoToLocalDate(it) }
                ?: deadlineDate,
            deadlineAt = deadlineAt, deadlineDate = deadlineDate,
            display = "2026-11-01 17:00（北京时间）",
            countable = countable, sourceExcerpt = null, revision = 1,
        ),
    )

    // ── policyDays ───────────────────────────────────────────────────────

    @Test
    fun `策略解析 - 1d_3d_7d 有效，off 与未知一律不提醒`() {
        assertEquals(1, DeadlineReminder.policyDays("1d"))
        assertEquals(3, DeadlineReminder.policyDays("3d"))
        assertEquals(7, DeadlineReminder.policyDays("7d"))
        assertEquals(3, DeadlineReminder.policyDays(" 3d "))
        assertNull(DeadlineReminder.policyDays("off"))
        assertNull(DeadlineReminder.policyDays("2d"))
        assertNull(DeadlineReminder.policyDays(null))
        assertNull(DeadlineReminder.policyDays(""))
    }

    // ── isoToEpochMillis ─────────────────────────────────────────────────

    @Test
    fun `UTC ISO 解析成正确的 epoch 毫秒`() {
        // 2026-11-01T09:00:00.000Z == 北京时间 17:00
        assertEquals(at("Asia/Shanghai", 2026, 11, 1, 17, 0), DeadlineReminder.isoToEpochMillis("2026-11-01T09:00:00.000Z"))
    }

    @Test
    fun `带偏移的 ISO 换算成 UTC epoch`() {
        assertEquals(at("Asia/Shanghai", 2026, 11, 1, 17, 0), DeadlineReminder.isoToEpochMillis("2026-11-01T17:00:00+08:00"))
    }

    @Test
    fun `无时区的 ISO 按北京时间墙钟解释`() {
        assertEquals(at("Asia/Shanghai", 2026, 11, 1, 17, 0), DeadlineReminder.isoToEpochMillis("2026-11-01T17:00"))
    }

    @Test
    fun `解析不出来的串返回 null 而不是 0`() {
        assertNull(DeadlineReminder.isoToEpochMillis("not-a-date"))
        assertNull(DeadlineReminder.isoToEpochMillis(""))
    }

    // ── plan：DATETIME 精度 ──────────────────────────────────────────────

    @Test
    fun `3d 策略的触发点是截止前 3 天的同一时刻`() {
        val alarms = DeadlineReminder.plan(listOf(sub()), now)
        assertEquals(1, alarms.size)
        val expected = at("Asia/Shanghai", 2026, 11, 1, 17, 0) - 3 * DAY_MS
        assertEquals(expected, alarms[0].fireAt)
        // 2026-10-29 17:00，在未来 → 不需要兜底
        assertTrue(alarms[0].fireAt > now)
    }

    @Test
    fun `off 与未知策略完全不排`() {
        assertTrue(DeadlineReminder.plan(listOf(sub(policy = "off"), sub(policy = "2d"), sub(policy = null)), now).isEmpty())
    }

    @Test
    fun `已取消与已失效的订阅不排`() {
        assertTrue(DeadlineReminder.plan(listOf(sub(enabled = false), sub(invalidated = true)), now).isEmpty())
    }

    @Test
    fun `不可倒计时的期限不排`() {
        assertTrue(DeadlineReminder.plan(listOf(sub(countable = false)), now).isEmpty())
    }

    @Test
    fun `已过截止的订阅不排`() {
        // 截止 2026-10-01 < 今天 10-15
        assertTrue(DeadlineReminder.plan(listOf(sub(deadlineAt = "2026-09-30T09:00:00.000Z")), now).isEmpty())
    }

    @Test
    fun `提醒点已过但截止没到 - 兜底到现在加一分钟`() {
        // 截止 2026-10-17 17:00（北京时间），剩 2 天 < 3d 策略 → 自然提醒点 10-14 已过
        val alarms = DeadlineReminder.plan(listOf(sub(deadlineAt = "2026-10-17T09:00:00.000Z")), now)
        assertEquals(1, alarms.size)
        assertEquals(now + 60_000L, alarms[0].fireAt)
        assertTrue(alarms[0].fireAt < at("Asia/Shanghai", 2026, 10, 17, 17, 0))
    }

    // ── plan：DATE 精度 ──────────────────────────────────────────────────

    @Test
    fun `DATE 精度提醒落在截止前 N 天的北京时间 09_00 而不是编出来的 23_59`() {
        val alarms = DeadlineReminder.plan(
            listOf(sub(precision = CompetitionPrecision.DATE, deadlineAt = null, deadlineDate = "2026-11-01")),
            now,
        )
        assertEquals(1, alarms.size)
        assertEquals(at("Asia/Shanghai", 2026, 10, 29, 9, 0), alarms[0].fireAt)
        assertTrue(alarms[0].dateOnly)
    }

    @Test
    fun `DATE 精度过期按日历日判`() {
        // 截止日 2026-10-15（今天）→ 不算已过；提醒点 10-12 09:00 已过 → 兜底
        val alarms = DeadlineReminder.plan(
            listOf(sub(precision = CompetitionPrecision.DATE, deadlineAt = null, deadlineDate = "2026-10-15")),
            now,
        )
        assertEquals(1, alarms.size)
        assertEquals(now + 60_000L, alarms[0].fireAt)
    }

    // ── fireTitle / 通知文案 ─────────────────────────────────────────────

    @Test
    fun `标题天数按 UTC 加 8 日界现算`() {
        val deadline = at("Asia/Shanghai", 2026, 10, 18, 17, 0)
        assertEquals("还有 3 天报名截止", DeadlineReminder.fireTitle("报名截止", deadline, now))
        // 送达推迟到 10-17 20:00 → 只剩 1 天
        assertEquals("明天报名截止", DeadlineReminder.fireTitle("报名截止", deadline, at("Asia/Shanghai", 2026, 10, 17, 20, 0)))
        // 推迟到截止当天
        assertEquals("今天报名截止", DeadlineReminder.fireTitle("报名截止", deadline, at("Asia/Shanghai", 2026, 10, 18, 8, 0)))
    }

    @Test
    fun `fireSubText 的 DATETIME 按北京时间展示`() {
        val alarm = DeadlineAlarm(
            fireAt = now, subscriptionId = "s1",
            deadlineMillis = at("Asia/Shanghai", 2026, 11, 1, 17, 0),
            title = "蓝桥杯", kindLabel = "报名截止", dateOnly = false,
        )
        assertEquals("11月1日 17:00 截止（北京时间）", DeadlineReminder.fireSubText(alarm))
    }

    @Test
    fun `fireSubText 的 DATE 明说时间以原文为准`() {
        val alarm = DeadlineAlarm(
            fireAt = now, subscriptionId = "s1",
            deadlineMillis = at("Asia/Shanghai", 2026, 11, 2, 0, 0),
            title = "蓝桥杯", kindLabel = "报名截止", dateOnly = true,
        )
        assertEquals("当日截止，时间以原文为准", DeadlineReminder.fireSubText(alarm))
    }

    // ── 上限 ─────────────────────────────────────────────────────────────

    @Test
    fun `超过上限时取最近的那些`() {
        val many = (1..50).map { i ->
            sub(id = "s$i", deadlineAt = "2026-11-%02dT09:00:00.000Z".format(i.coerceIn(1, 28)), policy = "1d")
        }
        val alarms = DeadlineReminder.plan(many, now)
        assertEquals(32, alarms.size)
        // 全是 11 月的 1d 策略,按 fireAt 排序后取前 32 → 最早的在前面
        assertTrue(alarms[0].fireAt <= alarms.last().fireAt)
    }
}
