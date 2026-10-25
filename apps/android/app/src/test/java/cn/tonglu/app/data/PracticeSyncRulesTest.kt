package cn.tonglu.app.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量同步的**控制流**：分批上传（FINDINGS #1）与下载游标推进（FINDINGS #2）。
 *
 * ## 为什么单独开一个文件
 * 这两条规则原先写死在 `AppViewModel` 的私有方法里 —— 方向对，但**测不到**：
 * 那是个 Android ViewModel，JVM 单测起不来。现在抽成 [practicePullLoop] /
 * [practicePushBatches] 两个纯函数（网络与存储靠 lambda 注入），这里就**直接打它们**。
 *
 * ## 这两条为什么非测不可
 * 它们坏掉时**界面上完全看不出来**，而且都是「老用户/多设备」才踩得到：
 * - **#1 不分批**：历史攒过 200 条之后每次都发同一个超包体 → 被 400 拒回 →
 *   重试还是那个包 → **永远传不上去**。用户只看到「记录没同步」，不会报错。
 * - **#2 拿上传回执的 cursor 推进下载游标**：那个 cursor 是「服务端收到的最大序号」，
 *   不代表本机**读到并落地**了。跳过去就会漏掉别的设备同一时刻写的那批，
 *   而且**永久补不回来** —— 因为游标已经越过它们了。
 *
 * 另一端（后端 DTO 的上限）由 `scripts/audit_cross_end_limits.py` 守着，
 * 两边一起才构成完整契约。
 */
class PracticeSyncRulesTest {

    // ---------------------------------------------------------------- 假的服务端

    /**
     * 按脚本吐页的假服务端。**脚本用完了就报错**（而不是安静地给个空页）——
     * 否则实现多拉一次也测不出来，只会静默少跑几轮。
     */
    private class FakeServer(
        private val pages: List<PracticeChangesDto>,
        /** 第几次「落地」抛异常（-1 = 从不失败）。用来模拟半页写盘失败。 */
        private val failOnApplyCall: Int = -1,
    ) {
        val requested = mutableListOf<Long>()      // 每次拉取带的游标
        val applied = mutableListOf<PracticeChangesDto>()
        val saved = mutableListOf<Long>()          // 落盘过的游标

        suspend fun fetch(cursor: Long): PracticeChangesDto {
            requested += cursor
            val i = requested.size - 1
            if (i >= pages.size) error("拉多了：第 ${i + 1} 次（脚本只有 ${pages.size} 页）")
            return pages[i]
        }

        suspend fun apply(page: PracticeChangesDto) {
            if (applied.size == failOnApplyCall) throw IOException("写盘失败（模拟半页失败）")
            applied += page
        }

        fun onCursor(value: Long) {
            saved += value
        }
    }

    private fun item(seq: Long) = PracticeChangeItemDto(
        seq = seq,
        eventId = "e$seq",
        kind = PracticeEventDto.KIND_ATTEMPT,
        payload = mapOf("id" to "a$seq"),
    )

    /** [count] = 0 表示「服务端说还有，但这一页一条也没给」。 */
    private fun page(next: Long, hasMore: Boolean, count: Int = 1) = PracticeChangesDto(
        items = (1..count).map { item(next) },
        nextCursor = next,
        hasMore = hasMore,
    )

    private fun events(n: Int) = (1..n).map {
        PracticeEventDto(
            eventId = "e$it",
            kind = PracticeEventDto.KIND_ATTEMPT,
            payload = mapOf("id" to "a$it"),
        )
    }

    /** 断言一个 suspend 块抛出指定异常（`assertThrows` 接不了 suspend lambda）。 */
    private inline fun <reified T : Throwable> assertThrowsSuspend(
        crossinline block: suspend () -> Unit,
    ) {
        val err = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertTrue("期望抛 ${T::class.java.simpleName}，实际是 $err", err is T)
    }

    // ---------------------------------------------------------------- 拉取：正常路径

