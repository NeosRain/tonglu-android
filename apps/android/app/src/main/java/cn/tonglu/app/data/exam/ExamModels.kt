package cn.tonglu.app.data.exam

/**
 * 考试事件模型（`docs/plan/54` M4/M8）。
 *
 * ## 日期一律用 `yyyy-MM-dd` 字符串，不用 `LocalDate`
 * minSdk 24 且工程**没有开 coreLibraryDesugaring**，`java.time` 用不了。
 * 转成整数算天数的工作放在 [ExamCountdown] 里（UTC 基准的 epochDay 纯算术）。
 *
 * ## 为什么分类/来源用 String 而不是 enum
 * 这些值要进 Gson，enum 一旦改名，老用户磁盘上的旧值就反序列化不出来 —— 而考试
 * 是用户一条条敲进去的，丢了就得重填。String + 常量最稳，读的时候未知值退化为 OTHER。
 */
data class ExamEvent(
    val id: String,
    val title: String,
    /** 归一化科目 key（[ExamSubjects] 里的常量），M7 学习页联动靠它 */
    val subjectKey: String? = null,
    /** 考试日期 `yyyy-MM-dd` */
    val date: String,
    /** 时段原文，如「9:00-11:20」，仅展示不参与计算 */
    val timeText: String? = null,
    /** [ExamCategory] 之一 */
    val category: String = ExamCategory.OTHER,
    /** [ExamSource] 之一 */
    val source: String = ExamSource.MANUAL,
    /** 关联国家考证库条目（从库里选的才有） */
    val nationalExamId: String? = null,
    /** 「正在备考」= true，首页才显示 */
    val pinned: Boolean = true,
    /** 这个日期是哪来的 —— 决定要不要给用户看「待核实」（见 [ExamDateSource]） */
    val dateSource: String = ExamDateSource.USER,
    /** 日期来源的人话说明，如「来源：中国教育考试网 · 更新于 2026-10-04」 */
    val sourceLabel: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** 考试结束 30 天后自动打上，仅归档不删除 */
    val archivedAt: Long? = null,
)

object ExamCategory {
    /** 国家考证（四六级 / NCRE / 考研 / 教资…） */
    const val NATIONAL = "national"

    /** 校内考试（期末、期中） */
    const val SCHOOL = "school"

    /** 其它（用户自建的杂项） */
    const val OTHER = "other"

    fun labelOf(value: String): String = when (value) {
        NATIONAL -> "国家考证"
        SCHOOL -> "校内考试"
        else -> "其它"
    }
}

object ExamSource {
    /** 用户手动创建 */
    const val MANUAL = "manual"

    /** 从教务导入 */
    const val JW = "jw"

    /** 来自国家考证库（内置年度表 / 规则推算 / 服务端抓取） */
    const val NATIONAL = "national"
}

/**
 * 日期的可靠程度 —— **必须能让用户一眼看出来**。
 *
 * 这是 `docs/plan/54` M5 的硬要求：绝不给用户一个看着正常其实是错的日期。
 * 每一次展示都要说清「这个日期是哪来的」。
 */
object ExamDateSource {
    /** 用户自己填的 / 官方抓取确认过的 —— 按原样展示 */
    const val USER = "user"
    const val OFFICIAL = "official"

    /** 抓不到官方数据，用的是去年同场次日期 —— 加「?」并提示「待核实」 */
    const val LAST_YEAR = "lastYear"

    /** 连去年数据都没有，按往年规律算的 —— 提示「按往年规律推算」 */
    const val RULE = "rule"

    fun needsVerify(value: String): Boolean = value == LAST_YEAR || value == RULE

    /** 天数后面那句小字，null 表示不用额外说明 */
    fun hintOf(value: String): String? = when (value) {
        LAST_YEAR -> "日期待核实，以官方公告为准"
        RULE -> "按往年规律推算，以官方公告为准"
        else -> null
    }
}

/**
 * 科目归一化 key → 展示名。
 *
 * M7 的联动映射表（`ExamLink`）也用这些 key，两边必须同源，不然「加进备考」
 * 之后跳不过去。
 */
object ExamSubjects {
    const val CET4 = "cet4"
    const val CET6 = "cet6"
    const val NCER1 = "ncre1"
    const val NCER2 = "ncre2"
    const val NCER3 = "ncre3"
    const val NCER4 = "ncre4"
    const val KAOYAN = "kaoyan"
    const val JIAOSHI = "jiaoshi"
    const val PUTONGHUA = "putonghua"
    const val CUSTOM = "custom"

    fun labelOf(key: String?): String = when (key) {
        CET4 -> "英语四级"
        CET6 -> "英语六级"
        NCER1 -> "计算机一级"
        NCER2 -> "计算机二级"
        NCER3 -> "计算机三级"
        NCER4 -> "计算机四级"
        KAOYAN -> "考研"
        JIAOSHI -> "教师资格"
        PUTONGHUA -> "普通话"
        else -> "自定义"
    }
}
