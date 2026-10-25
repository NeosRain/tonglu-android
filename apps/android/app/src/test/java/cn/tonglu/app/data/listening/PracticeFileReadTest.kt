package cn.tonglu.app.data.listening

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「空文件」与「读不出来」的判定（FINDINGS #10）。
 *
 * ## 这条为什么必须有测试
 * 上一轮 #10 改完是 **P1 零覆盖**：判定写在 `PracticeStore.loadStrict()` 里，
 * 而 `PracticeStore` 要 `Context`，JVM 单测里 android.jar 是 stub
 * （`isReturnDefaultValues = false`，一碰就抛 not mocked）—— 于是这条只能靠编译保证。
 * 它的验收偏偏全是文件级的：「损坏 / 截断 / 读失败**均保留原文件**，不会以一条新作答
 * 静默替换历史」。没有测试，就等于没有验收。
 *
 * 现在判定抽成了纯函数 [parsePracticeContent]（`String?` 进、结果出，不碰磁盘），
 * 这里直接打它。
 *
 * ## 测的是哪一层
 * 本文件**只验解析判定**。「判定之后写路径真的停下」由 `loadStrict()` 抛
 * [PracticeStoreUnreadableException] 保证 —— 那一步要 `Context`，仍测不到；
 * 但它是 `when` 里三行直通代码，判定对它就对。**判定的边界全在这里。**
 */
class PracticeFileReadTest {

    private fun row(id: String, at: Long = 1_000L) = QuestionAttempt(
        id = id, questionId = "q01", questionKey = "bank:q01",
        materialId = "bank", materialTitle = "题组", chosen = 1,
        correct = true, answeredAt = at,
    )

    /** 用**真正的序列化器**造样本：测的是「本机会写出什么」，不是我手写的 JSON。 */
    private fun jsonOf(rows: List<QuestionAttempt>): String = Gson().toJson(rows)

    // ---------------------------------------------------------------- 「没有历史」是事实

    /** `null` = 文件不存在（全新设备）→ Missing，可以照常写。 */
    @Test
    fun `文件不存在判为 Missing`() {
        assertTrue(parsePracticeContent(null) is PracticeFileRead.Missing)
    }

    /** 空串 = 文件在但是空的 → Empty，同样可以照常写。 */
    @Test
    fun `空文件判为 Empty`() {
        assertTrue(parsePracticeContent("") is PracticeFileRead.Empty)
    }

    /** 只有空白字符也当空 —— 换行/空格不该被当成「有内容但解析不了」。 */
    @Test
    fun `只有空白字符判为 Empty`() {
        assertTrue(parsePracticeContent("  \n\t ") is PracticeFileRead.Empty)
    }

    // ---------------------------------------------------------------- 正常读出

    /** 合法内容 → Ok，且字段一条不丢（判定的同时不能顺手毁数据）。 */
    @Test
    fun `合法内容判为 Ok 且字段不丢`() {
        val raw = jsonOf(listOf(row("a", 1_000L), row("b", 2_000L)))
        val parsed = parsePracticeContent(raw)
        assertTrue(parsed is PracticeFileRead.Ok)
        val rows = (parsed as PracticeFileRead.Ok).rows
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertEquals("bank:q01", rows.first().questionKey)
        assertEquals(1, rows.first().chosen)
        assertTrue(rows.first().correct)
        assertEquals(2_000L, rows.last().answeredAt)
    }

    /** 文件里有合法 JSON 但零条记录 → Ok(空)，不是「读不出来」。 */
    @Test
    fun `合法但零条记录判为 Ok 空列表`() {
        val parsed = parsePracticeContent("[]")
        assertTrue("[] 是合法内容，不是损坏", parsed is PracticeFileRead.Ok)
        assertTrue((parsed as PracticeFileRead.Ok).rows.isEmpty())
    }

    // ---------------------------------------------------------------- 「不知道」绝不能当成空

    /** 截断的 JSON：写一半断电最典型的样子。 */
    @Test
    fun `截断的 JSON 判为 Unreadable 而不是空`() {
        val raw = jsonOf(listOf(row("a")))
        val cut = raw.substring(0, raw.length / 2)
        assertTrue("半截文件必须报不知道，不能当没历史", parsePracticeContent(cut) is PracticeFileRead.Unreadable)
    }

    /**
     * **最关键的一条**：截断文件**开头有两条完整记录**。
     *
     * 如果解析器「读到几条算几条」，就会返回 Ok(2条)，写路径据此认为
     * 「历史就这两条」→ 追加一条新作答写回 = **第 3 条之后的记录全丢**。
     * 这正是 FINDINGS #10 要挡的事。
     */
    @Test
    fun `开头有完整记录的截断文件也必须判为 Unreadable`() {
        val raw = jsonOf(listOf(row("a"), row("b"), row("c")))
        // 落在第 3 条中间：前两条是完整的
        val cut = raw.substring(0, raw.indexOf("\"c\"") + 3)
        assertTrue("开头有完整记录也不能当成‘历史就这些’", cut.contains("\"a\""))
        assertTrue(parsePracticeContent(cut) is PracticeFileRead.Unreadable)
    }

    /** 完全不是 JSON（比如别的程序写过这个文件）→ 不知道。 */
    @Test
    fun `完全不是 JSON 判为 Unreadable`() {
        assertTrue(parsePracticeContent("这不是作答记录") is PracticeFileRead.Unreadable)
    }

    /** 形状不对（对象而不是数组）→ 不知道，不能退化成空。 */
    @Test
    fun `顶层不是数组判为 Unreadable`() {
        assertTrue(parsePracticeContent("""{"id":"a"}""") is PracticeFileRead.Unreadable)
    }

    /**
     * Unreadable 要带上**原始异常**，而且那个异常得有话可说。
     *
     * 只看到「读不出来」四个字无法定位；原始 `message` 里才有「坏在第几行、
     * 是不是 EOF」这类线索。
     */
    @Test
    fun `Unreadable 带着有消息的原始异常`() {
        val parsed = parsePracticeContent("""[{""")
        assertTrue(parsed is PracticeFileRead.Unreadable)
        assertNotNull(
            "原始异常得带 message，否则排查只剩‘读不出来’四个字",
            (parsed as PracticeFileRead.Unreadable).cause.message,
        )
    }

    // ---------------------------------------------------------------- 边界：字面量 null

    /**
     * 文件里就写着 `null`：本机从不这样写（`gson.toJson(emptyList())` 是 `[]`），
     * 但它**明确声明了「没有记录」**，不是「不知道」→ 按空处理，不抛。
     *
     * 这条是**显式记下当前取舍**，不是顺带通过：哪天要改成 Unreadable，
     * 这里会先红。
     */
    @Test
    fun `字面量 null 判为空而不是读不出来`() {
        val parsed = parsePracticeContent("null")
        assertTrue("字面量 null 明确声明没有记录", parsed is PracticeFileRead.Ok)
        assertTrue((parsed as PracticeFileRead.Ok).rows.isEmpty())
    }
}
