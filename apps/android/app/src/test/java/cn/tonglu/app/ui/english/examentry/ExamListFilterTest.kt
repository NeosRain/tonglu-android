package cn.tonglu.app.ui.english.examentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【开发夹具】筛选纯函数单测。所有数据均为开发夹具，不是真实题源、
 * 不入库、不进生产资料库（exam-entry-contract.md「组件演示夹具明确标记」）。
 */
class ExamListFilterTest {

    private fun row(
        id: String,
        year: Int? = 2024,
        session: String? = "6月",
        paper: String? = "第一套",
        readingType: String? = "仔细阅读",
        progress: Float? = null,
        cached: Boolean = false,
        fullText: Boolean = true,
    ) = ReadingArticleRow(
        articleId = id,
        revision = "v1",
        title = "【开发夹具】$id 标题",
        subtitle = "首句摘要（开发夹具）",
        progress = progress,
        cached = cached,
        fullText = fullText,
        examMeta = if (year == null && session == null && paper == null && readingType == null) null
        else ExamArticleMeta(year, session, paper, readingType),
    )

    // ---------- applyExamFilter ----------

    @Test fun defaultFilterReturnsRowsAsIs() {
        val rows = listOf(row("a"), row("b", year = 2023))
        assertTrue(applyExamFilter(rows, ExamListFilter()) === rows)
    }

    @Test fun yearFilterKeepsMatchingOnly() {
        val rows = listOf(row("a", year = 2024), row("b", year = 2023), row("c", year = 2024, session = "12月"))
        val got = applyExamFilter(rows, ExamListFilter(year = 2024))
        assertEquals(listOf("a", "c"), got.map { it.articleId })
    }

    @Test fun combinedFiltersAreAnded() {
        val rows = listOf(
            row("a", year = 2024, session = "6月", readingType = "仔细阅读"),
            row("b", year = 2024, session = "12月", readingType = "仔细阅读"),
            row("c", year = 2024, session = "6月", readingType = "快速阅读"),
        )
        val got = applyExamFilter(rows, ExamListFilter(year = 2024, session = "6月", readingType = "仔细阅读"))
        assertEquals(listOf("a"), got.map { it.articleId })
    }

    @Test fun rowsWithoutMetaExcludedWhenAnyFilterActive() {
        val noMeta = row("x", year = null, session = null, paper = null, readingType = null)
        val withMeta = row("a", year = 2024)
        // 默认筛选：原样返回全部（含无元数据行）
        assertEquals(listOf("x", "a"), applyExamFilter(listOf(noMeta, withMeta), ExamListFilter()).map { it.articleId })
        // 任一筛选激活：无元数据行被排除（它们不声明任何值，排除比假装匹配诚实）
        assertEquals(
            listOf("a"),
            applyExamFilter(listOf(noMeta, withMeta), ExamListFilter(year = 2024)).map { it.articleId },
        )
    }

    // ---------- examFilterOptions：只从数据派生，不编造 ----------

    @Test fun optionsDerivedFromRowsSortedAndDistinct() {
        val rows = listOf(
            row("a", year = 2024, session = "6月", readingType = "仔细阅读"),
            row("b", year = 2023, session = "6月", readingType = "快速阅读"),
            row("c", year = 2024, session = "12月", readingType = "仔细阅读"),
            row("d", year = null, session = null, readingType = null),
        )
        val options = examFilterOptions(rows)
        assertEquals(listOf(2024, 2023), options.years)
        assertEquals(listOf("6月", "12月"), options.sessions)
        assertEquals(listOf("仔细阅读", "快速阅读"), options.readingTypes)
    }

    @Test fun emptyRowsGiveEmptyOptions() {
        assertTrue(examFilterOptions(emptyList()).isEmpty)
    }

    // ---------- listPageStatus：待收录 与 无匹配 是两个不同的空 ----------

    @Test fun emptyWithoutFilterIsAwaitCatalog() {
        assertEquals(ListPageStatus.AwaitCatalog, listPageStatus(emptyList(), ExamListFilter()))
    }

    @Test fun filterThatEmptiesIsNoMatch() {
        assertEquals(ListPageStatus.NoMatch, listPageStatus(listOf(row("a")), ExamListFilter(year = 1999)))
    }

    @Test fun nonEmptyAfterFilterIsHasRows() {
        assertEquals(ListPageStatus.HasRows, listPageStatus(listOf(row("a")), ExamListFilter(year = 2024)))
    }

    // ---------- progressLabel：null/0/中段/读完 ----------

    @Test fun progressLabels() {
        assertEquals("未开始", progressLabel(null))
        assertEquals("未开始", progressLabel(0f))
        assertEquals("读到35%", progressLabel(0.35f))
        assertEquals("已读完", progressLabel(1f))
    }

    // ---------- filter.summary ----------

    @Test fun summaryNullWhenDefaultAndJoinedOtherwise() {
        assertNull(ExamListFilter().summary())
        assertEquals("2024年 · 6月 · 仔细阅读", ExamListFilter(2024, "6月", "仔细阅读").summary())
        assertTrue(ExamListFilter(year = 2023).summary()!!.contains("2023年"))
    }
}
