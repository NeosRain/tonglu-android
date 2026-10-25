package cn.tonglu.app.data

/**
 * 成绩数据与「这门课到底过没过」的判定。
 *
 * 背景（用户提的原版痛点）：教务系统里一门课的**每次考试都是一条独立记录** ——
 * 正考一条，之后每次重修再各一条，而且旧记录不会因为后来通过了就消失。
 * 企业微信把成绩分成「已过」「未过」两个列表，同一门课可能在两边都出现，
 * 于是「挂了三次、第四次过了」这种课，用户根本看不出它最终算不算过。
 *
 * 这里的做法是**按课程聚合**（[CourseScore]），只给一个结论：
 *  · [CourseScore.passed] 只要有任何一次及格就算过；
 *  · [CourseScore.retakeCount] 重修次数 = 记录数 - 1（第一条是正考）；
 *  · [CourseScore.effective] 生效成绩 = 最早那次及格（真正拿来算学分的那次）。
 */
data class ScoreRecord(
    val courseName: String,
    /** 原始成绩文本："78" / "良好" / "通过" / "缺考" */
    val scoreText: String = "",
    /** 百分制数值（能解析出来才有） */
    val scoreValue: Double? = null,
    val credit: Double? = null,
    val gradePoint: Double? = null,
    /** 学年学期编码，形如 2023-2024-1（-1 秋 / -2 春） */
    val termCode: String? = null,
    /** 考试类型：正常考试 / 重修 / 补考 */
    val examType: String? = null,
    /** 课程性质：必修 / 选修 */
    val courseType: String? = null,
    val passed: Boolean = false,
)

/** 一门课的全部考试记录（已按学年学期排序），是「过没过」结论的唯一来源 */
data class CourseScore(
    val name: String,
    val attempts: List<ScoreRecord>,
) {
    /** 有任何一次及格 → 这门课就算过了 */
    val passed: Boolean get() = attempts.any { it.passed }

    /** 重修次数 = 记录数 - 1（首条视作正考） */
    val retakeCount: Int get() = (attempts.size - 1).coerceAtLeast(0)

    /** 挂过几次 */
    val failCount: Int get() = attempts.count { !it.passed }

    /** 生效成绩：过了取**最早那次及格**（有效成绩），没过取最后一次 */
    val effective: ScoreRecord?
        get() = attempts.firstOrNull { it.passed } ?: attempts.lastOrNull()

    /** 离现在最近的一次考试 */
    val latest: ScoreRecord? get() = attempts.lastOrNull()

    /** 需要展开看细节的（考过不止一次） */
    val hasHistory: Boolean get() = attempts.size > 1
}

data class ScoreSummary(
    val courseCount: Int,
    val passedCount: Int,
    val failedCount: Int,
    /** 有重修记录的课程数 */
    val retakenCourses: Int,
    /** 已通过课程的学分合计 */
    val passedCredits: Double,
)

object ScoreBoard {

    /** 按课程聚合 + 排序：未通过的排最前，然后有重修的，最后按名称 */
    fun byCourse(records: List<ScoreRecord>): List<CourseScore> {
        val grouped = LinkedHashMap<String, MutableList<ScoreRecord>>()
        records.forEach { record ->
            val key = normalizeName(record.courseName)
            if (key.isEmpty()) return@forEach
            grouped.getOrPut(key) { mutableListOf() } += record
        }
        return grouped.map { (_, list) ->
            CourseScore(
                name = list.first().courseName.trim(),
                attempts = list.sortedBy { termOrder(it.termCode) },
            )
        }.sortedWith(
            compareBy(
                { it.passed },              // 未通过(false) 在前
                { -it.retakeCount },
                { it.name },
            ),
        )
    }

    fun summary(courses: List<CourseScore>): ScoreSummary = ScoreSummary(
        courseCount = courses.size,
        passedCount = courses.count { it.passed },
        failedCount = courses.count { !it.passed },
        retakenCourses = courses.count { it.retakeCount > 0 },
        passedCredits = courses.filter { it.passed }
            .mapNotNull { it.effective?.credit }
            .sum(),
    )

    /** 课程名归一化：用来判断"这两条记录是不是同一门课" */
    fun normalizeName(raw: String): String =
        raw.replace(" ", "").replace("　", "").trim().lowercase()

    /**
     * 及格判定。优先看百分制分数，其次看等级制文案。
     * 判定不出来的（缓考、免修之类）一律按**未通过**处理 ——
     * 宁可让用户看到一门"可能没过"的课，也不要漏掉一门真正挂了的课。
     */
    fun judgePass(scoreText: String, scoreValue: Double?): Boolean {
        if (scoreValue != null) return scoreValue >= 60.0
        val text = scoreText.trim()
        if (text.isEmpty()) return false
        if (FAIL_WORDS.any { text.contains(it) }) return false
        if (PASS_WORDS.any { text.contains(it) }) return true
        return false
    }

    /** 从成绩文本里抠出百分制数值（"78" / "78.5" / "78分"）；抠不出返回 null */
    fun parseScoreValue(text: String): Double? {
        val trimmed = text.trim().replace("分", "").trim()
        val match = Regex("^(\\d{1,3})(\\.\\d{1,2})?$").find(trimmed) ?: return null
        return match.value.toDoubleOrNull()
    }

    /**
     * 成绩文本拼成带单位的人话。
     *
     * 只有**数字**才配「分」—— 等级制的「良好」「合格」、以及「缺考」这类状态，
     * 原样输出即可；无脑拼「分」会输出「缺考 分」「良好 分」这种读不通的东西。
     */
    fun scoreWithUnit(text: String?): String {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return "--"
        return if (t.first().isDigit()) "$t 分" else t
    }

    /** 学年学期排序键：2023-2024-1 → 20231（越大越晚） */
    fun termOrder(code: String?): Int {
        val match = TERM_FULL.find(code.orEmpty()) ?: return 0
        val year = match.groupValues[1].toIntOrNull() ?: return 0
        val term = match.groupValues[3].toIntOrNull() ?: 0
        return year * 10 + term
    }

    /** 2023-2024-1 → "2023-2024 学年 秋季学期"；认不出来就原样返回 */
    fun termLabel(code: String?): String {
        if (code.isNullOrBlank()) return "时间未知"
        val match = TERM_FULL.find(code) ?: return code
        val from = match.groupValues[1]
        val to = match.groupValues[2]
        val season = if (match.groupValues[3] == "2") "春季学期" else "秋季学期"
        return "$from-$to 学年 $season"
    }

    internal val TERM_FULL = Regex("(\\d{4})-(\\d{4})-(\\d)")
    internal val TERM_PAIR = Regex("(\\d{4})-(\\d{4})")

    private val FAIL_WORDS = listOf(
        "不及格", "未通过", "不合格", "缺考", "旷考", "作弊", "违纪", "取消", "无成绩", "未评",
    )

    private val PASS_WORDS = listOf(
        "通过", "合格", "及格", "优秀", "良好", "中等", "免修", "已修",
    )
}
