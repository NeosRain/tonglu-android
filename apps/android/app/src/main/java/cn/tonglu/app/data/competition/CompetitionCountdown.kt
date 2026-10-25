package cn.tonglu.app.data.competition

import cn.tonglu.app.data.exam.ExamCountdown

/**
 * 竞赛期限的倒计时与展示（`docs/plan/56` §2.3）。
 *
 * 纯函数，不碰 Android、不碰当前时间之外的全局状态 —— 跨年 / 闰年 / 当天 / 已过
 * 这几种情况要靠确定性验证，不能靠走查。
 *
 * ## 三条不能破的规矩
 * 1. **未知期限不倒计时**：`UNCONFIRMED` 或没有日期的期限，不算天数、不给「还有 N 天」，
 *    只显示「日期待核实」。这是「未知期限不提醒」在客户端这一侧的落地。
 * 2. **DATE 精度不补时刻**：原文只写日期的，展示就用服务端给的 [CompetitionDeadline.display]
 *    （「当日截止，时间以原文为准」），**绝不自己拼 23:59**。
 * 3. 时区**固定 UTC+8**：竞赛公告都是北京时间（和 [ExamCountdown] 里国家考证同一理由），
 *    不跟设备时区走 —— 否则用户换个时区，同一个截止日会算差一天。
 */
object CompetitionCountdown {

    enum class Phase { UPCOMING, TODAY, ENDED }

    data class Result(
        /** 日历日差：>0 还有几天，==0 今天，<0 已过 */
        val days: Int,
        val phase: Phase,
        /** 主文案，如「还有 12 天」/「今天截止」/「已过」 */
        val label: String,
        /** 临近（≤7 天），UI 用主色强调 */
        val urgent: Boolean,
        /** true = 日期没核实过，界面**只能**显示「日期待核实」，不能显示天数 */
        val unknown: Boolean,
    )

    /**
     * 取一条期限能算倒计时的那个日期（`yyyy-MM-dd`）。
     *
     * - `DATE`：就是 `deadlineDate`；
     * - `DATETIME`：从 `deadlineAt`（ISO 串）截出日期部分 —— 按 **UTC+8** 截，
     *   因为服务端下发的是带时区的 ISO，直接 `substring(0,10)` 在 UTC+8 会差一天。
     *
     * 服务端已经把 `countable` 算好了，这里再算一遍是为了防「字段在但值为空」的半截数据。
     */
    fun dateOf(deadline: CompetitionDeadline): String? {
        if (!deadline.countable) return null
        deadline.deadlineDate?.takeIf { ExamCountdown.dateToEpochDay(it) != null }?.let { return it }
        val at = deadline.deadlineAt ?: return null
        return isoToLocalDate(at)
    }

    /**
     * ISO 8601 时刻 → 北京时间（UTC+8）那一天的 `yyyy-MM-dd`。解析不出来返回 null。
     *
     * 为什么不能直接 `substring(0, 10)`：服务端下发的是 **UTC** 的 ISO 串
     * （如 `2026-12-20T16:00:00.000Z`）。如果原文写的是北京时间 12-21 00:00，
     * 截前 10 位会得到 12-20 —— 差一天。所以要把时刻按 UTC+8 折算日界。
     *
     * 只认三种形态：`...Z`（UTC）、`...±HH:MM`（带偏移）、无时区（当作已是本地日期）。
     */
    fun isoToLocalDate(iso: String): String? {
        if (iso.length < 10) return null
        val year = iso.substring(0, 4).toIntOrNull() ?: return null
        val month = iso.substring(5, 7).toIntOrNull() ?: return null
        val day = iso.substring(8, 10).toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        val base = "%04d-%02d-%02d".format(year, month, day)
        val epochDay = ExamCountdown.dateToEpochDay(base) ?: return null

        if (iso.length < 16) return base // 只有日期，没钟点
        val hour = iso.substring(11, 13).toIntOrNull() ?: return base
        val minute = iso.substring(14, 16).toIntOrNull() ?: return base

        // 把「这一刻」折算成 UTC+8 的分钟数，看有没有跨过日界。
        val utcMinutes = hour * 60 + minute
        return when {
            iso.endsWith("Z", ignoreCase = true) -> {
                val local = utcMinutes + 8 * 60
                when {
                    local >= 24 * 60 -> ExamCountdown.epochDayToDate(epochDay + 1)
                    local < 0 -> ExamCountdown.epochDayToDate(epochDay - 1)
                    else -> base
                }
            }
            // 带偏移（+08:00 / -05:00）：先换回 UTC，再折算 UTC+8
            else -> {
                val offsetText = Regex("([+-])(\\d{2}):?(\\d{2})$").find(iso)
                if (offsetText == null) {
                    base // 没写时区就当作已经是本地日期
                } else {
                    val signChar = offsetText.groupValues[1]
                    val offHour = offsetText.groupValues[2].toIntOrNull() ?: 0
                    val offMin = offsetText.groupValues[3].toIntOrNull() ?: 0
                    val offsetMinutes = (offHour * 60 + offMin) * if (signChar == "-") -1 else 1
                    val utc = utcMinutes - offsetMinutes
                    val local = utc + 8 * 60
                    when {
                        local >= 24 * 60 -> ExamCountdown.epochDayToDate(epochDay + 1)
                        local < 0 -> ExamCountdown.epochDayToDate(epochDay - 1)
                        else -> base
                    }
                }
            }
        }
    }