    /** 没有更多时，拉一页就停。 */
    @Test
    fun `没有更多时只拉一页`() {
        val s = FakeServer(listOf(page(10, hasMore = false)))
        val r = runBlocking {
            practicePullLoop(0, fetch = s::fetch, onCursor = s::onCursor, apply = s::apply)
        }
        assertEquals(10L, r.cursor)
        assertEquals(false, r.cappedAtRoundLimit)
        assertEquals(listOf(0L), s.requested)
        assertEquals(listOf(10L), s.saved)
    }

    /** 每页都必须用**上一页回的 nextCursor** 去拉，不能跳到全局最新（会漏中间的事件）。 */
    @Test
    fun `每页都用上一页的 nextCursor 继续`() {
        val s = FakeServer(listOf(page(10, true), page(20, true), page(30, false)))
        val r = runBlocking {
            practicePullLoop(0, fetch = s::fetch, onCursor = s::onCursor, apply = s::apply)
        }
        assertEquals(listOf(0L, 10L, 20L), s.requested)
        assertEquals(listOf(10L, 20L, 30L), s.saved)
        assertEquals(30L, r.cursor)
    }

    // ---------------------------------------------------------------- 拉取：FINDINGS #2 正身

    /**
     * **整页落地失败时，游标必须原地不动**。
     *
     * 这是 #2 最要紧的一条：反过来（先推进再落地）半页失败就**永久漏掉**那批事件，
     * 而且没有任何报错 —— 游标已经越过去了，永远不会再拉一次。
     */
    @Test
    fun `整页落地失败时游标不前进`() {
        val s = FakeServer(
            pages = listOf(page(10, true), page(20, true), page(30, false)),
            failOnApplyCall = 1,          // 第 2 页落地时炸
        )
        assertThrowsSuspend<IOException> {
            practicePullLoop(0, fetch = s::fetch, onCursor = s::onCursor, apply = s::apply)
        }
        // 第 1 页成功 → 游标记到 10；第 2 页失败 → **不能**记到 20。
        assertEquals(listOf(10L), s.saved)
        assertEquals(1, s.applied.size)
        assertEquals(listOf(0L, 10L), s.requested)
    }

    /** 第一页就失败：游标连 10 都不能记，下次从 0 重拉。 */
    @Test
    fun `第一页就失败时游标完全不动`() {
        val s = FakeServer(listOf(page(10, true)), failOnApplyCall = 0)
        assertThrowsSuspend<IOException> {
            practicePullLoop(0, fetch = s::fetch, onCursor = s::onCursor, apply = s::apply)
        }
        assertTrue("一次都没落盘成功，不该记游标", s.saved.isEmpty())
    }

    /** 服务端给的 nextCursor 比当前还小（序号回退）：**不倒退**，否则会反复拉同一批。 */
    @Test
    fun `游标只前进不后退`() {
        val s = FakeServer(listOf(page(10, true), page(5, false)))
        val r = runBlocking {
            practicePullLoop(0, fetch = s::fetch, onCursor = s::onCursor, apply = s::apply)
        }
        assertEquals(10L, r.cursor)
        assertEquals("回退的那个 5 不能写进游标", listOf(10L), s.saved)
    }

    // ---------------------------------------------------------------- 拉取：终止条件

    /**
     * `hasMore=true` **但本页一条也没给** → 必须停。
     *
     * 不停就是死循环：服务端一直说「还有」却一次也不给，客户端永远拉不完，
     * 同步整天卡在那儿（脚本里的假服务端拉多了会直接报错，所以这里也顺带防挂死）。
     */
    @Test
    fun `hasMore 为真但本页为空时立刻停`() {
        val s = FakeServer(listOf(page(10, hasMore = true, count = 0)))
        val r = runBlocking {
            practicePullLoop(0, fetch = s::fetch, onCursor = s::onCursor, apply = s::apply)
        }
        assertEquals(1, s.requested.size)
        assertEquals(10L, r.cursor)
        assertEquals(false, r.cappedAtRoundLimit)
    }

    /** 轮次触顶：**返回已经推进到的位置**，下次接着追，而不是放弃从头再来。 */
    @Test
    fun `轮次触顶时返回已推进到的位置`() {
        val s = FakeServer((1..100).map { page(it * 10L, hasMore = true) })
        val r = runBlocking {
            practicePullLoop(0, maxRounds = 3, fetch = s::fetch, onCursor = s::onCursor, apply = s::apply)
        }
        assertEquals(3, s.requested.size)
        assertEquals(30L, r.cursor)
        assertEquals(true, r.cappedAtRoundLimit)
        assertEquals(listOf(10L, 20L, 30L), s.saved)
    }

