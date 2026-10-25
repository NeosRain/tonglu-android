package cn.tonglu.app.ui.square

/**
 * 「我的内容」的四种呈现态。
 *
 * 抽成纯函数（与 `squareListPhase` 同款）是为了能单测 ——
 * 「失败被显示成空态」这个错只有在这一层才看得见：一旦 UI 里把判断顺序写反，
 * 失败态和真空态长得**一模一样**，编译、走查都不一定抓得到。
 */
internal enum class MyPostsPhase { CONTENT, ERROR, LOADING, EMPTY }

/**
 * @param count    当前已拿到的条数
 * @param hasError 本次请求是否失败（**「我的内容」自己的** `AppUiState.myPostsError` 非空；
 *                 共用的 `error` 会被任何并发成功请求清掉，不能当判据 —— 见 BUGS A14）
 *
 * 🔴 `hasError` 必须排在 `loading` 与空态**之前**。失败时列表同样是空的，
 * 先判空就会把「没拉到」说成「还没有发布过内容」——对一个**确实发过**内容的用户，
 * 这句话比报错更糟：他会以为内容被删了，然后去重发、去问客服。
 */
internal fun myPostsPhase(
    loading: Boolean,
    count: Int,
    hasError: Boolean,
): MyPostsPhase = when {
    count > 0 -> MyPostsPhase.CONTENT
    hasError -> MyPostsPhase.ERROR
    loading -> MyPostsPhase.LOADING
    else -> MyPostsPhase.EMPTY
}