    /**
     * 算一条期限的倒计时。
     *
     * @param nowMillis 传进来而不是内部取，是为了能测。
     * @return 日期待核实的返回 `unknown = true` 的结果（**不是 null** —— 界面仍要展示这条，只是不显示天数）。
     */
    fun of(deadline: CompetitionDeadline, nowMillis: Long = System.currentTimeMillis()): Result {
        val date = dateOf(deadline)
            ?: return Result(days = 0, phase = Phase.UPCOMING, label = "日期待核实", urgent = false, unknown = true)
        val deadlineDay = ExamCountdown.dateToEpochDay(date)!!
        val today = ExamCountdown.todayEpochDay(nowMillis, fixedUtcPlus8 = true)
        val days = (deadlineDay - today).toInt().coerceIn(-3650, 3650)
        val phase = when {
            days > 0 -> Phase.UPCOMING
            days == 0 -> Phase.TODAY
            else -> Phase.ENDED
        }
        val label = when (phase) {
            Phase.TODAY -> "今天截止"
            Phase.UPCOMING -> "还有 $days 天"
            Phase.ENDED -> "已过"
        }
        return Result(days = days, phase = phase, label = label, urgent = phase == Phase.UPCOMING && days <= 7, unknown = false)
    }
}

/**
 * 客户端真正用的竞赛期限（远端 → 本地的净化结果）。
 *
 * @param date 能算倒计时的日期（`yyyy-MM-dd`）；`null` = 日期待核实 / 没有日期。
 */
data class CompetitionDeadline(
    val id: String,
    val kind: String,
    val precision: String,
    val verificationStatus: String,
    val date: String?,
    val deadlineAt: String?,
    val deadlineDate: String?,
    /** 服务端给的人话展示串（DATE 时明说「以原文为准」）。 */
    val display: String,
    /** 能不能倒计时。**以服务端为准**，不要自己重算。 */
    val countable: Boolean,
    val sourceExcerpt: String?,
    val revision: Int,
) {
    val kindLabel: String get() = CompetitionKind.labelOf(kind)
}

/** 客户端真正用的竞赛（只含已发布的，且期限只含已核实的）。 */
data class Competition(
    val id: String,
    val title: String,
    val organizer: String?,
    val academicYear: String?,
    val sourceUrl: String?,
    val revision: Int,
    val verifiedAt: String?,
    val deadlines: List<CompetitionDeadline>,
) {
    /** 最早的那个还没过期的期限 —— 列表页一眼看的那个。全过了就给最早的。 */
    fun nextDeadline(nowMillis: Long = System.currentTimeMillis()): CompetitionDeadline? {
        if (deadlines.isEmpty()) return null
        val upcoming = deadlines
            .map { it to CompetitionCountdown.of(it, nowMillis) }
            .filter { it.second.phase != CompetitionCountdown.Phase.ENDED }
            .minByOrNull { it.second.days }
        return (upcoming ?: deadlines.first().let { it to CompetitionCountdown.of(it, nowMillis) }).first
    }
}

/**
 * 远端 → 客户端结构。**不合法返回 null / 被过滤掉**（照 `RemoteExamCalendar.toCalendar`）。
 *
 * 过滤点都是「留着会出事」的：id 为空会让订阅对不上；标题空、期限一条都没有的赛事
 * 在列表里只是个空壳。期限**只保留 VERIFIED 且有日期的** —— 未核实的日期不展示天数。
 */
