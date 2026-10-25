package cn.tonglu.app.ui.english.examentry

/**
 * 精读材料选择组件的数据输入模型。契约来源：`docs/english/exam-entry-contract.md` §A。
 *
 * 🔴 本组件只接状态与回调，不自行联网、不存正文、不读任何密钥；
 * 数据由宿主（C 接线）灌入。所有「未知」一律 null，不许编造或用示例冒充真题。
 */

/**
 * 考试目录项。计数与列表必须同源（EF02）：
 * - [articleCount]：该考试下已发布条目总数（含「仅原站入口」导航条）；
 * - [fullTextCount]：其中可离线精读的全文数。
 * 两者都为 0 才显示「待收录」；只有链接条目时如实说「暂无可精读全文 · 有官方入口」，
 * 不再把 0 篇全文说成「0 篇 · 材料待收录」又让点进去看到条目（数字与列表打架）。
 */
data class ExamOption(val key: String, val title: String, val articleCount: Int, val fullTextCount: Int) {
    val linkCount: Int get() = (articleCount - fullTextCount).coerceAtLeast(0)
}

/**
 * 行内考试元数据（契约字段之外的最小扩展，交付时已在执行文档记录，C 适配）。
 * 任何字段为 null 表示数据侧未知：不参与筛选，也不在行内显示。
 */
data class ExamArticleMeta(
    val year: Int? = null,
    val session: String? = null,
    val paper: String? = null,
    val readingType: String? = null,
)

/**
 * 文章列表行。progress：null=无阅读记录，0f=未开始，(0,1]=已读比例；不能伪造。
 * fullText=false 表示仅原站链接（availability=SOURCE_LINK），不承诺离线全文。
 */
data class ReadingArticleRow(
    val articleId: String,
    val revision: String,
    val title: String,
    val subtitle: String,
    val progress: Float?,
    val cached: Boolean,
    val fullText: Boolean,
    val examMeta: ExamArticleMeta? = null,
)

/** 「继续阅读」条：精读入口顶部，无记录时传 null 即不显示。 */
data class ContinueReadingInfo(
    val articleId: String,
    val title: String,
    val progress: Float,
)

/** 考试目录页状态：Loading / Content / Failed 三态，失败由宿主 onRetry 重试。 */
sealed interface ExamCatalogState {
    data object Loading : ExamCatalogState
    data class Content(val exams: List<ExamOption>) : ExamCatalogState
    data class Failed(val message: String) : ExamCatalogState
}

/**
 * 文章列表页状态：Loading / Content / Failed。
 * 🔴 Content 传**未筛选**的全量行：筛选由组件本地应用（见 [applyExamFilter]），
 * 宿主可通过 onFilterChanged 同步刷新数据，但不要传已筛选结果，否则会被二次过滤。
 * 空列表的「待收录 / 无匹配」由 [listPageStatus] 派生，不由调用方标记。
 */
sealed interface ArticleListState {
    data object Loading : ArticleListState
    data class Content(val rows: List<ReadingArticleRow>) : ArticleListState
    data class Failed(val message: String) : ArticleListState
}
