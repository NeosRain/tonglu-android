package cn.tonglu.app.ui.clubs

import cn.tonglu.app.data.ClubActivityDto

/**
 * 活动的时间状态。
 *
 * 🔴 **只有这几种，没有「已取消」**：服务端 `ClubActivity` 没有取消字段，
 * 而「帖子未上架 / 机审未过」这两种不可见的活动**在列表响应里根本不会出现**
 * （`listActivities` 的 where 已经过滤掉了）。所以界面无法知道某个活动"被取消了"——
 * 显示「已取消」就是在编一个服务端没给的事实。
 *
 * [UNKNOWN] 也不是凑数：`startAt` 解析失败时（时间串坏了）必须如实说「待确认」，
 * 而不是塞进「尚未开始」（那是一个断言）。
 */
internal enum class ActivityPhase(val label: String) {
    UPCOMING("尚未开始"),
    ONGOING("进行中"),
    ENDED("已结束"),
    UNKNOWN("时间待确认"),
}

/** 我在某个活动上的报名状态（服务端 `ClubActivityRsvp.status`）。 */
internal enum class RsvpState(val label: String) {
    NONE("未报名"),
    GOING("已报名"),
    INTERESTED("感兴趣"),
}

/** ISO-8601（服务端 Prisma DateTime → JSON，UTC 带 `Z`）→ epoch 毫秒。**解析失败返回 null，不编时间。** */
internal fun activityEpochMillis(iso: String): Long? = try {
    java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
} catch (_: Exception) {
    null
}

/**
 * 判定活动处于哪个阶段。
 *
 * 区间是**左闭右开**：`[start, end)` 算「进行中」。
 * - 正好到 `start` 那一刻 → 进行中（不是「尚未开始」）；
 * - 正好到 `end` 那一刻 → **已结束**（不是「进行中」）——「16:00 结束」的活动在 16:00 就该显示结束。
 *
 * 结束时间缺失（`endAt == null`）时：开始之后一直算「进行中」——
 * 不替用户猜「大概开一天就结束了」（那是编数据）。
 */
internal fun activityPhase(startAt: String, endAt: String?, nowMillis: Long): ActivityPhase {
    val start = activityEpochMillis(startAt) ?: return ActivityPhase.UNKNOWN
    val end = endAt?.let { activityEpochMillis(it) }
    return when {
        nowMillis < start -> ActivityPhase.UPCOMING
        end != null && nowMillis >= end -> ActivityPhase.ENDED
        else -> ActivityPhase.ONGOING
    }
}

/** 单个时刻：「2026-10-12 14:00」（本机时区）。解析失败返回空串 —— 界面据此不显示时间位。 */
internal fun formatActivityTime(iso: String): String = try {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
} catch (_: Exception) { "" }

/**
 * 时间区间文案：「2026-10-12 14:00 — 16:00」。
 *
 * 同一天就用 `— 16:00` 收尾（跨天才重复日期）；两端都解析不出来时返回空串。
 */
internal fun activityTimeLabel(startAt: String, endAt: String?): String {
    val start = formatActivityTime(startAt)
    if (start.isEmpty()) return ""
    val end = endAt?.let { formatActivityTime(it) } ?: return start
    if (end.isEmpty()) return start
    val sameDay = start.take(10) == end.take(10)
    return "$start — ${if (sameDay) end.substringAfter(' ') else end}"
}

/** 报名状态：服务端下发的是字符串，认不出的值一律按「未报名」（不猜成已报名）。 */
internal fun rsvpStateOf(raw: String?): RsvpState = when (raw) {
    "GOING" -> RsvpState.GOING
    "INTERESTED" -> RsvpState.INTERESTED
    else -> RsvpState.NONE
}

/**
 * 轮播下一项索引。
 *
 * 🔴 两条「不推进」：只有一项时没必要动；**系统「减少动画」开着时永不自动推进**
 * （任务要求「减少动画关闭自动轮播」）。返回原索引 = 停播。
 */
internal fun nextBannerIndex(current: Int, size: Int, animationsDisabled: Boolean): Int {
    if (size <= 1 || animationsDisabled) return current
    return (current + 1).floorMod(size)
}

private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod

/**
 * 横幅要不要显示。
 *
 * 🔴 没有封面图字段 → 横幅**不是图片轮播**，而是「文字活动条」。
 * 所以只有在**真的有可见活动**时才显示；一个活动都没有时整条不出现
 * （不画一个空横幅假装「这里有内容」）。
 */
internal fun shouldShowActivityBanner(activities: List<ClubActivityDto>): Boolean = activities.isNotEmpty()

/**
 * 活动横幅/列表里显示的活动摘要。
 *
 * 没有封面、没有「已取消」、没有报名名单 —— 只有服务端真的给了的东西。
 * 空地点不显示「地点：待定」（那是替主办方下结论），直接不显示这一行。
 */
internal fun activityPlaceLabel(location: String?): String? =
    location?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 参与人数文案。
 *
 * 🔴 [going] 来自 `_count.rsvps` —— 它统计的是 `ClubActivityRsvp` 表的**全部行**，
 * 而 `GOING`（报名）和 `INTERESTED`（感兴趣）**存在同一张表里**（见 `clubs.service.ts`
 * 的 `rsvp()`：两种状态走同一个 upsert）。所以这个数**不是「报名人数」**。
 * 写成「N 人报名」就是把服务端没说的话说出来了 —— 得写成「报名或感兴趣」。
 */
internal fun activityGoingLabel(going: Int): String = "$going 人报名或感兴趣"

/**
 * 「报名名单」的说明。
 *
 * 🔴 服务端只有报名**人数**（`_count.rsvps`），**没有**名单接口，也**不做**「谁能看名单」的判定。
 * 任务要求「报名名单只给授权者」—— 在没有接口之前，正确的做法是**不显示名单**并说清原因，
 * 而不是拿人数冒充名单，也不是自己造一个「只有管理员可见」的假名单。
 */
internal const val RSVP_ROSTER_PENDING =
    "报名名单需要服务端接口，当前版本没有：这里只显示回应总人数（报名与感兴趣合并计数，服务端没分开给）。"

/** 活动页的讨论入口说明：讨论在活动帖子里，不另造一套。 */
internal const val ACTIVITY_DISCUSSION_HINT = "活动正文与讨论在同一个帖子里（活动即帖子）。"

/** 空活动 + 谁能建：说清「空」和「谁可以发起」。 */
internal const val ACTIVITY_EMPTY_FOR_MEMBER = "这个社团还没有公开的活动。"
internal const val ACTIVITY_EMPTY_FOR_OWNER = "还没有活动：作为负责人，你可以在网页端发起活动（活动会自动生成一篇帖子）。"