internal fun RemoteCompetitionCatalog.toCatalog(): List<Competition> {
    val raw = items.orEmpty()
    return raw.mapNotNull { item ->
        val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val title = item.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val deadlines = item.deadlines.orEmpty().mapNotNull { it.toDeadline() }
        // 赛事存在但期限未核实：保留公告，只禁止倒计时与订阅。
        Competition(
            id = id,
            title = title,
            organizer = item.organizer?.takeIf { it.isNotBlank() },
            academicYear = item.academicYear?.takeIf { it.isNotBlank() },
            sourceUrl = item.sourceUrl?.takeIf { it.isNotBlank() },
            revision = item.revision ?: 1,
            verifiedAt = item.verifiedAt?.takeIf { it.isNotBlank() },
            deadlines = deadlines.sortedWith(
                compareBy<CompetitionDeadline> { CompetitionKind.orderOf(it.kind) }.thenBy { it.date ?: "9999" },
            ),
        )
    }
}

private fun RemoteCompetitionDeadline.toDeadline(): CompetitionDeadline? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val kind = kind?.takeIf { it.isNotBlank() } ?: CompetitionKind.REGISTRATION
    val precision = precision?.takeIf { it in listOf(CompetitionPrecision.DATE, CompetitionPrecision.DATETIME) }
        ?: CompetitionPrecision.DATE
    val verification = verificationStatus?.takeIf {
        it in listOf(CompetitionVerification.VERIFIED, CompetitionVerification.UNCONFIRMED)
    } ?: CompetitionVerification.UNCONFIRMED
    val date = deadlineDate?.takeIf { ExamCountdown.dateToEpochDay(it) != null }
        ?: deadlineAt?.let { CompetitionCountdown.isoToLocalDate(it) }
    // 没有可算日期的期限直接丢掉：留着也只会在界面上占一行显示「日期待核实」，
    // 而服务端本来就要求「有确切日期的期限才值得填」。
    if (date == null) return null
    return CompetitionDeadline(
        id = id, kind = kind, precision = precision, verificationStatus = verification,
        date = date, deadlineAt = deadlineAt, deadlineDate = deadlineDate,
        display = display?.takeIf { it.isNotBlank() }
            ?: if (precision == CompetitionPrecision.DATETIME) deadlineAt.orEmpty() else "当日截止，时间以原文为准",
        countable = countable ?: (verification == CompetitionVerification.VERIFIED),
        sourceExcerpt = sourceExcerpt?.takeIf { it.isNotBlank() },
        revision = revision ?: 1,
    )
}

/** 我的订阅项（净化后）。 */
data class Subscription(
    val id: String,
    val eventId: String,
    val deadlineId: String,
    val title: String,
    val organizer: String?,
    val sourceUrl: String?,
    val invalidated: Boolean,
    val changed: Boolean,
    /** 服务端原样带回；`enabled=false` 的条目不提醒（目前取消订阅写逻辑删除，这里兜底）。 */
    val enabled: Boolean,
    /** 提醒策略（`off` | `1d` | `3d` | `7d`），原样带回给提醒排期用；未知值在排期层按不提醒处理。 */
    val reminderPolicy: String?,
    val deadline: CompetitionDeadline?,
)

internal fun RemoteSubscription.toSubscription(): Subscription? {
    val deadlineId = deadlineId?.takeIf { it.isNotBlank() } ?: return null
    val eventId = eventId?.takeIf { it.isNotBlank() } ?: return null
    return Subscription(
        id = id ?: "$eventId:$deadlineId",
        eventId = eventId,
        deadlineId = deadlineId,
        title = title?.takeIf { it.isNotBlank() } ?: "竞赛",
        organizer = organizer?.takeIf { it.isNotBlank() },
        sourceUrl = sourceUrl?.takeIf { it.isNotBlank() },
        invalidated = invalidated == true,
        changed = changed == true,
        enabled = enabled != false,
        reminderPolicy = reminderPolicy?.takeIf { it.isNotBlank() },
        deadline = deadline?.toDeadline(),
    )
}

/** 只有已核实、尚未过期的报名期限才属于可报名目录。 */
internal fun competitionOpenForRegistration(competition: Competition, nowMillis: Long): Boolean =
    competition.deadlines.any { deadline ->
        val countdown = CompetitionCountdown.of(deadline, nowMillis)
        deadline.kind == CompetitionKind.REGISTRATION && !countdown.unknown &&
            countdown.phase != CompetitionCountdown.Phase.ENDED
    }
