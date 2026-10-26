package cn.tonglu.app.ui.square

/**
 * 广场卡片正文的**纯文本加工**（无 Compose 依赖，可单测）。
 *
 * 拆出来的原因：`#话题#` 高亮和分享文案都是「边界条件一堆、肉眼很难穷举」的逻辑，
 * 而 Compose 里的渲染结果没法在 JVM 单测里断言 —— 只有把判定抽成纯函数才钉得住
 * （见 `SquareFeedTextTest`）。
 */

/**
 * 摘要最多显示几行。
 *
 * 录屏（plan §三.1）里广场是**紧凑信息流**：一屏能看到三条左右卡片。
 * 6 行太长会把每条卡撑到大半屏，所以收到 **4 行**；超出走「查看更多」展开（能力不变）。
 */
internal const val EXCERPT_MAX_LINES = 4

/**
 * 正文里 `#话题#` 的区间（**含两端的 `#`**，和酷安的高亮范围一致）。
 *
 * 规则（每一条都对应一个会误判的真实输入，逐条配了单测）：
 * - 结构 = `#` + 1..24 个「非空白、非 `#`」字符 + `#`；
 * - 从前往后**贪心但不重叠**：`#a#b#c#` 得到 `#a#` 与 `#b#`，不会出现 `#a#b#` 这种跨段高亮。
 *
 * ## 故意**不**做「井号前面不能是字母」这类边界判断
 * 试过加，代价是 `abc#话题#` 里的话题**不亮** —— 而中文正文里 `#` 紧贴 CJK 是常规写法，
 * 漏亮的概率远高于误亮。误亮只是多染几个字，漏亮是「话题高亮整个没生效」。
 * 这条取舍是**有意的**，靠单测 `字母紧贴井号也认` 钉住，别当 bug 修回去。
 *
 * 返回升序、两两不重叠，调用方可以直接拿去拼 `AnnotatedString`。
 */
internal fun topicSpans(text: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var i = 0
    while (i < text.length) {
        if (text[i] != '#') { i++; continue }
        val close = findClose(text, i + 1)
        if (close < 0) { i++; continue }
        out += i until close + 1
        i = close + 1
    }
    return out
}

/** 从 [from] 起找配对的收尾 `#`；找不到（或中间出现空白/超长）返回 -1。 */
private fun findClose(text: String, from: Int): Int {
    var j = from
    while (j < text.length && j - from < 24) {
        val c = text[j]
        if (c == '#') {
            // `##` 连写不是话题（空名字），skip 掉
            return if (j == from) -1 else j
        }
        if (c.isWhitespace()) return -1
        j++
    }
    return -1
}

/**
 * 系统分享面板要发的正文。
 *
 * 🔴 **不发链接**：帖子没有对外可访问的 URL，编一个出来就是假链接。
 * 所以只发「分类 + 标题 + 摘要 + 出处」这一段纯文本；末尾如实写「来自苔米厸 App」。
 *
 * @param categoryName 已翻译好的分类名（`WALL` → `校园动态`）
 * @param truncated    摘要是否被截断（截断了才补省略号，否则短帖会多条尾巴）
 */
internal fun shareText(categoryName: String, title: String?, excerpt: String, truncated: Boolean = false): String {
    val head = title?.trim().orEmpty().ifBlank { excerpt.trim().take(24) }
    val body = excerpt.trim().let { if (truncated) "$it…" else it }
    return buildString {
        append("【").append(categoryName).append("】")
        append(head)
        if (body.isNotBlank() && body != head) {
            append("\n\n").append(body)
        }
        append("\n\n—— 来自苔米厸 App")
    }
}

/**
 * 折叠开关的文案；返回 `null` = 这个开关不该出现。
 *
 * 🔴 **判据是「短版曾经被截断过」，不是「当前这一版有没有溢出」**。
 * 展开之后 `maxLines` 变成无限制，`hasVisualOverflow` 自然变 false ——
 * 如果拿它当判据，「查看更多」会在展开的瞬间消失，用户**再也收不回去**。
 * 调用方只在 `hasVisualOverflow == true` 时把 `truncated` 置真、**永不置假**。
 *
 * @param truncated 短版是否被截断过（调用方 sticky 记下的）
 * @param expanded  当前是否已展开
 */
internal fun expandToggleLabel(truncated: Boolean, expanded: Boolean): String? = when {
    !truncated -> null
    expanded -> "收起"
    else -> "查看更多"
}

/** 摘要的 `maxLines`：展开后不限制（用 [Int.MAX_VALUE] 比 null 好断言）。 */
internal fun excerptMaxLines(expanded: Boolean): Int =
    if (expanded) Int.MAX_VALUE else EXCERPT_MAX_LINES

/**
 * 互动栏里的回复数文案。0 条也要写出来 ——
 * 只显示一个「0」或者干脆空白，用户会以为互动栏坏了。
 */
internal fun commentCountLabel(count: Int?): String {
    val n = count ?: 0
    return if (n <= 0) "还没有回复" else "$n 条回复"
}
