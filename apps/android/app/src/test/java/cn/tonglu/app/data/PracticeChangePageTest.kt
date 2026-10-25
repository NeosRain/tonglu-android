package cn.tonglu.app.data

import cn.tonglu.app.data.listening.QuestionAttempt
import cn.tonglu.app.data.listening.mergeByEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **一页**云端增量 → 本机事件的翻译与合流（FINDINGS #3）。
 *
 * ## 为什么单独开一个测试文件
 * 上一轮把「保序」做在了 `AppViewModel` 的私有方法里 —— 方向对，但测不到：
 * 那是个 Android ViewModel，单测里起不来。现在保序翻译抽成了纯函数
 * [practiceMergeEvents]，这里就**直接打它**，再顺手验「翻译结果交给
 * `mergeByEvents` 之后，本机记录是不是该有的样子」。
 *
 * ## 核心是「服务端序号顺序不能被重排」
 * 一页里同时有「新增 A」和「撤回 A」是常态（新设备建了这条，同一批里又撤了）。
 * 早先的做法是先删全页墓碑、再加全页新增，于是：
 * - 「先新增 A、后删除 A」→ 变成「删掉一个本机没有的 A（空操作）→ 又把 A 加回来」
 *   = **对方已撤回的记录在本机复活**，而且墓碑消费过了，永远删不掉；
 * - 「先删除 A、后新增 A（新版本）」→ 变成「先加回来、再删掉」
 *   = **别人刚做的题被抹掉**。
 * 两种都是静默丢/复活数据，界面上完全看不出来 —— 只能靠这层测试挡住。
 */
class PracticeChangePageTest {

    private fun attemptEvent(
        seq: Long,
        id: String = "a",
        correct: Boolean = true,
        at: Long = 2_000L,
    ) = PracticeChangeItemDto(
        seq = seq,
        eventId = "e-$id",
        kind = PracticeEventDto.KIND_ATTEMPT,
        payload = mapOf(
            "id" to id, "questionId" to "q01", "questionKey" to "bank:q01",
            "materialId" to "bank", "materialTitle" to "题组",
            "chosen" to 1, "correct" to correct, "answeredAt" to at,
        ),
    )

    private fun tombstone(seq: Long, id: String = "a") = PracticeChangeItemDto(
        seq = seq,
        eventId = "e-$id-del",
        kind = PracticeEventDto.KIND_TOMBSTONE,
        payload = mapOf("id" to id),
        deletedAt = "2026-10-06T12:00:00Z",
    )

    private fun local(id: String, at: Long = 1_000L) = QuestionAttempt(
        id = id, questionId = "q01", questionKey = "bank:q01",
        materialId = "bank", materialTitle = "题组", chosen = 0,
        correct = false, answeredAt = at,
    )

    // ---------------------------------------------------------------- 排序

    /** 页面到达顺序不一定是 seq 顺序，翻译时必须按 seq 升序排好。 */
    @Test
    fun `乱序到达的页面按 seq 升序翻译`() {
        val page = listOf(attemptEvent(3, "c"), attemptEvent(1, "a"), attemptEvent(2, "b"))
        assertEquals(listOf("a", "b", "c"), practiceMergeEvents(page).map { it.payload["id"] })
    }

    // ---------------------------------------------------------------- 混合事件（FINDINGS #3 正身）

    /**
     * **先新增 A、后删除 A** —— 本机没有 A，最终不该有 A。
     *
     * 这就是 FINDINGS #3 里那条：重排成「先删后加」会让已撤回的 A 复活。
     */
    @Test
    fun `先新增后撤回_本机最终没有这条`() {
        val page = listOf(attemptEvent(seq = 10), tombstone(seq = 11))
        val merged = mergeByEvents(emptyList(), practiceMergeEvents(page))
        assertTrue("先加后撤，最终该没有这条", merged.isEmpty())
    }

    /**
     * **先删除 A、后新增 A** —— 本机已有 A，最终该有且只有云端那条新的。
     *
     * 重排成「先加后删」会把别人刚做的题抹掉。
     */
    @Test
    fun `先撤回后新增_最终留下新增那条`() {
        val page = listOf(tombstone(seq = 10), attemptEvent(seq = 11, at = 9_000L))
        val merged = mergeByEvents(listOf(local("a")), practiceMergeEvents(page))
        assertEquals(1, merged.size)
        assertEquals(9_000L, merged.first().answeredAt)
    }