    @Test
    fun `轮次上限必须为正`() {
        val s = FakeServer(listOf(page(10, false)))
        assertThrowsSuspend<IllegalArgumentException> {
            practicePullLoop(0, maxRounds = 0, fetch = s::fetch, apply = s::apply)
        }
        assertTrue("一页都不该拉", s.requested.isEmpty())
    }

    // ---------------------------------------------------------------- 推送：FINDINGS #1

    /** 201 条 → 两批，**每批都不超过后端上限**。这是 #1 的正身：不分批就永远传不上去。 */
    @Test
    fun `超过上限时分批且每批都不超限`() {
        val sizes = mutableListOf<Int>()
        val accepted = runBlocking {
            practicePushBatches(events(201)) { batch ->
                sizes += batch.size
                PracticeEventsAckDto(accepted = batch.size)
            }
        }
        assertEquals(listOf(PRACTICE_UPLOAD_BATCH, 1), sizes)
        assertTrue("每批都必须 ≤ 后端上限", sizes.all { it <= PRACTICE_UPLOAD_BATCH })
        assertEquals(201, accepted)
    }

    /** 正好等于上限 → 一批，不拆。 */
    @Test
    fun `正好等于上限时不拆`() {
        val sizes = mutableListOf<Int>()
        runBlocking {
            practicePushBatches(events(PRACTICE_UPLOAD_BATCH)) { batch ->
                sizes += batch.size
                PracticeEventsAckDto(accepted = batch.size)
            }
        }
        assertEquals(listOf(PRACTICE_UPLOAD_BATCH), sizes)
    }

    /** 分批只是切分：一条都不能丢、也不能重复。 */
    @Test
    fun `分批不丢条也不重复`() {
        val src = events(451)
        val seen = mutableListOf<PracticeEventDto>()
        runBlocking {
            practicePushBatches(src) { batch ->
                seen += batch
                PracticeEventsAckDto(accepted = batch.size)
            }
        }
        assertEquals(src, seen)
    }

    /** 空列表**一个请求都不发**（没有待上传就不要打扰服务端）。 */
    @Test
    fun `空列表不发任何请求`() {
        var calls = 0
        val accepted = runBlocking {
            practicePushBatches(emptyList()) { calls++; PracticeEventsAckDto(accepted = 0) }
        }
        assertEquals(0, calls)
        assertEquals(0, accepted)
    }

    /**
     * **回执里的 cursor 不许变成下载游标**（FINDINGS #2 的端到端版）。
     *
     * 真正的保证在类型上：[practicePushBatches] **没有**带回 cursor 的通道，
     * 只能由 [practicePullLoop] 推进。这个用例钉住的是「调用方那个游标变量
     * 不会被上传改写」—— 以后有人顺手把 cursor 带出来，这里会先红。
     */
    @Test
    fun `上传回执的巨大 cursor 不会变成下载游标`() {
        val first = FakeServer(listOf(page(7, hasMore = false)))
        var cursor = runBlocking {
            practicePullLoop(0, fetch = first::fetch, onCursor = first::onCursor, apply = first::apply)
        }.cursor
        assertEquals(7L, cursor)

        runBlocking {
            practicePushBatches(events(3)) { PracticeEventsAckDto(accepted = it.size, cursor = 9_999L) }
        }

        // 再拉一轮：起点必须是 7（拉出来的），不能是 9999（上传回执里的）。
        val second = FakeServer(listOf(page(9, hasMore = false)))
        runBlocking {
            practicePullLoop(cursor, fetch = second::fetch, onCursor = second::onCursor, apply = second::apply)
        }
        assertEquals(listOf(7L), second.requested)
    }

    @Test
    fun `批大小必须为正`() {
        assertThrowsSuspend<IllegalArgumentException> {
            practicePushBatches(events(2), batchSize = 0) { PracticeEventsAckDto() }
        }
    }
}
