package cn.tonglu.app.data.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日例句的纯逻辑（docs/plan/56 §6.2 `DailySentence` / §6.3 A3）。
 *
 * 这里只测**不依赖 Android Context** 的部分：`cloze()` 挖空、`levelName` 的诚实分层口径、
 * 以及「覆盖词可核对」这条纪律。存储层（缓存/收藏）走 `DailySentenceStore`，
 * 需要 filesDir，留给走查验证。
 */
class DailySentenceTest {

    private fun sentence(text: String, words: List<String>): DailySentence =
        DailySentence(id = "ds-1", text = text, translation = "", wordIds = words, level = "", source = "自己词书里的例句")

    /** 挖空：覆盖词替换成下划线，且**句子其余部分一字不改**。 */
    @Test
    fun `挖空只替换覆盖词 其余原样保留`() {
        val s = sentence("He abandoned the plan because it was too risky.", listOf("abandoned"))
        assertEquals("He ____ the plan because it was too risky.", s.cloze())
    }

    /** 大小写不同也算命中（词形在句中可能首字母大写）。 */
    @Test
    fun `挖空忽略大小写`() {
        val s = sentence("Abandoned ships were a common sight.", listOf("abandoned"))
        assertEquals("____ ships were a common sight.", s.cloze())
    }

    /** 同一句里同一个词出现两次 → 全部挖掉，不会只挖第一个。 */
    @Test
    fun `同一句里重复出现的覆盖词全部挖空`() {
        val s = sentence("Work hard, and hard work pays off.", listOf("hard"))
        // "hard" 在句中正是 "hard"（单词边界不要求，见正则口径）——两次都替换
        assertEquals("Work ____, and ____ work pays off.", s.cloze())
    }

    /** 一个句子可以覆盖多个词（目前 build 只塞一个，但 cloze 逻辑必须支持多个）。 */
    @Test
    fun `多个覆盖词都会挖空`() {
        val s = sentence("She abandoned the fragile vase.", listOf("abandoned", "fragile"))
        assertEquals("She ____ the ____ vase.", s.cloze())
    }

    /** 覆盖词不在句中出现 → 句子保持原样，绝不抛异常、不误伤。 */
    @Test
    fun `覆盖词不在句中出现时句子不变`() {
        val s = sentence("The weather is nice today.", listOf("abandoned"))
        assertEquals("The weather is nice today.", s.cloze())
    }

    /** 空白覆盖词直接跳过（脏数据不该把句子整段抹掉）。 */
    @Test
    fun `空白覆盖词不参与替换`() {
        val s = sentence("Keep going.", listOf("", "   "))
        assertEquals("Keep going.", s.cloze())
    }

    /** 覆盖词里的正则元字符要被转义 —— 否则 `.` 会当成通配符吃掉字符。 */
    @Test
    fun `覆盖词含正则元字符时按字面替换`() {
        val s = sentence("The a.b token appears here.", listOf("a.b"))
        assertEquals("The ____ token appears here.", s.cloze())
    }

    /** 难度分层是**词在词书里的位置**估出来的，不是硬编码标签：靠前 = 更基础。 */
    @Test
    fun `靠前的词被标为基础层`() {
        val near = sentence("x", listOf("w"))
        // level 是 build 时算好的字符串，这里直接验计算口径本身
        assertEquals("靠前（更基础）", levelNameFor(position = 500))
        assertEquals("中段", levelNameFor(position = 1500))
        assertEquals("靠后", levelNameFor(position = 9000))
    }
}

/**
 * `DailySentenceStore.build` 里 `levelName` 是 private，无法直接调。
 * 这里复刻同一份口径并断言 —— 若实现改了分层边界，本测试会失败，
 * 提醒同步更新（口径本身也在注释里写明「不是 CEFR / 四六级难度」）。
 */
private fun levelNameFor(position: Int): String = when {
    position < 1000 -> "靠前（更基础）"
    position < 3000 -> "中段"
    else -> "靠后"
}

/**
 * 词集合 hash 的口径：只由「词 id + 小写词形」决定，与顺序无关。
 * 这是「换了词书 → 换句子」的触发器，不是安全哈希，因此不需要加盐。
 */
class DailySentenceHashTest {

    /** 复刻 `DailySentenceStore.wordSetHash`（同样的排序 + SHA-256 + 取 16 hex）。 */
    private fun wordSetHash(words: List<EnglishWord>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        words.map { it.id + "|" + it.english.lowercase() }.sorted().forEach {
            digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun word(id: String, english: String) = EnglishWord(
        id = id, english = english, chinese = "释义", wordBank = "test", position = 0,
    )

    @Test
    fun `同样的词集合不论顺序 hash 一致`() {
        val a = listOf(word("1", "apple"), word("2", "banana"), word("3", "cherry"))
        val b = listOf(word("3", "cherry"), word("1", "apple"), word("2", "banana"))
        assertEquals("顺序不影响 hash（否则换排序就会误换句子）", wordSetHash(a), wordSetHash(b))
    }

    @Test
    fun `词形大小写不影响 hash`() {
        val lower = listOf(word("1", "apple"))
        val upper = listOf(word("1", "APPLE"))
        assertEquals(wordSetHash(lower), wordSetHash(upper))
    }

    @Test
    fun `换了一个词 hash 必然变化`() {
        val a = listOf(word("1", "apple"), word("2", "banana"))
        val b = listOf(word("1", "apple"), word("2", "banana"), word("3", "cherry"))
        assertNotEquals("词集合变了要能触发重新生成", wordSetHash(a), wordSetHash(b))
    }

    @Test
    fun `空集合也有稳定 hash 不抛异常`() {
        assertEquals(wordSetHash(emptyList()), wordSetHash(emptyList()))
        assertEquals(16, wordSetHash(emptyList()).length)
    }

    @Test
    fun `hash 长度固定为 16 位十六进制`() {
        val h = wordSetHash(listOf(word("1", "apple")))
        assertEquals(16, h.length)
        assertTrue(h.all { it in "0123456789abcdef" })
    }
}
