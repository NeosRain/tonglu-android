package cn.tonglu.app.data

/**
 * 发帖正文的长度规则。
 *
 * ## 为什么要有它
 * 后端 `CreatePostDto.body` 是 `@Length(1, 20000)`（`backend/src/posts/posts.dto.ts`），
 * 而客户端**一处护栏都没有** —— 全项目唯一一条没护栏的长文本。
 * 撞上时的表现是：点「发布」→ 一个 400 → 界面只说「请求失败」，
 * **不说是哪一项超了、超了多少**，用户只会反复点。
 *
 * ## 为什么**不**做截断
 * 直接 `take(20000)` 看着更省事，但会把用户**写了一半的长文悄悄砍掉**，
 * 而且他未必当场发现（滚动位置未必在末尾）。那比返回一个 400 更糟 ——
 * 400 至少还让他知道"没发出去"。
 * 所以这里的做法是：**原文一字不动**，超限就拦住「发布」+ 在顶栏明说超了多少字。
 *
 * ## 两边数字怎么保持一致
 * `scripts/audit_cross_end_limits.py` 的「帖子正文」契约会同时读后端 DTO 的
 * `@Length(…, 20000)` 和这里 [POST_BODY_MAX] 这个名字，**改一边就红**。
 * 别把 20000 再抄到别处 —— 要用就读这个常量。
 */
internal const val POST_BODY_MAX = 20000

/**
 * 正文长度的纯逻辑（没有 Compose / Android 依赖），所以能直接被 JVM 单测打。
 *
 * ⚠️ **口径要和后端一致**：`class-validator` 的 `@Length` 量的是 JS 的 `value.length`
 * = **UTF-16 code unit 数**，而 Kotlin 的 `String.length` 也是 UTF-16 code unit 数 ——
 * 两边天然同口径，连 emoji（代理对，算 2）都一样。**别改成码点数**（`codePointCount`），
 * 那会两边差出几倍，变成一个新的跨端口径 bug。
 */
internal object PostBodyLimit {

    /**
     * 到这个字数就把计数显示出来。
     *
     * 为什么要提前：只在撞顶时才提示的话，用户永远是「写完了才被告知」，
     * 那时他已经很难轻松压缩了。留 10% 余量让他从容收尾。
     */
    const val WARN_AT = POST_BODY_MAX / 10 * 9      // 18000，整除，不引浮点

    /** 用了多少字。 */
    fun used(text: String): Int = text.length

    /** 还差多少到上限；已经超了就返回 0（**不返回负数**，否则界面会显示"还可输入 -37 字"）。 */
    fun remaining(text: String): Int = (POST_BODY_MAX - text.length).coerceAtLeast(0)

    /** 超了多少字；没超返回 0。 */
    fun overBy(text: String): Int = (text.length - POST_BODY_MAX).coerceAtLeast(0)

    /**
     * 是否已经超出上限。
     *
     * **必须是 `>` 不是 `>=`** —— 正好 [POST_BODY_MAX] 字是合法的（后端 `@Length(1, 20000)`
     * 的闭区间）。写成 `>=` 会让「刚好写满」的用户发不出去，而他怎么删都还差一个字。
     */
    fun overLimit(text: String): Boolean = text.length > POST_BODY_MAX

    /** 是否该把字数提示显示出来。 */
    fun shouldWarn(text: String): Boolean = text.length >= WARN_AT
}
