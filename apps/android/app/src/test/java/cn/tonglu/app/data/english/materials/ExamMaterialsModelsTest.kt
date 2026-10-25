package cn.tonglu.app.data.english.materials

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考试材料适配器（B 槽）的纯 JVM 判据。
 *
 * 四条要在这一层锁死的纪律（docs/english/exam-entry-contract.md）：
 *  1. **远端字段全可空**：Gson 绕过 Kotlin 构造器，缺字段不许 NPE；
 *  2. **未知考试日期不补猜测值**：year/session/paper 远端 null 就保持 null；
 *  3. **FULLTEXT 与 SOURCE_LINK 是硬边界**：只有原站链接的资源绝不冒充离线全文；
 *  4. **进度不伪造**：无记录=null，SOURCE_LINK 恒 null/false。
 */
class ExamMaterialsModelsTest {

    private val gson = Gson()

    // ------------------------------------------------------------ DTO 解析

    @Test
    fun `远端少字段不炸 全部可选`() {
        val dto = gson.fromJson("{}", RemoteExamMaterialRow::class.java)
        assertNull(dto.articleId)
        assertNull(dto.availability)
        // toRow 对 id 缺失的条目整条丢弃：阅读记录没法键，也不能整列表炸掉
        assertNull(dto.toRow())
    }

    @Test
    fun `未知考试日期保持 null 不补猜测值`() {
        val json = """
            {"articleId":"cet4-official-entry","revision":"r1","title":"四级官方入口",
             "examType":"CET4","year":null,"session":null,"paper":null,
             "availability":"SOURCE_LINK","sourceUrl":"https://cet.neea.edu.cn/"}
        """.trimIndent()
        val row = gson.fromJson(json, RemoteExamMaterialRow::class.java).toRow()!!
        assertNull(row.year)
        assertNull(row.session)
        assertNull(row.paper)
        assertEquals("CET4", row.examType)
        assertFalse(row.isFullText)
    }

    @Test
    fun `availability 缺省时按 SOURCE_LINK 处理 保守方向`() {
        val json = """{"articleId":"a","revision":"r","title":"t"}"""
        val row = gson.fromJson(json, RemoteExamMaterialRow::class.java).toRow()!!
        // 解析失败的缺省值取「更保守」的一端：没有证据说有全文，就不承诺全文
        assertEquals(ExamAvailability.SOURCE_LINK, row.availability)
    }

    @Test
    fun `revision 缺失的条目保留 revision 为空 与目录条目口径一致`() {
        val json = """{"articleId":"a","title":"t"}"""
        val row = gson.fromJson(json, RemoteExamMaterialRow::class.java).toRow()!!
        // 目录条目的 sourceRevision 缺省也是空串（LearningCatalog 同口径）：
        // 行仍可展示，只是没有可定位的阅读记录键，进度自然为 null。
        assertEquals("", row.revision)
        assertNull(row.examType)
    }

    @Test
    fun `id 缺失的条目整条丢弃 阅读记录没法键`() {
        val json = """{"title":"t","revision":"r"}"""
        assertNull(gson.fromJson(json, RemoteExamMaterialRow::class.java).toRow())
    }

    // ------------------------------------------------------------ 详情边界

    @Test
    fun `SOURCE_LINK 详情剥掉 body 不冒充离线全文`() {
        val json = """
            {"articleId":"nav","revision":"r","title":"官方入口",
             "availability":"SOURCE_LINK","body":"不应该存在的正文","bodySource":"catalog"}
        """.trimIndent()
        val detail = gson.fromJson(json, RemoteExamMaterialDetail::class.java).toDetail()!!
        assertNull(detail.body)
        assertNull(detail.bodySource)
    }

    @Test
    fun `FULLTEXT 详情保留正文与来源`() {
        val json = """
            {"articleId":"pp","revision":"r","title":"全文",
             "availability":"FULLTEXT","body":"## Chapter I","bodySource":"catalog"}
        """.trimIndent()
        val detail = gson.fromJson(json, RemoteExamMaterialDetail::class.java).toDetail()!!
        assertEquals("## Chapter I", detail.body)
        assertEquals("catalog", detail.bodySource)
        assertTrue(detail.row.isFullText)
    }

    @Test
    fun `FULLTEXT 但 body 空白 详情正文为 null`() {
        val json = """
            {"articleId":"pp","revision":"r","title":"t",
             "availability":"FULLTEXT","body":"   ","bodySource":"catalog"}
        """.trimIndent()
        val detail = gson.fromJson(json, RemoteExamMaterialDetail::class.java).toDetail()!!
        assertNull(detail.body)
        assertNull(detail.bodySource)
    }

    // ------------------------------------------------------------ 选项

    @Test
    fun `选项映射 key 缺失丢弃 计数缺省 0`() {
        assertNull(gson.fromJson("{}", RemoteExamOption::class.java).toOption())
        val opt = gson.fromJson("""{"key":"CET4"}""", RemoteExamOption::class.java).toOption()!!
        assertEquals(0, opt.articleCount)
        assertEquals(0, opt.fullTextCount)
    }

    // ------------------------------------------------------------ 本机状态合并

    private fun fullText(id: String) = ExamMaterialRow(
        articleId = id, revision = "r", title = "t", subtitle = "s", summary = "", sectionId = "en-exam",
        kind = "MARKDOWN", examType = null, language = "en", year = null, session = null, paper = null,
        readingType = null, recommended = true, availability = ExamAvailability.FULLTEXT,
        sourceUrl = "", licenseId = "CUSTOM", licenseUrl = "", attribution = "", contentSha256 = null, updatedAt = "",
    )

    private fun linkOnly(id: String) = fullText(id).copy(
        kind = "LINK", availability = ExamAvailability.SOURCE_LINK,
    )

    @Test
    fun `无阅读记录 progress 为 null 有记录给比例`() {
        val merged = mergeLocalState(
            rows = listOf(fullText("a"), fullText("b")),
            progressOf = { id -> if (id == "a") 0.35f else null },
            cachedIds = emptySet(),
        )
        assertEquals(0.35f, merged[0].progress!!, 1e-6f)
        assertNull(merged[1].progress)
    }

    @Test
    fun `进度越界值夹回 0到1 不把脏数据透给 UI`() {
        val merged = mergeLocalState(
            rows = listOf(fullText("a")),
            progressOf = { 1.7f },
            cachedIds = emptySet(),
        )
        assertEquals(1f, merged[0].progress!!, 1e-6f)
    }

    @Test
    fun `SOURCE_LINK 行 进度与缓存恒为无`() {
        val merged = mergeLocalState(
            rows = listOf(linkOnly("nav")),
            progressOf = { 0.9f },
            cachedIds = setOf("nav"),
        )
        assertNull(merged[0].progress)
        assertFalse(merged[0].cached)
    }

    @Test
    fun `FULLTEXT 行 cached 按本机缓存集合判定`() {
        val merged = mergeLocalState(
            rows = listOf(fullText("a"), fullText("b")),
            progressOf = { null },
            cachedIds = setOf("a"),
        )
        assertTrue(merged[0].cached)
        assertFalse(merged[1].cached)
    }
}
