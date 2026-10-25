package cn.tonglu.app.data.exam

import java.util.Calendar
import java.util.TimeZone

/**
 * 倒计时计算（`docs/plan/54` M8）—— **全是纯函数，不碰 Android、不碰当前时间之外的全局状态**，
 * 这样跨年 / 闰年 / 当天 / 已结束这几种情况才能被确定性验证（靠走查是验不出来的）。
 *
 * ## 两个必须讲清楚的坑
 *
 * ### 1. 时区：国家考证**不跟设备时区走**
 * 考研公告原文写「考试时间以北京时间为准」，四六级 / 教资 / NCRE 的公告时间也都是北京时间。
 * 所以国家考证的「今天」固定按 **UTC+8** 算（中国在 1986 年后不再实行夏令时，固定 +8 是准确的，
 * 不需要 `java.time` 的 `ZoneId`——工程没开 desugaring，用不了）。
 * 校内 / 自建考试跟设备本地日界（它本来就是本地事件）。
 *
 * ### 2. 日期差按「日历日」算，不按 24 小时
 * 今晚 23:59 看明天的考试应该是「1 天」，不是 0.0007 天。所以先把 `yyyy-MM-dd`
 * 折算成 **epochDay**（从 1970-01-01 起的天数，UTC 基准），再相减。
 * 直接 `millis / 86400000` 是 **UTC 日界**，东八区会提前 8 小时跨天 —— 这个坑在
 * `ScheduleText.todayEpochDay` 里踩过一次，不要重犯。
 */
object ExamCountdown {

    private const val DAY_MS = 24L * 3600 * 1000
    private const val UTC_PLUS_8_MS = 8L * 3600 * 1000

    /** 考试状态 */
    enum class Phase {
        /** 还没考 */
        UPCOMING,

        /** 就是今天 */
        TODAY,

        /** 已经考完 */
        ENDED,
    }

    data class Result(
        /** 日历日差：>0 还有几天，==0 今天，<0 已过 */
        val days: Int,
        val phase: Phase,
        /** 主文案，如「还有 12 天」/「今天考试」/「已结束」 */
        val label: String,
        /** 临考（≤7 天），UI 用主色强调 */
        val urgent: Boolean,
        /** 日期不是官方确认的，天数后要加「?」 */
        val needsVerify: Boolean,
        /** 「?」的说明文字，null 表示不用说明 */
        val verifyHint: String?,
    )

    /**
     * `yyyy-MM-dd` → epochDay。解析失败返回 null（**不要**返回 0，那会变成 1970 年，UI 上就是个荒唐日期）。
     */
    fun dateToEpochDay(date: String): Long? {
        val parts = date.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        // isLenient=false：不加这句，`2026-02-30` 会被 Calendar 悄悄滚成 `2026-03-02`
        // —— 用户填了一个日期，存进去的却是另一个，而界面上完全看不出来。
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            isLenient = false
            set(year, month - 1, day)
        }
        return runCatching { calendar.timeInMillis / DAY_MS }.getOrNull()
    }

    /** epochDay → `yyyy-MM-dd` */
    fun epochDayToDate(epochDay: Long): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochDay * DAY_MS
        }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(year, month, day)
    }

    /**
     * 「今天」的 epochDay。
     *
     * @param fixedUtcPlus8 true = 按北京时间算（国家考证用）；false = 按设备本地日界（校内 / 自建用）
     */
    fun todayEpochDay(millis: Long = System.currentTimeMillis(), fixedUtcPlus8: Boolean = true): Long {
        if (fixedUtcPlus8) return (millis + UTC_PLUS_8_MS) / DAY_MS
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        val offset = calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)
        return (millis + offset) / DAY_MS
    }

    /**
     * 算一条考试的倒计时。
     *
     * @param nowMillis 当前时间（传进来而不是内部取，是为了能测）
     * @param fixedUtcPlus8 这条考试要不要按北京时间算今天（国家考证 = true）
     */
    fun of(event: ExamEvent, nowMillis: Long = System.currentTimeMillis(), fixedUtcPlus8: Boolean = true): Result? {
        val examDay = dateToEpochDay(event.date) ?: return null
        val today = todayEpochDay(nowMillis, fixedUtcPlus8)
        val days = (examDay - today).toInt().coerceIn(-3650, 3650)
        val needsVerify = ExamDateSource.needsVerify(event.dateSource)
        val phase = when {
            days > 0 -> Phase.UPCOMING
            days == 0 -> Phase.TODAY
            else -> Phase.ENDED
        }
        val label = when (phase) {
            // 「今天考试」不能写成「还有 0 天」—— 那天用户看到 0 只会觉得算错了
            Phase.TODAY -> "今天考试"
            Phase.UPCOMING -> "还有 $days 天"
            Phase.ENDED -> "已结束"
        }
        return Result(
            days = days,
            phase = phase,
            label = label,
            urgent = phase == Phase.UPCOMING && days <= 7,
            needsVerify = needsVerify,
            verifyHint = ExamDateSource.hintOf(event.dateSource),
        )
    }

    /**
     * 首页要显示的那几条：备考中（pinned）且**还没结束**的，按剩余天数升序。
     *
     * 已结束的从首页移除（M8 状态规则表），但**不删数据** —— 日历页的「已结束」分组还要看。
     */
    /** 排序用的中间结果。刻意用带字段名的 data class —— `Pair` 靠 first/second 极易写反。 */
    private data class Dated(val event: ExamEvent, val days: Int)

    fun homeList(events: List<ExamEvent>, nowMillis: Long = System.currentTimeMillis(), limit: Int = 3): List<ExamEvent> =
        events
            .filter { it.pinned && it.archivedAt == null }
            .mapNotNull { event ->
                val result = of(event, nowMillis, fixedUtcPlus8 = event.category == ExamCategory.NATIONAL)
                    ?: return@mapNotNull null
                if (result.phase == Phase.ENDED) null else Dated(event, result.days)
            }
            // 按剩余天数升序；并列时先按日期、再按名称，保证顺序稳定（不然每次重组都在跳）
            .sortedWith(compareBy<Dated> { it.days }.thenBy { it.event.date }.thenBy { it.event.title })
            .map { it.event }
            .take(limit)

    /** 结束后保留多少天再归档（M8：考研结束后还想看「考完了」） */
    const val ARCHIVE_AFTER_DAYS = 30

    /**
     * 该不该自动归档：结束超过 [ARCHIVE_AFTER_DAYS] 天且还没打过 archivedAt。
     *
     * 注意这是「打标记」不是「删数据」—— 用户手动加的考试，沉默删掉是最糟的体验。
     */
    fun shouldArchive(event: ExamEvent, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (event.archivedAt != null) return false
        val result = of(event, nowMillis, fixedUtcPlus8 = event.category == ExamCategory.NATIONAL) ?: return false
        return result.days <= -ARCHIVE_AFTER_DAYS
    }
}
