package cn.tonglu.app.data.exam

import java.util.Calendar

/**
 * 国家考证**内置年度表**（`docs/plan/54` M5 的 L2 层），照 `Holidays.kt` 的写法：
 * 新一年的安排确定了就在这里加一行，查询接口不变。
 *
 * ## 为什么必须有这一层
 * 服务端抓取（L3）**迟早会失败**（官方站点有 WAF，本项目在蓝奏云上已被阿里云 WAF 堵过一次）。
 * 最坏情况不能是「没有数据」—— 那时用户看到的是空白，比看到「待核实」糟糕得多。
 * 所以内置表必须能**独立撑住**，抓不到时用它并如实标注。
 *
 * ## 铁律：每一条都要说清「这个日期是哪来的」
 * `dateSource` 决定 UI 要不要给天数加「?」：`OFFICIAL` 是已核实过的，`RULE` / `LAST_YEAR`
 * 是推算的，**必须标出来**。绝不给用户一个看着正常其实是错的日期。
 *
 * 数据来源核实于 2026-10-04（详见 `docs/plan/54` 附录 B）。
 */
object NationalExamSeed {

    /** 日历页的筛选分类（沿用附录 C 的 9 分类，再加「校内自建」） */
    object Group {
        const val KAOYAN = "升学考研"
        const val ENGLISH = "英语"
        const val COMPUTER = "计算机"
        const val TEACHER = "教资"
        const val FINANCE = "财会金融"
        const val LAW_ENG = "法律工程"
        const val MEDICAL = "医学"
        const val LANGUAGE = "语言其他"
        const val SCHOOL = "校内自建"

        val ALL = listOf(KAOYAN, ENGLISH, COMPUTER, TEACHER, FINANCE, LAW_ENG, MEDICAL, LANGUAGE, SCHOOL)
    }

    data class NationalExam(
        val id: String,
        val title: String,
        val subjectKey: String,
        val group: String,
        /** 考试日期 `yyyy-MM-dd` */
        val date: String,
        /** 多日考试的结束日期（NCRE 考三天、教资面试两天） */
        val endDate: String? = null,
        val timeText: String? = null,
        /** [ExamDateSource] 之一 */
        val dateSource: String = ExamDateSource.RULE,
        /** 来源人话说明，展示在日历页底部 */
        val sourceLabel: String? = null,
    )

    private data class Raw(
        val id: String,
        val title: String,
        val subjectKey: String,
        val group: String,
        val date: String,
        val endDate: String? = null,
        val dateSource: String = ExamDateSource.RULE,
        val sourceLabel: String? = null,
    )

    /**
     * 2026 年**已核实**的场次（来源：中国教育考试网 CET 报名公告、教育部《2027 年
     * 全国硕士研究生招生工作管理规定》、上海市教育考试院考前公告、黑龙江省招生考试院公告）。
     *
     * 只列 2026-10-04 之后还剩下的 —— 已经考完的不展示（那是日历页「已结束」的事，
     * 首页只关心「下一场」）。
     */
    private val VERIFIED_2026: List<Raw> = listOf(
        Raw(
            id = "cet-set-2026-2", title = "全国大学英语四六级口试（CET-SET）",
            subjectKey = ExamSubjects.CET4, group = Group.ENGLISH,
            date = "2026-11-21", endDate = "2026-11-22",
            dateSource = ExamDateSource.OFFICIAL, sourceLabel = "来源：中国教育考试网 CET 报名公告",
        ),
        Raw(
            id = "cet4-2026-2", title = "英语四级（CET-4）笔试",
            subjectKey = ExamSubjects.CET4, group = Group.ENGLISH,
            date = "2026-12-12",
            dateSource = ExamDateSource.OFFICIAL, sourceLabel = "来源：中国教育考试网 CET 报名公告",
        ),
        Raw(
            id = "cet6-2026-2", title = "英语六级（CET-6）笔试",
            subjectKey = ExamSubjects.CET6, group = Group.ENGLISH,
            date = "2026-12-12",
            dateSource = ExamDateSource.OFFICIAL, sourceLabel = "来源：中国教育考试网 CET 报名公告",
        ),
        Raw(
            id = "kaoyan-2027", title = "全国硕士研究生招生考试（初试）",
            subjectKey = ExamSubjects.KAOYAN, group = Group.KAOYAN,
            date = "2026-12-19", endDate = "2026-12-20",
            dateSource = ExamDateSource.OFFICIAL,
            sourceLabel = "来源：教育部《2027 年全国硕士研究生招生工作管理规定》",
        ),
        Raw(
            id = "jiaoshi-interview-2026-2", title = "教师资格面试",
            subjectKey = ExamSubjects.JIAOSHI, group = Group.TEACHER,
            date = "2026-12-05", endDate = "2026-12-06",
            dateSource = ExamDateSource.OFFICIAL, sourceLabel = "来源：各省教育考试院考前公告",
        ),
    )

