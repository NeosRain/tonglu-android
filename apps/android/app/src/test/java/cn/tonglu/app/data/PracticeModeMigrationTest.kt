package cn.tonglu.app.data

import cn.tonglu.app.data.listening.PracticeMode
import cn.tonglu.app.data.listening.QuestionAttempt
import cn.tonglu.app.data.listening.withMigratedKey
import cn.tonglu.app.data.listening.withMigratedMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `QuestionAttempt.mode` 的**读时迁移**（2026-10-11 加字段）。
 *
 * 为什么必须钉住：这是**动用户历史数据**的地方。搞错了两种后果都很糟 ——
 * 把旧记录标错来源 → 今日卡分线统计显示错数字（不报错）；
 * 迁移不幂等 → 每次读盘都回写一遍（用户数据被反复重写）。
 *
 * 规则：`mode` 空 = 旧记录，**只按题目键前缀推断**（`legacy:` → 材料题，其余 → 题库）。
 * ⚠️ 绝不能把任何旧记录猜成 `sentence` —— 那是这个字段才加的来源，旧记录里不可能有。
 */
class PracticeModeMigrationTest {

    private fun attempt(key: String = "", materialId: String = "pack-1", mode: String = "") =
        QuestionAttempt(questionId = "q01", questionKey = key, materialId = materialId,
            materialTitle = "材料", chosen = 0, correct = true, answeredAt = 1L, mode = mode)

    @Test
    fun `材料题的旧记录推断成 material`() {
        val r = attempt(key = "legacy:pack-1:q01").withMigratedMode()
        assertEquals(PracticeMode.MATERIAL, r.mode)
    }

    @Test
    fun `题库的旧记录推断成 quiz`() {
        val r = attempt(key = "kylebing-cet4:q07", materialId = "kylebing-cet4").withMigratedMode()
        assertEquals(PracticeMode.QUIZ, r.mode)
    }

    @Test
    fun `题目键也空时先补键再据此推断来源`() {
        val r = attempt(key = "", materialId = "pack-9").withMigratedMode()
        assertEquals("legacy:pack-9:q01", r.questionKey)
        assertEquals(PracticeMode.MATERIAL, r.mode)
    }

    @Test
    fun `已有 mode 的原样返回，绝不改写`() {
        val r = attempt(key = "kylebing-cet4:q07", mode = PracticeMode.SENTENCE).withMigratedMode()
        assertEquals("已有的来源不该被推断覆盖", PracticeMode.SENTENCE, r.mode)
    }

    @Test
    fun `幂等-连跑两次结果完全一致`() {
        val once = attempt(key = "legacy:pack-1:q01").withMigratedMode()
        val twice = once.withMigratedMode()
        assertEquals(once, twice)
    }

    @Test
    fun `对照组-旧记录不许被猜成 sentence`() {
        // sentence 是 2026-10-11 才有的来源，旧数据里不可能存在。
        // 若有人「顺手」把无键记录优先猜成 sentence，本用例必须变红。
        val r = attempt(key = "", materialId = "").withMigratedMode()
        assertEquals(PracticeMode.MATERIAL, r.mode)
        assertEquals("legacy:unknown:q01", r.questionKey)
        assertEquals("绝不能凭空猜成例句作答", false, r.mode == PracticeMode.SENTENCE)
    }

    @Test
    fun `withMigratedKey 仍然只补键不动来源（老 API 行为不变）`() {
        val r = attempt(key = "").withMigratedKey()
        assertEquals("legacy:pack-1:q01", r.questionKey)
        assertEquals("老 API 不该顺手改 mode", "", r.mode)
    }
}
