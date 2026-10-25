package cn.tonglu.app.ui.square

import androidx.compose.ui.graphics.Color

/**
 * 广场分类（`docs/plan/61-SQUARE-CLUB-DESIGN.md` §2）。
 *
 * 🔴 **本轮不新增 `PostCategory` 枚举值**（plan/62 第一轮禁止 Prisma 迁移），
 * 所以 plan/61 的四个分类全部**映射到后端已有的 5 个值**：
 *
 * | plan/61 | 后端值 |
 * |---|---|
 * | 校园动态 | `WALL` |
 * | 求助 | `HELP` + `LOST_FOUND`（失物招领是求助的子场景，单独占一个顶部筛选项会把筛选栏撑长） |
 * | 二手 | `SECOND_HAND` |
 * | 学习资源 | `NOTES` |
 *
 * `serverKey` 与 `accepts` **故意分开**：`GET /posts?category=` 一次只吃一个值，
 * 跨多值的分类只能传 null（拉全部）再由客户端过一遍 —— 传其中一个会把另一个整类漏掉。
 * 这个「不能只传一个」的约束用 `serverQuery()` 表达，并配单测钉住。
 */
internal data class SquareCategory(
    /** 界面上显示的标签。 */
    val label: String,
    /** 该分类在后端的**单一**取值；跨多值时只取其中一个（见 `serverQuery`）。 */
    val primary: String?,
    /** 客户端过滤接收集合；空集 = 全部。 */
    val accepts: Set<String>,
)

internal object SquareCategories {

    val all: List<SquareCategory> = listOf(
        SquareCategory(label = "全部", primary = null, accepts = emptySet()),
        SquareCategory(label = "校园动态", primary = "WALL", accepts = setOf("WALL")),
        SquareCategory(label = "求助", primary = "HELP", accepts = setOf("HELP", "LOST_FOUND")),
        SquareCategory(label = "二手", primary = "SECOND_HAND", accepts = setOf("SECOND_HAND")),
        SquareCategory(label = "学习资源", primary = "NOTES", accepts = setOf("NOTES")),
    )

    /** 二手分类：提醒「平台不代收款」的那张卡只在这一类下出现，不占满整个信息流。 */
    val SECOND_HAND: SquareCategory get() = all.first { it.label == "二手" }

    /**
     * 能不能把这一分类交给服务端过滤。
     *
     * 跨多个后端值（求助 = HELP + LOST_FOUND）时返回 null ——
     * 交一个值给服务端会把另一个整类静默漏掉，那比不过滤更糟。
     */
    fun serverQuery(category: SquareCategory): String? =
        if (category.accepts.size == 1) category.primary else null

    /** 客户端过滤。`accepts` 为空 = 不过滤。 */
    fun matches(category: SquareCategory, postCategory: String): Boolean =
        category.accepts.isEmpty() || postCategory in category.accepts

    /** 按标签取分类；找不到就退回「全部」（保存的分类值在升级后可能失效）。 */
    fun byLabel(label: String): SquareCategory = all.firstOrNull { it.label == label } ?: all.first()
}

/**
 * 列表的四种呈现态。抽成纯函数是为了能单测 ——
 * 「有内容却显示空态」「失败被 loading 盖住」这两种错都只有在这层才看得见。
 */
internal enum class SquareListPhase { CONTENT, ERROR, LOADING, EMPTY_ALL, EMPTY_FILTERED }

/**
 * @param totalCount 服务端回来的**全部**帖子数（未经搜索/分类过滤）
 * @param shownCount 过滤后真正要显示的数量
 * @param hasError   本次请求是否失败（**帖子自己的** `AppUiState.postsError` 非空；
 *                   共用的 `error` 会被任何并发成功请求清掉，不能当判据）
 */
internal fun squareListPhase(
    loading: Boolean,
    totalCount: Int,
    shownCount: Int,
    hasError: Boolean,
): SquareListPhase = when {
    shownCount > 0 -> SquareListPhase.CONTENT
    hasError -> SquareListPhase.ERROR
    loading -> SquareListPhase.LOADING
    totalCount == 0 -> SquareListPhase.EMPTY_ALL
    else -> SquareListPhase.EMPTY_FILTERED
}

/**
 * 分类的彩色识别（plan/61 §3「图标保留彩色分类识别」）。
 *
 * 颜色值单独抽成 `Long` 而不是直接返回 `Color`：这样单测**不依赖 Compose 运行时**
 * （这套测试是纯 JVM 的，不引 Robolectric）。`categoryTint` 只是它的一层包装。
 */
internal fun categoryColorArgb(postCategory: String): Long = when (postCategory) {
    "WALL" -> 0xFF2563EB
    "HELP" -> 0xFFD9480F
    "LOST_FOUND" -> 0xFF9C36B5
    "SECOND_HAND" -> 0xFF099268
    "NOTES" -> 0xFF6741D9
    else -> 0xFF6B7280
}

internal fun categoryTint(postCategory: String): Color = cn.tonglu.app.ui.noticeForeground(Color(categoryColorArgb(postCategory)))