    /**
     * 2027 年**按往年规律推算**的场次 —— 官方还没发文，所以一律标 `RULE`，
     * UI 上天数后面要带「?」。等公告出来就把它们挪进已核实表。
     */
    private val RULED_2027: List<Raw> = listOf(
        Raw(
            id = "ncre-2027-1", title = "全国计算机等级考试（NCRE）上半年",
            subjectKey = ExamSubjects.NCER2, group = Group.COMPUTER,
            date = "2027-03-27", endDate = "2027-03-29",
            dateSource = ExamDateSource.RULE, sourceLabel = "按往年规律推算：3 月最后一个周末",
        ),
        Raw(
            id = "jiaoshi-2027-1", title = "教师资格笔试（上半年）",
            subjectKey = ExamSubjects.JIAOSHI, group = Group.TEACHER,
            date = "2027-03-06",
            dateSource = ExamDateSource.RULE, sourceLabel = "按往年规律推算：3 月第一个周六",
        ),
        Raw(
            id = "cet4-2027-1", title = "英语四级（CET-4）笔试",
            subjectKey = ExamSubjects.CET4, group = Group.ENGLISH,
            date = "2027-06-12",
            dateSource = ExamDateSource.RULE, sourceLabel = "按往年规律推算：6 月第二个周六",
        ),
        Raw(
            id = "cet6-2027-1", title = "英语六级（CET-6）笔试",
            subjectKey = ExamSubjects.CET6, group = Group.ENGLISH,
            date = "2027-06-12",
            dateSource = ExamDateSource.RULE, sourceLabel = "按往年规律推算：6 月第二个周六",
        ),
    )

    /**
     * 全部内置场次（已核实 + 推算）。
     *
     * 普通话水平测试**刻意不在这里**：它没有全国统一日期，各省/市自行组织，
     * 抓了也是错的 —— 只能用户手动录入（plan 54 Q5）。
     */
    private val ALL_RAW: List<Raw> = VERIFIED_2026 + RULED_2027

    /**
     * 内置表**全部**场次（不按今天过滤）。
     *
     * `ExamCalendarFeed` 需要这一份：服务端年度表也是全量的，两边结构要一样，
     * 过滤交给调用方按「今天」做（服务端那份可能比内置表更新，过滤时机必须一致）。
     */
    fun all(): List<NationalExam> = ALL_RAW.map { raw ->
        NationalExam(
            id = raw.id, title = raw.title, subjectKey = raw.subjectKey, group = raw.group,
            date = raw.date, endDate = raw.endDate, dateSource = raw.dateSource,
            sourceLabel = raw.sourceLabel,
        )
    }

    /**
     * 取还没考的场次，按日期升序。
     *
     * @param todayEpochDay 「今天」（北京时间，由调用方算好传进来，便于测试）
     */
    fun upcoming(todayEpochDay: Long): List<NationalExam> =
        all()
            .filter {
                val epochDay = ExamCountdown.dateToEpochDay(it.date) ?: return@filter false
                epochDay >= todayEpochDay
            }
            .sortedWith(compareBy<NationalExam> { it.date }.thenBy { it.title })

    /**
     * 内置表**整体过期**时的兜底（L1 规则层）：从今年起按规则算出下一场。
     *
     * 正常走不到这里（内置表里同时有 2026 和 2027 两年），但只要有人忘了更新表，
     * 用户看到的就是「按往年规律推算」而不是空白 —— 这正是 L1 存在的意义。
     */
    fun fallbackByRule(todayEpochDay: Long, todayYear: Int): List<NationalExam> {
        val rules = listOf(
            Triple(ExamSubjects.CET4, "英语四级（CET-4）笔试", Group.ENGLISH) to
                ExamRules.NthWeekday(month = 6, weekday = Calendar.SATURDAY, nth = 2),
            Triple(ExamSubjects.CET4, "英语四级（CET-4）笔试", Group.ENGLISH) to
                ExamRules.NthWeekday(month = 12, weekday = Calendar.SATURDAY, nth = 2),
            Triple(ExamSubjects.NCER2, "全国计算机等级考试（NCRE）", Group.COMPUTER) to
                ExamRules.LastWeekend(month = 3),
            Triple(ExamSubjects.JIAOSHI, "教师资格笔试", Group.TEACHER) to
                ExamRules.NthWeekday(month = 3, weekday = Calendar.SATURDAY, nth = 1),
            Triple(ExamSubjects.KAOYAN, "全国硕士研究生招生考试（初试）", Group.KAOYAN) to
                ExamRules.NthWeekday(month = 12, weekday = Calendar.SATURDAY, nth = 3),
        )
        return rules.mapNotNull { (meta, rule) ->
            val date = ExamRules.nextFrom(rule, todayEpochDay, todayYear) ?: return@mapNotNull null
            NationalExam(
                id = "rule-${meta.first}-$date",
                title = meta.second,
                subjectKey = meta.first,
                group = meta.third,
                date = date,
                dateSource = ExamDateSource.RULE,
                sourceLabel = "按往年规律推算（内置年度表已过期，请更新）",
            )
        }
    }
}
