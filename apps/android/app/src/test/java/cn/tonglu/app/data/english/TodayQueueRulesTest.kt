package cn.tonglu.app.data.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.ZoneId

/**
 * 今日队列的纯规则单测。
 *
 * 覆盖的是「算错了界面也看不出来」的两处：[TodayQueueRules.entryKey]（幂等键）
 * 与 [TodayQueueRules.day]（跨天口径）。
 *
 * 注意这一层**不代表队列能用了** —— 数据库层的幂等（主键 + INSERT OR IGNORE）、
 * 迁移、以及「加入今日不产生学习事件」由 `work/english-c-wiring/` 的真库冒烟覆盖。
 * 这里只保证键的算法和日期的算法本身是对的。
 */
class TodayQueueRulesTest {

    // ---- entryKey ----

    @Test
    fun `有词条 id 时用 id 做键`() {
        assertEquals("cet4-1234", TodayQueueRules.entryKey("cet4-1234", "Corn"))
    }

    @Test
    fun `没有词条 id 时退化成小写词形`() {
        // 正文点词经常查不到词条（生僻词、专有名词），这时词形本身就是唯一标识。
        assertEquals("corn", TodayQueueRules.entryKey(null, "Corn"))
    }

    @Test
    fun `词条 id 是空白串等同没有`() {
        // 空串不能当 id 用 —— 否则所有「查不到词条」的词都会撞进同一个键，
        // 用户加第二个词时界面会说「已在今日」，而队列里其实只有第一个。
        assertEquals("corn", TodayQueueRules.entryKey("   ", "Corn"))
        assertEquals("corn", TodayQueueRules.entryKey("", "Corn"))
    }

    @Test
    fun `词形两侧空白不影响键`() {
        assertEquals(TodayQueueRules.entryKey(null, "corn"), TodayQueueRules.entryKey(null, "  corn  "))
    }

    @Test
    fun `大小写不同的同一个词算同一个键`() {
        // 「Corn」和「corn」在用户眼里是同一个词，加两次只该有一条。
        assertEquals(TodayQueueRules.entryKey(null, "Corn"), TodayQueueRules.entryKey(null, "corn"))
    }

    @Test
    fun `土耳其语环境下同一个词也只有一个键`() {
        // 这是一条**真的有坑**的断言：直接用 `lowercase()`（走系统 Locale）时，
        // 土耳其语环境会把 "I" 变成 "ı"（不带点），于是同一个词在两个地区算出两个键，
        // 「加过没有」的判定就失效了。显式 Locale.ROOT 才是稳的。
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val tr = TodayQueueRules.entryKey(null, "I")
            java.util.Locale.setDefault(java.util.Locale.US)
            val us = TodayQueueRules.entryKey(null, "I")
            assertEquals("同一个词在不同 Locale 下必须是同一个键", us, tr)
            assertEquals("i", tr)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `不同的词必须是不同的键`() {
        assertNotEquals(TodayQueueRules.entryKey(null, "corn"), TodayQueueRules.entryKey(null, "corner"))
    }

    // ---- day ----

    @Test
    fun `day 是本地日期串`() {
        val zone = ZoneId.of("Asia/Shanghai")
        // 2026-10-13 08:00 +08
        val now = java.time.ZonedDateTime.of(2026, 10, 13, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2026-10-13", TodayQueueRules.day(now, zone))
    }

    @Test
    fun `跨过本地午夜就换一天`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val before = java.time.ZonedDateTime.of(2026, 10, 13, 23, 59, 59, 0, zone).toInstant().toEpochMilli()
        val after = java.time.ZonedDateTime.of(2026, 10, 14, 0, 0, 1, 0, zone).toInstant().toEpochMilli()
        assertEquals("2026-10-13", TodayQueueRules.day(before, zone))
        assertEquals("2026-10-14", TodayQueueRules.day(after, zone))
        // 「跨天即新队列」靠的就是这一步：日期串变了，主键就变了，昨天的行不会被读到。
        assertNotEquals(TodayQueueRules.day(before, zone), TodayQueueRules.day(after, zone))
    }

    @Test
    fun `同一时刻在不同时区可能不是同一天`() {
        // 这不是 bug，是「本地日」的固有含义 —— 显式钉住它，免得以后有人把 day 改成 UTC
        // 而以为「只是实现细节」。用户看到的「今天」是设备时区的今天。
        val instant = java.time.ZonedDateTime.of(2026, 10, 13, 23, 30, 0, 0,
            ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        assertEquals("2026-10-13", TodayQueueRules.day(instant, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-10-13", TodayQueueRules.day(instant, ZoneId.of("UTC")))  // 15:30 UTC
        assertEquals("2026-10-14", TodayQueueRules.day(instant, ZoneId.of("Pacific/Auckland")))  // 次日 04:30
    }
}
