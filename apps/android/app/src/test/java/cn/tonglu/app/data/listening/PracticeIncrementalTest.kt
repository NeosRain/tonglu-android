package cn.tonglu.app.data.listening

import cn.tonglu.app.data.PracticeEventDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作答**增量合流**（B6，`docs/plan/56` §3.3「分业务增量 + 墓碑」）。
 *
 * ## 为什么这层测试是必需的
 * 增量同步的风险集中在「**悄悄丢用户作答**」和「**悄悄重复记账**」两处，
 * 而这两者在界面上都看不出来：错题本少一条、某题「做过 3 次」显示成 4 次，
 * 用户只会觉得「好像不太对」，不会当成 bug 报。JSON 文件 + Gson 绕过构造器
 * 这套组合更让走查验不了。所以这里直接测纯函数 [mergeByEvents]。
 *
 * ## 覆盖的纪律
 * - 按 `id` 判身份，**不按 questionKey**（同题做三次是三条，不能被"去重"抹成一条）；
 * - `attempt` 幂等（重传不产生第二条）；
 * - `tombstone` 按 id 删，「撤回」压倒「新增」；
 * - 缺 id 的事件丢弃（没有身份的记录没法判重、也没法被墓碑命中）；
 * - 不改写已有记录的 `correct`（答案版本更新不许改写历史）。
 */
class PracticeIncrementalTest {

    private fun attempt(
        id: String,
        key: String = "bank:q01",
        correct: Boolean = false,
        at: Long = 1_000L,
    ) = QuestionAttempt(
        id = id, questionId = "q01", questionKey = key,
        materialId = "bank", materialTitle = "题组", chosen = 0,
        correct = correct, answeredAt = at,
    )

    private fun event(id: String, kind: String = PracticeEventDto.KIND_ATTEMPT) =
        PracticeEventDto(
            eventId = "e-$id", kind = kind,
            payload = mapOf(
                "id" to id, "questionId" to "q01", "questionKey" to "bank:q01",
                "materialId" to "bank", "materialTitle" to "题组",
                "chosen" to 1, "correct" to true, "answeredAt" to 2_000L,
            ),
        )

    // ---------------------------------------------------------------- 基本合并

    /** 本机为空时，事件全部落地。 */
    @Test
    fun `空本机拉回事件全部落地`() {
        val merged = mergeByEvents(emptyList(), listOf(event("a"), event("b")))
        assertEquals(2, merged.size)
        assertTrue(merged.map { it.id }.containsAll(listOf("a", "b")))
    }

    /**
     * **同一道题做三次必须留三条** —— 这是最容易写错的地方。
     *
     * 如果实现按 `questionKey` 去重（看起来"更干净"），三次作答会被压成一条，
     * 用户「做过 3 次，对 2 次」的历史就没了。
     */
    @Test
    fun `同题多次作答是三条独立记录而不是一条`() {
        val local = listOf(
            attempt("a1", at = 3_000L),
            attempt("a2", at = 2_000L),
            attempt("a3", at = 1_000L),
        )
        assertEquals(3, local.size)
        // 同样三条再合一遍（幂等）：还该是三条
        val merged = mergeByEvents(local, listOf(event("a1"), event("a2"), event("a3")))
        assertEquals(3, merged.size)
    }

    // ---------------------------------------------------------------- 幂等

    /** 重传同一条（id 相同）不产生第二条 —— 「超时其实写成功」的重传是常态。 */
    @Test
    fun `重传同一条事件不产生重复`() {
        val local = listOf(attempt("a"))
        val merged = mergeByEvents(local, listOf(event("a"), event("a")))
        assertEquals(1, merged.size)
    }

    /** 已在本地的那条**内容不被云端覆盖**（历史不改写）。 */
    @Test
    fun `本地已有记录不被云端同 id 事件改写`() {
        val local = listOf(attempt("a", correct = false))
        val merged = mergeByEvents(local, listOf(event("a"))) // 云端那条 correct=true
        assertEquals(1, merged.size)
        assertFalse("本地 correct 不该被云端改写", merged.first().correct)
    }

    // ---------------------------------------------------------------- 墓碑

    /** 墓碑按 id 删掉本机那条。 */
    @Test
    fun `墓碑删掉本机对应记录`() {
        val local = listOf(attempt("a"), attempt("b"))
        val merged = mergeByEvents(local, listOf(event("a", PracticeEventDto.KIND_TOMBSTONE)))
        assertEquals(1, merged.size)
        assertEquals("b", merged.first().id)
    }

    /** 墓碑指向本机没有的 id：不报错、也不产生新记录。 */
    @Test
    fun `墓碑指向不存在的记录是空操作`() {
        val local = listOf(attempt("a"))
        val merged = mergeByEvents(local, listOf(event("ghost", PracticeEventDto.KIND_TOMBSTONE)))
        assertEquals(1, merged.size)
        assertEquals("a", merged.first().id)
    }

