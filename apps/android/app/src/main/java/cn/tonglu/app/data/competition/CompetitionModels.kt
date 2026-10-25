package cn.tonglu.app.data.competition

/**
 * 竞赛目录与订阅的客户端模型（`docs/plan/56` §2.3）。
 *
 * ## 字段一律可空 + String
 * 照 `RemoteExamCalendar` 的容错范式：服务端换版、字段改名、半个 JSON 都得扛住 ——
 * 解析失败只是「这次没有竞赛」，绝不能崩。枚举值也用 String（Gson 存盘/读盘，
 * 改名会让老数据反序列化不出来），未知值退化为「其它」。
 *
 * ## 日期分两种精度，**不能用同一个字段**
 * 原文只写「11月20日」的，就是 `precision = DATE`，只有 `deadlineDate`；
 * 原文写了具体钟点的，才是 `DATETIME`，给 `deadlineAt`。
 * 客户端**绝不自己补 23:59** —— 那是在替原文编一个它没说的时间。
 * 见 [CompetitionDeadline.display]。
 */
data class RemoteCompetition(
    val id: String? = null,
    val title: String? = null,
    val organizer: String? = null,
    val academicYear: String? = null,
    val sourceUrl: String? = null,
    val revision: Int? = null,
    val verifiedAt: String? = null,
    val updatedAt: String? = null,
    val deadlines: List<RemoteCompetitionDeadline>? = null,
)

data class RemoteCompetitionDeadline(
    val id: String? = null,
    val kind: String? = null,
    val precision: String? = null,
    val timezone: String? = null,
    /** [CompetitionVerification] 之一；只有 VERIFIED 才会被服务端下发。 */
    val verificationStatus: String? = null,
    val deadlineAt: String? = null,
    val deadlineDate: String? = null,
    /** 服务端给的人话展示串（DATE 时是「当日截止，时间以原文为准」）。 */
    val display: String? = null,
    /** 能不能倒计时。UNCONFIRMED 恒 false —— 不要自己重算这个判断。 */
    val countable: Boolean? = null,
    val sourceExcerpt: String? = null,
    val revision: Int? = null,
)

data class RemoteCompetitionCatalog(
    val schoolId: String? = null,
    val revision: String? = null,
    val updatedAt: String? = null,
    val items: List<RemoteCompetition>? = null,
)

/** 我的订阅项：带赛事当前状态与「是否改期/失效」。 */
data class RemoteSubscription(
    val id: String? = null,
    val eventId: String? = null,
    val deadlineId: String? = null,
    val enabled: Boolean? = null,
    val reminderPolicy: String? = null,
    val revision: Int? = null,
    val updatedAt: String? = null,
    val title: String? = null,
    val organizer: String? = null,
    val sourceUrl: String? = null,
    val competitionStatus: String? = null,
    /** 赛事被下架 / 期限被改期作废 —— 界面要说「已失效」，不能悄悄消失。 */
    val invalidated: Boolean? = null,
    /** 赛事改过版，订阅者手里的日期已经旧了 → 提示「时间已更新」。 */
    val changed: Boolean? = null,
    val deadline: RemoteCompetitionDeadline? = null,
)

data class RemoteSubscriptionList(val items: List<RemoteSubscription>? = null)

/** 订阅/取消的请求体。 */
data class SubscriptionRequest(
    val eventId: String,
    val deadlineId: String,
    val enabled: Boolean,
)

/** 截止节点的种类。 */
object CompetitionKind {
    const val REGISTRATION = "REGISTRATION"
    const val SCHOOL_SUBMIT = "SCHOOL_SUBMIT"
    const val WORK_SUBMIT = "WORK_SUBMIT"
    const val COMPETITION_END = "COMPETITION_END"

    fun labelOf(value: String?): String = when (value) {
        REGISTRATION -> "报名截止"
        SCHOOL_SUBMIT -> "校内提交截止"
        WORK_SUBMIT -> "作品提交截止"
        COMPETITION_END -> "比赛结束"
        else -> "截止"
    }

    /** 展示优先级：报名在前、比赛结束在后（用户最关心什么时候得交东西）。 */
    fun orderOf(value: String?): Int = when (value) {
        REGISTRATION -> 0
        SCHOOL_SUBMIT -> 1
        WORK_SUBMIT -> 2
        COMPETITION_END -> 3
        else -> 9
    }
}

object CompetitionPrecision {
    const val DATE = "DATE"
    const val DATETIME = "DATETIME"
}

object CompetitionVerification {
    const val VERIFIED = "VERIFIED"
    const val UNCONFIRMED = "UNCONFIRMED"
}

object CompetitionStatus {
    const val DRAFT = "DRAFT"
    const val PUBLISHED = "PUBLISHED"
    const val ARCHIVED = "ARCHIVED"
}
