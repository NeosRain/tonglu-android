package cn.tonglu.app.ui.learning

import cn.tonglu.app.data.english.materials.ExamMaterialListItem
import org.junit.Assert.*
import org.junit.Test

class ExamReadingMappingTest {
    private fun json(extra: String = "", body: String = "We learn together.", availability: String = "FULLTEXT") = """{
      "articles":[{"id":"fixture","sourceRevision":"r1","title":"开发夹具",
      "sectionId":"english","summary":"开发夹具","author":"test","kind":"ARTICLE",
      "sourceUrl":"https://example.invalid","licenseId":"MIT","licenseUrl":"",
      "attribution":"开发夹具","changeNote":"","updatedAt":"2026-10-14","body":"$body",
      "exam":{"examType":"CET4","language":"en","recommended":true,"availability":"$availability"}$extra}]}"""

    @Test fun sameIdentityAndBodyArePreserved() {
        val parsed = packedExamReading(json())
        assertEquals("fixture", parsed.rows.single().articleId)
        assertEquals("fixture", parsed.articles.values.single().id)
        assertEquals("We learn together.", parsed.articles.values.single().body)
        assertEquals("r1", parsed.rows.single().revision)
    }
    @Test fun unknownDateIsNotInvented() {
        val row = packedExamReading(json()).rows.single()
        assertNull(row.year); assertNull(row.session); assertNull(row.paper)
    }
    @Test fun originalLinkCannotPretendFullText() {
        val parsed = packedExamReading(json(body = "", availability = "SOURCE_LINK"))
        assertFalse(parsed.rows.single().isFullText)
        assertTrue(parsed.articles.values.single().body.isEmpty())
    }
    @Test fun linkWithBodyIsRejectedWithVisibleWarning() {
        val parsed = packedExamReading(json(availability = "SOURCE_LINK"))
        assertTrue(parsed.rows.isEmpty()); assertEquals(1, parsed.warnings.size)
    }
    @Test fun emptyFullTextIsRejectedWithVisibleWarning() {
        val parsed = packedExamReading(json(body = ""))
        assertTrue(parsed.rows.isEmpty()); assertEquals(1, parsed.warnings.size)
    }
    @Test fun missingExamMetadataDoesNotClassifyChineseArticlesAsEnglish() {
        val parsed = packedExamReading("""{"articles":[{"id":"normal","title":"普通教程"}]}""")
        assertTrue(parsed.rows.isEmpty()); assertTrue(parsed.warnings.isEmpty())
    }
    @Test fun rowMappingKeepsRealProgressAndCache() {
        val row = packedExamReading(json()).rows.single()
        val mapped = ExamMaterialListItem(row, 0.4f, true).entryRow()
        assertEquals(0.4f, mapped.progress!!, 0f)
        assertTrue(mapped.cached); assertTrue(mapped.fullText)
        assertEquals("fixture", mapped.articleId)
    }
    @Test fun nullProgressStaysUnstarted() {
        val row = packedExamReading(json()).rows.single()
        assertNull(ExamMaterialListItem(row, null, false).entryRow().progress)
    }
}
