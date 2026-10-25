package cn.tonglu.app.ui.english.examentry

import kotlin.math.roundToInt

/**
 * 文章列表可选筛选。年份／月份／阅读类型都是**可选**：不选=不过滤，
 * 不做连续必选弹窗（用户 2026-10-14 拍板）。默认实例 = 不筛选。
 */
data class ExamListFilter(
    val year: Int? = null,
    val session: String? = null,
    val readingType: String? = null,
) {
    val isDefault: Boolean get() = year == null && session == null && readingType == null

    /** 筛选条上的紧凑摘要；默认筛选返回 null（不显示摘要徽标）。 */
    fun summary(): String? {
        if (isDefault) return null
        return listOfNotNull(
            year?.let { "${it}年" },
            session,
            readingType,
        ).joinToString(" · ")
    }
}

data class ExamFilterOptions(
    val years: List<Int>,
    val sessions: List<String>,
    val readingTypes: List<String>,
) {
    val isEmpty: Boolean get() = years.isEmpty() && sessions.isEmpty() && readingTypes.isEmpty()
}

/**
 * 从**当前列表的全量行**派生可选筛选项：数据里没有的值不编造。
 * 年份倒序（最新在前）；月份/类型保持数据出现顺序。
 */
fun examFilterOptions(rows: List<ReadingArticleRow>): ExamFilterOptions = ExamFilterOptions(
    years = rows.mapNotNull { it.examMeta?.year }.distinct().sortedDescending(),
    sessions = rows.mapNotNull { it.examMeta?.session }.distinct(),
    readingTypes = rows.mapNotNull { it.examMeta?.readingType }.distinct(),
)

/**
 * 本地筛选。任一筛选项激活时，**没有对应元数据的行会被排除**——
 * 它们不声明任何年份/月份/类型，排除比「假装匹配」诚实；
 * 全默认筛选时原样返回。
 */
fun applyExamFilter(rows: List<ReadingArticleRow>, filter: ExamListFilter): List<ReadingArticleRow> {
    if (filter.isDefault) return rows
    return rows.filter { row ->
        val meta = row.examMeta
        (filter.year == null || meta?.year == filter.year) &&
            (filter.session == null || meta?.session == filter.session) &&
            (filter.readingType == null || meta?.readingType == filter.readingType)
    }
}

/** 列表页空态派生：待收录（无筛选且没数据）与无匹配（筛选筛空了）是两个不同的空。 */
sealed interface ListPageStatus {
    data object HasRows : ListPageStatus
    data object AwaitCatalog : ListPageStatus
    data object NoMatch : ListPageStatus
}

fun listPageStatus(allRows: List<ReadingArticleRow>, filter: ExamListFilter): ListPageStatus = when {
    applyExamFilter(allRows, filter).isNotEmpty() -> ListPageStatus.HasRows
    filter.isDefault -> ListPageStatus.AwaitCatalog
    else -> ListPageStatus.NoMatch
}

/** 行内进度文案。null 与 0f 对用户都呈现「未开始」，内部语义仍区分（无记录 vs 未开始）。 */
fun progressLabel(progress: Float?): String = when {
    progress == null || progress <= 0f -> "未开始"
    progress >= 1f -> "已读完"
    else -> "读到${(progress * 100).roundToInt()}%"
}
