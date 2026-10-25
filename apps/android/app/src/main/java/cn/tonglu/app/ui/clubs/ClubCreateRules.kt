package cn.tonglu.app.ui.clubs

/**
 * 创建社团表单的**全部判定规则**——纯 Kotlin、只收标量，不接任何 Compose/数据模型。
 *
 * 为什么抽出来（同 B 包 `ContextWordRules` 的思路）：「4 字简介为什么点不了创建」
 * 这类判定如果摊在 composable 里，就只剩「能编译」这一条保证；抽成纯函数才能把
 * 「不足时说清还差几字」「不满足不发请求」变成**会红的单测**（UI-REPAIR §3 验收矩阵）。
 *
 * 🔴 这里是服务端 `CreateClubDto` 口径的**客户端镜像**（名称 2–50、分类 2–30、简介 5–1000），
 * 改任何一条都必须同步后端 DTO 与 PC 端 `clubs.vue`，不能只删前端限制。
 */

/** 分类预设（与 PC 端 `clubs.vue` 的 `CATEGORY_PRESETS` 同一份清单，别只改一边）。 */
internal val CLUB_CATEGORY_PRESETS = listOf("文学阅读", "体育运动", "科技编程", "公益志愿", "游戏动漫", "学习互助")

/** 服务端 `CreateClubDto.name` 的 `@Length(2, 50)`。输入框按它截断，规则按它判定。 */
internal const val CLUB_NAME_MIN = 2
internal const val CLUB_NAME_MAX = 50

/** `CreateClubDto.category` 的 `@Length(2, 30)`。 */
internal const val CLUB_CATEGORY_MIN = 2
internal const val CLUB_CATEGORY_MAX = 30

/** `CreateClubDto.description` 的 `@Length(5, 1000)`。 */
internal const val CLUB_DESCRIPTION_MIN = 5
internal const val CLUB_DESCRIPTION_MAX = 1000

private fun trimmedLength(s: String): Int = s.trim().length

/**
 * 「至少 N 字，还需 M 字」——下限不足时的**就近解释**。
 * 任务书点名：简介 `test`（4 字）必须解释为什么不能提交，而不是按钮灰着让人猜。
 */
internal fun belowMinimumHint(label: String, actual: Int, min: Int): String {
    val still = min - actual
    return if (still == 1) "${label}至少 $min 字，还需 1 字" else "${label}至少 $min 字，还需 $still 字"
}

internal fun clubNameError(name: String): String? {
    val n = trimmedLength(name)
    if (n == 0) return "请输入社团名称（$CLUB_NAME_MIN-$CLUB_NAME_MAX 字）"
    if (n < CLUB_NAME_MIN) return belowMinimumHint("社团名", n, CLUB_NAME_MIN)
    return null // 上限由输入框截断保证，超上限不会到达这里（UI-REPAIR §3「须一致」——沿用现行截断）
}

internal fun clubCategoryError(category: String): String? {
    val n = trimmedLength(category)
    if (n == 0) return "请选择或输入分类（$CLUB_CATEGORY_MIN-$CLUB_CATEGORY_MAX 字）"
    if (n < CLUB_CATEGORY_MIN) return belowMinimumHint("分类", n, CLUB_CATEGORY_MIN)
    return null
}

internal fun clubDescriptionError(description: String): String? {
    val n = trimmedLength(description)
    if (n == 0) return "社团介绍至少 $CLUB_DESCRIPTION_MIN 字"
    if (n < CLUB_DESCRIPTION_MIN) return belowMinimumHint("社团介绍", n, CLUB_DESCRIPTION_MIN)
    return null
}

/** 三条都过才允许提交——服务端还会再校验，这里只是不发**注定被拒**的请求。 */
internal fun clubCreateReady(name: String, category: String, description: String): Boolean =
    clubNameError(name) == null && clubCategoryError(category) == null && clubDescriptionError(description) == null

/** 按钮下方的一条提示：取**第一条**不满足的，不铺一屏红字（DESIGN-SYSTEM §2「就近提示」）。 */
internal fun clubCreateFirstHint(name: String, category: String, description: String): String? =
    clubNameError(name) ?: clubCategoryError(category) ?: clubDescriptionError(description)