    /** 一条记录在一页里被加两次、中间撤一次：最终留下最后一次新增。 */
    @Test
    fun `新增_撤回_再新增_留下最后一次`() {
        val page = listOf(
            attemptEvent(seq = 1, at = 1_000L),
            tombstone(seq = 2),
            attemptEvent(seq = 3, at = 3_000L),
        )
        val merged = mergeByEvents(emptyList(), practiceMergeEvents(page))
        assertEquals(1, merged.size)
        assertEquals(3_000L, merged.first().answeredAt)
    }

    /** 跨页的删除也要生效：上一页加了 A，这一页撤 A。 */
    @Test
    fun `跨页撤回能删掉上一页新增的记录`() {
        val first = mergeByEvents(emptyList(), practiceMergeEvents(listOf(attemptEvent(seq = 1))))
        assertEquals(1, first.size)
        val second = mergeByEvents(first, practiceMergeEvents(listOf(tombstone(seq = 2))))
        assertTrue("跨页的墓碑必须生效", second.isEmpty())
    }

    /** 两条不同记录混在一页：删掉 b、留下 a，互不影响。 */
    @Test
    fun `同页里删一条加一条互不干扰`() {
        val page = listOf(attemptEvent(seq = 1, id = "a"), tombstone(seq = 2, id = "b"), attemptEvent(seq = 3, id = "c"))
        val merged = mergeByEvents(listOf(local("b")), practiceMergeEvents(page))
        assertEquals(listOf("a", "c"), merged.map { it.id }.sorted())
    }

    // ---------------------------------------------------------------- 墓碑翻译

    /** 带 `deletedAt` 的事件翻成 tombstone，payload 只留 id。 */
    @Test
    fun `带 deletedAt 的事件翻成墓碑`() {
        val events = practiceMergeEvents(listOf(tombstone(1)))
        assertEquals(1, events.size)
        assertEquals(PracticeEventDto.KIND_TOMBSTONE, events.first().kind)
        assertEquals("a", events.first().payload["id"])
    }

    /** 墓碑的 payload 不带回答字段 —— 它只是「点在谁身上」，不是一条记录。 */
    @Test
    fun `墓碑不携带作答内容`() {
        val events = practiceMergeEvents(listOf(tombstone(1)))
        assertEquals(setOf("id"), events.first().payload.keys)
    }

    // ---------------------------------------------------------------- 脏数据

    /** 缺 id 的事件丢弃：没法判重、也没法被墓碑命中。 */
    @Test
    fun `缺 id 的事件被丢弃`() {
        val noId = PracticeChangeItemDto(
            seq = 1, kind = PracticeEventDto.KIND_ATTEMPT,
            payload = mapOf("questionId" to "q01", "correct" to true),
        )
        assertTrue(practiceMergeEvents(listOf(noId)).isEmpty())
    }

    /** 空 payload 的事件丢弃（老服务端 / 字段损坏都可能给出来）。 */
    @Test
    fun `空 payload 的事件被丢弃`() {
        assertTrue(practiceMergeEvents(listOf(PracticeChangeItemDto(seq = 1))).isEmpty())
    }

    /** 墓碑缺 id 也丢弃 —— 没有目标的删除不该变成「删掉一切」。 */
    @Test
    fun `缺 id 的墓碑被丢弃而不是删光本机`() {
        val noTarget = PracticeChangeItemDto(seq = 1, deletedAt = "2026-10-06T12:00:00Z")
        assertTrue(practiceMergeEvents(listOf(noTarget)).isEmpty())
    }

    /** 整页都是脏数据时，合流结果原样不动（调用方据此跳过写盘）。 */
    @Test
    fun `整页脏数据不改动本机`() {
        val mine = listOf(local("a"))
        val merged = mergeByEvents(mine, practiceMergeEvents(listOf(PracticeChangeItemDto(seq = 1))))
        assertTrue("该原样返回，调用方靠它跳过写盘", merged === mine)
    }

    // ---------------------------------------------------------------- 空页

    /** 空页翻出空列表，合流原样返回。 */
    @Test
    fun `空页不产生任何事件`() {
        assertTrue(practiceMergeEvents(emptyList()).isEmpty())
        val mine = listOf(local("a"))
        assertTrue(mergeByEvents(mine, practiceMergeEvents(emptyList())) === mine)
    }
}
