package cn.tonglu.app.data.english.materials

import android.content.Context
import cn.tonglu.app.data.LearningFeedCache
import cn.tonglu.app.data.ReadingLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 列表行 + 本机状态（契约 A 的 ReadingArticleRow 形状）。
 *
 * - [progress]：`null` = 没有阅读记录；`0f` = 未开始（阅读库不打 0% 空账，所以
 *   出现 0 意味着确实开过且停在开头）；其余 = 已读比例 0~1。**不伪造**。
 * - [cached]：正文已在本机缓存（离线可读）。仅 SOURCE_LINK 条目恒 false——
 *   只有原站链接的资源没有离线正文可缓存。
 */
data class ExamMaterialListItem(
    val row: ExamMaterialRow,
    val progress: Float?,
    val cached: Boolean,
)

/**
 * 本机状态合并（**纯函数**，JVM 单测直接覆盖）。
 *
 * @param progressOf 按文章 id 查阅读比例；无记录返回 null。
 * @param cachedIds 本机已有正文的文章 id 集合。
 */
fun mergeLocalState(
    rows: List<ExamMaterialRow>,
    progressOf: (String) -> Float?,
    cachedIds: Set<String>,
): List<ExamMaterialListItem> = rows.map { row ->
    if (row.isFullText) {
        val ratio = progressOf(row.articleId)
        // 阅读库对「打开就退出」不打账：record==null → 没有记录；ratio 夹在 0~1。
        ExamMaterialListItem(row, ratio?.coerceIn(0f, 1f), row.articleId in cachedIds)
    } else {
        // 仅原站链接：没有离线正文，进度与缓存都必须是「无」，不能拿别的数据冒充。
        ExamMaterialListItem(row, null, false)
    }
}

/**
 * 考试材料仓库：精读考试入口（A 的 UI）与学习资料库共用同一批文章的数据面。
 *
 * 只读。公共发布走管理后台的学习目录编辑（管理员），这里没有任何写通道；
 * 私人导入文章在本机 Room、按 owner 分桶，**不会**出现在这些公共列表里
 * （服务端目录本来就不含私人条目，见 docs/english/exam-entry-contract.md）。
 */
class ExamMaterialsRepository(private val context: Context) {

    /** 考试选择页选项（四级/六级/后续扩展，来自服务端数据配置，客户端不写死）。 */
    suspend fun options(): List<ExamMaterialOption> = withContext(Dispatchers.IO) {
        ExamMaterialsClient.api.options().mapNotNull { it.toOption() }
    }

    /** 某考试的列表（元数据 + 本机进度/缓存状态）。examType 如 "CET4"；非法值服务端 400。 */
    suspend fun list(examType: String, owner: String): List<ExamMaterialListItem> =
        fetch { ExamMaterialsClient.api.materials(examType = examType) }.let { merge(it, owner) }

    /** 「学习页推荐文章」：同一资料库里 language=en、管理员标记推荐的 FULLTEXT 材料。 */
    suspend fun recommended(owner: String): List<ExamMaterialListItem> =
        fetch { ExamMaterialsClient.api.materials(recommended = "true") }.let { merge(it, owner) }

    /**
     * 详情（元数据 + 正文）。SOURCE_LINK 条目 body 为 null——UI 应引导跳转原站，
     * 不存在「离线全文」这回事。
     */
    suspend fun detail(articleId: String): ExamMaterialDetail? = withContext(Dispatchers.IO) {
        ExamMaterialsClient.api.detail(articleId).toDetail()
    }

    // ------------------------------------------------------------ 内部

    private suspend fun fetch(call: suspend () -> RemoteExamMaterialsPage): List<ExamMaterialRow> =
        withContext(Dispatchers.IO) { call().items.orEmpty().mapNotNull { it.toRow() } }

    private suspend fun merge(rows: List<ExamMaterialRow>, owner: String): List<ExamMaterialListItem> =
        withContext(Dispatchers.IO) {
            val cachedIds = LearningFeedCache.bodies(context).keys
                .filter { id -> rows.any { it.articleId == id } }.toSet()
            val library = ReadingLibrary.of(context, owner)
            mergeLocalState(rows, progressOf = { id -> library.articleProgress(id)?.score }, cachedIds = cachedIds)
        }
}
