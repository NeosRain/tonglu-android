package cn.tonglu.app.ui.square

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广场正文加工（话题高亮 / 分享文案 / 折叠判据）。
 *
 * ## 为什么非测不可
 * 这几段逻辑坏掉时，**界面上看不出异常**：
 * - `#话题#` 判错 → 满屏蓝色或一个都不亮，用户只会觉得「颜色怪」；
 * - 分享文案里凭空多一条「…」或少了标题 → 发出去的内容是错的，而且发出去了就收不回来；
 * - `查看更多` 用猜字数实现 → 短帖冒出「查看更多」，长帖反而不给 —— 只能靠排版结果判。
 *
 * 每条断言都能被「故意改回旧写法」弄红。
 */
class SquareFeedTextTest {

    @Test
    fun `基本话题区间含两端井号`() {
        val text = "今天#新闻#真多"
        assertEquals(listOf(2 until 6), topicSpans(text))
        assertEquals("#新闻#", text.substring(2, 6))
    }

    @Test
    fun `一行里多个话题各自成段`() {
        val text = "#选题#与#排版#都好"
        assertEquals(listOf(0 until 4, 5 until 9), topicSpans(text))
    }

    /**
     * 🔴 这条是**故意**的行为，不是漏判：井号紧贴字母一样算话题。
     * 见 `topicSpans` 的注释 —— 漏亮比误亮糟得多。改回去会让这条变红。
     */
    @Test
    fun `字母紧贴井号也认`() {
        assertEquals(listOf(3 until 8), topicSpans("abc#def#"))
        assertEquals(listOf(2 until 6), topicSpans("今天#新闻#真多"))
    }

    @Test
    fun `空话题与未闭合井号都不认`() {
        assertTrue(topicSpans("##").isEmpty())
        assertTrue(topicSpans("#没闭合").isEmpty())
    }

    @Test
    fun `话题里有空格就不算`() {
        // `#a b#` 更可能是两个独立井号，强行高亮会把整段正文染蓝
        assertTrue(topicSpans("#a b#").isEmpty())
    }

    @Test
    fun `超长不闭合的井号不算话题`() {
        assertTrue(topicSpans("#" + "字".repeat(30)).isEmpty())
    }

    @Test
    fun `不重叠不跨段`() {
        val text = "#a#b#c#"
        val spans = topicSpans(text)
        assertEquals(listOf(0 until 3, 4 until 7), spans)
        // 两两不重叠：拼成 AnnotatedString 时重叠会抛异常
        spans.zipWithNext().forEach { (a, b) -> assertTrue(a.last < b.first) }
    }

    @Test
    fun `分享文案带分类标题摘要与出处`() {
        val out = shareText("校园动态", "图书馆今晚延长到 23 点", "自习的同学注意啦")
        assertTrue(out.startsWith("【校园动态】图书馆今晚延长到 23 点"))
        assertTrue(out.contains("自习的同学注意啦"))
        assertTrue(out.endsWith("—— 来自苔米林 App"))
    }

    @Test
    fun `没有标题时用摘要开头，且不重复一遍`() {
        val out = shareText("求助", null, "有人在三号楼捡到一张校园卡吗")
        assertTrue(out.startsWith("【求助】有人在三号楼捡到一张校园卡吗"))
        // 摘要已经在标题位用过一次了，不能再重复输出第二遍
        assertEquals(1, Regex("有人在三号楼捡到一张校园卡吗").findAll(out).count())
    }

    @Test
    fun `只有真截断才补省略号`() {
        val cut = shareText("二手", "出自行车", "九成新，可小刀", truncated = true)
        val full = shareText("二手", "出自行车", "九成新，可小刀", truncated = false)
        assertTrue(cut.contains("九成新，可小刀…"))
        assertFalse(full.contains("九成新，可小刀…"))
    }

    @Test
    fun `分享文案里绝不出现链接`() {
        // 帖子没有对外可访问的 URL，编一个就是假链接
        val out = shareText("校园动态", "t", "b", truncated = true)
        assertFalse(out.contains("http"))
        assertFalse(out.contains("同路.cn"))
        assertFalse(out.contains("苔米林.cn"))
    }

    /**
     * 🔴 这条钉的是**「展开后收不回去」**那个坑：
     * 展开后排版不再溢出，若拿 `hasVisualOverflow` 当判据，开关会静默消失。
     */
    @Test
    fun `展开后开关还在，只是变成收起`() {
        assertEquals("查看更多", expandToggleLabel(truncated = true, expanded = false))
        assertEquals("收起", expandToggleLabel(truncated = true, expanded = true))
    }

    @Test
    fun `没截断就不出开关`() {
        assertNull(expandToggleLabel(truncated = false, expanded = false))
        assertNull(expandToggleLabel(truncated = false, expanded = true))
    }

    @Test
    fun `展开后不再限制行数`() {
        assertEquals(EXCERPT_MAX_LINES, excerptMaxLines(expanded = false))
        assertEquals(Int.MAX_VALUE, excerptMaxLines(expanded = true))
    }

    @Test
    fun `零回复也要给一句可点的文案`() {
        // 显示成空白的话，用户会以为互动栏坏了
        assertEquals("还没有回复", commentCountLabel(0))
        assertEquals("还没有回复", commentCountLabel(null))
        assertEquals("3 条回复", commentCountLabel(3))
    }
}
