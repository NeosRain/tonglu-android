package cn.tonglu.app.data.listening

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧作答记录的题目键迁移（`docs/plan/57` §三）。
 *
 * ## 为什么必须有这层测试
 * 迁移的风险是**悄悄丢用户的历史作答**，而界面上完全看不出来 ——
 * 错题本少几条、某题的「做过 N 次」变成 1 次，用户只会觉得「好像不对」。
 * JSON 文件读写 + Gson 绕过构造器这套组合，光靠走查验不到。
 *
 * 这组用例直接构造**旧格式 JSON**（没有 `questionKey` 字段），
 * 走真实的 `withMigratedKey()`，而不是喂理想数据。
 */
class QuestionKeyMigrationTest {

    private val gson = Gson()

    /**
     * 关键用例：**两份材料都用 `q01` 命名**。
     *
     * 这正是加 `questionKey` 之前会出错的场景 —— 旧代码 `statsOf("q01")`
     * 会把两份材料的作答算在一起。迁移后两者必须得到**不同**的键。
     */
    @Test
    fun `两份材料同名题号迁移后得到不同键`() {
        val materialA = attempt(qid = "q01", materialId = "pack-north-wind")
        val materialB = attempt(qid = "q01", materialId = "pack-other-story")

        val keyA = materialA.withMigratedKey().questionKey
        val keyB = materialB.withMigratedKey().questionKey

        assertEquals("legacy:pack-north-wind:q01", keyA)
        assertEquals("legacy:pack-other-story:q01", keyB)
        assertNotEquals("同题号不同材料必须得到不同的键", keyA, keyB)
    }

    /** 同一份材料的同一题号，迁移后键必须相同（否则历史会被拆成两半）。 */
    @Test
    fun `同材料同题号迁移后键相同`() {
        val first = attempt(qid = "q03", materialId = "pack-north-wind", answeredAt = 1000)
        val second = attempt(qid = "q03", materialId = "pack-north-wind", answeredAt = 2000)

        assertEquals(first.withMigratedKey().questionKey, second.withMigratedKey().questionKey)
    }

    /** 幂等：已经有键的记录原样返回，重复跑不会叠加前缀。 */
    @Test
    fun `已迁移的记录不重复加前缀`() {
        val already = attempt(qid = "q01", materialId = "pack-north-wind")
            .copy(questionKey = "builtin-en-grammar:g01")

        val once = already.withMigratedKey()
        val twice = once.withMigratedKey()

        assertEquals("builtin-en-grammar:g01", once.questionKey)
        assertEquals("builtin-en-grammar:g01", twice.questionKey)
    }

    /** 脏数据（materialId 为空）不丢记录：退回 unknown 前缀并保留原题号。 */
    @Test
    fun `缺材料id的脏记录不丢`() {
        val dirty = attempt(qid = "q07", materialId = "")

        val migrated = dirty.withMigratedKey()

        assertEquals("legacy:unknown:q07", migrated.questionKey)
        assertEquals("题号必须保留", "q07", migrated.questionId)
    }

    /**
     * 迁移**绝不改动作答内容** —— 这是「旧记录不丢」的实质定义。
     *
     * 数量对得上但 `chosen`/`correct`/`answeredAt` 被改写，等于记录错了。
     */
    @Test
    fun `迁移不改动作答内容`() {
        val original = attempt(
            qid = "q02", materialId = "pack-north-wind",
            chosen = 3, correct = false, answeredAt = 1_700_000_000_000L,
        )

        val migrated = original.withMigratedKey()

        assertEquals(original.id, migrated.id)
        assertEquals(original.questionId, migrated.questionId)
        assertEquals(original.materialId, migrated.materialId)
        assertEquals(original.materialTitle, migrated.materialTitle)
        assertEquals(original.chosen, migrated.chosen)
        assertEquals(original.correct, migrated.correct)
        assertEquals(original.answeredAt, migrated.answeredAt)
    }

    /** 迁移后再序列化，新字段必须落在 JSON 里（否则下次打开又得重迁）。 */
    @Test
    fun `迁移结果可持久化且能读回`() {
        val rows = listOf(
            attempt(qid = "q01", materialId = "pack-north-wind", answeredAt = 2000),
            attempt(qid = "q01", materialId = "pack-other-story", answeredAt = 1000),
        ).map { it.withMigratedKey() }

        val json = gson.toJson(rows)
        assertTrue("序列化结果应含新字段", json.contains("questionKey"))

        val type = object : com.google.gson.reflect.TypeToken<List<QuestionAttempt>>() {}.type
        val readBack = gson.fromJson<List<QuestionAttempt>>(json, type)

        assertEquals(2, readBack.size)
        assertEquals("legacy:pack-north-wind:q01", readBack[0].questionKey)
        assertEquals("legacy:pack-other-story:q01", readBack[1].questionKey)
        assertEquals("chosen 应保持不变", rows[0].chosen, readBack[0].chosen)
    }

    /**
     * **读旧格式 JSON**（完全没有 `questionKey` 键）时，Gson 会把它置为 null 而不是
     * 走 Kotlin 默认值 —— 必须能迁移，不能因为 null 崩掉或漏迁。
     *
     * 这是本项目反复踩过的坑（Gson 绕过构造器），所以单独锁一条。
     */
    @Test
    fun `旧格式JSON缺键时不崩且能迁移`() {
        val legacyJson = """
            [{"id":"a1","questionId":"q01","materialId":"pack-north-wind",
              "materialTitle":"北风与太阳","chosen":1,"correct":true,"answeredAt":1690000000000}]
        """.trimIndent()

        val type = object : com.google.gson.reflect.TypeToken<List<QuestionAttempt>>() {}.type
        val rows = gson.fromJson<List<QuestionAttempt>>(legacyJson, type)

        assertEquals(1, rows.size)
        val migrated = rows.map { it.withMigratedKey() }
        assertEquals("legacy:pack-north-wind:q01", migrated[0].questionKey)
        assertEquals(1, migrated[0].chosen)
        assertEquals(true, migrated[0].correct)
    }

    /** 题库题的键格式：`<bankId>:<questionId>`，不带 legacy 前缀。 */
    @Test
    fun `题库键格式与材料键格式不冲突`() {
        val bankKey = "builtin-en-grammar:g01"
        val materialKey = attempt(qid = "q01", materialId = "pack-north-wind")
            .withMigratedKey().questionKey

        assertNotEquals(bankKey, materialKey)
        assertTrue("材料键应带 legacy 前缀", materialKey.startsWith("legacy:"))
        assertTrue("题库键不应带 legacy 前缀", !bankKey.startsWith("legacy:"))
    }

    private fun attempt(
        qid: String,
        materialId: String,
        chosen: Int = 0,
        correct: Boolean = true,
        answeredAt: Long = 1_700_000_000_000L,
    ) = QuestionAttempt(
        id = "rec-$qid-$materialId",
        questionId = qid,
        questionKey = "",              // ← 旧记录：没有键
        materialId = materialId,
        materialTitle = "测试材料",
        chosen = chosen,
        correct = correct,
        answeredAt = answeredAt,
    )
}