    /** 同批里「新增 + 撤回同一条」以事件顺序为准（服务端已按 seq 升序给）。 */
    @Test
    fun `同批先新增后撤回最终不留记录`() {
        val merged = mergeByEvents(
            emptyList(),
            listOf(event("a"), event("a", PracticeEventDto.KIND_TOMBSTONE)),
        )
        assertTrue("先加后撤，最终该没有这条", merged.isEmpty())
    }

    // ---------------------------------------------------------------- 脏数据

    /** 缺 id 的事件丢弃 —— 没有身份的记录没法判重、也没法被墓碑命中。 */
    @Test
    fun `缺 id 的事件被丢弃`() {
        val noId = PracticeEventDto(
            eventId = "e-x", kind = PracticeEventDto.KIND_ATTEMPT,
            payload = mapOf("questionId" to "q01", "correct" to true),
        )
        assertTrue(mergeByEvents(emptyList(), listOf(noId)).isEmpty())
    }

    /** `id` 是空串也按缺 id 处理。 */
    @Test
    fun `空字符串 id 的事件被丢弃`() {
        assertTrue(mergeByEvents(emptyList(), listOf(event(""))).isEmpty())
    }

    /** 事件缺 `questionKey` 时按 `legacy:<materialId>:<questionId>` 就地补键。 */
    @Test
    fun `事件缺 questionKey 时按迁移规则补键`() {
        val legacy = PracticeEventDto(
            eventId = "e-old", kind = PracticeEventDto.KIND_ATTEMPT,
            payload = mapOf(
                "id" to "old1", "questionId" to "q07",
                "materialId" to "north-wind", "chosen" to 0,
                "correct" to true, "answeredAt" to 1_500L,
            ),
        )
        val merged = mergeByEvents(emptyList(), listOf(legacy))
        assertEquals(1, merged.size)
        assertEquals("legacy:north-wind:q07", merged.first().questionKey)
    }

    /** 缺 answeredAt 时给 0（排到列表底部），不给当前时间 —— 伪造时间戳会污染「最近一次」。 */
    @Test
    fun `事件缺 answeredAt 时用 0 而不是当前时间`() {
        val noTime = PracticeEventDto(
            eventId = "e-t", kind = PracticeEventDto.KIND_ATTEMPT,
            payload = mapOf("id" to "t1", "questionId" to "q01", "questionKey" to "bank:q01"),
        )
        val merged = mergeByEvents(emptyList(), listOf(noTime))
        assertEquals(1, merged.size)
        assertEquals(0L, merged.first().answeredAt)
    }

    // ---------------------------------------------------------------- 排序

    /** 合并结果按作答时间倒序（最近的在前）—— 与 `PracticeStore.attempts()` 同一口径。 */
    @Test
    fun `合并结果按时间倒序`() {
        val a = event("a").let { it.copy(payload = it.payload + ("answeredAt" to 1_000L)) }
        val b = event("b").let { it.copy(payload = it.payload + ("answeredAt" to 5_000L)) }
        val c = event("c").let { it.copy(payload = it.payload + ("answeredAt" to 3_000L)) }
        val merged = mergeByEvents(emptyList(), listOf(a, b, c))
        assertEquals(listOf("b", "c", "a"), merged.map { it.id })
    }

    // ---------------------------------------------------------------- 边界

    /** 空事件列表：原样返回**同一个引用**（调用据此跳过写盘）。 */
    @Test
    fun `空事件列表不产生任何改动`() {
        val local = listOf(attempt("a"))
        val merged = mergeByEvents(local, emptyList())
        assertEquals(1, merged.size)
        assertTrue("该原样返回，调用方靠它跳过写盘", merged === local)
    }

    /** 事件全是幂等重复时，也原样返回同一引用（不白写一次盘）。 */
    @Test
    fun `全部幂等重复时返回原引用`() {
        val local = listOf(attempt("a"))
        val merged = mergeByEvents(local, listOf(event("a")))
        assertTrue(merged === local)
    }

    /** 事件 id 前缀 `e-` 只是测试构造用的，真实上传用的是作答自身的 id。 */
    @Test
    fun `上传事件用作答自身 id 作为幂等键`() {
        val dto = cn.tonglu.app.data.PracticeAttemptDto(
            id = "real-1", questionId = "q01", questionKey = "bank:q01",
            materialId = "bank", materialTitle = "题组",
            chosen = 1, correct = true, answeredAt = 1_000L,
        )
        val e = PracticeEventDto.ofAttempt(dto)
        assertEquals("real-1", e.eventId)
        assertEquals(PracticeEventDto.KIND_ATTEMPT, e.kind)
        assertEquals("real-1", e.payload["id"])
        assertNotNull(e.payload["questionKey"])
    }
}
