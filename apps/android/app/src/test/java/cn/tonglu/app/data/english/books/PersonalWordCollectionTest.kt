package cn.tonglu.app.data.english.books

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PersonalWordCollectionTest {
    private fun word(value: String = "Read") = PersonalBookWord(value, value, "阅读", "We read.", "cet4-2026-06-1-passage1", "s1:3-7")
    @Test fun examTagsChooseBooksAndRepeatClickIsIdempotentUntilExplicitPlusOne() {
        val start = PersonalBooksSnapshot("alice")
        val first = collectWordInSnapshot(start, word(), "CET4", false, 30).first
        assertEquals("四级单词书", first.books.single().name)
        assertEquals(1, first.books.single().words.single().collectionCount)
        val repeat = collectWordInSnapshot(first, word("READ"), "CET4", false, 30).first
        assertEquals(first, repeat)
        val plus = collectWordInSnapshot(repeat, word("read"), "CET4", true, 30).first
        assertEquals(1, plus.books.single().words.size)
        assertEquals(2, plus.books.single().words.single().collectionCount)
        assertEquals(2, plus.books.single().words.single().encounters!!.size)
        val other = collectWordInSnapshot(plus, word("read").copy(articleId = "other"), null, true, 30).first
        assertEquals(1, other.books.size)
        assertEquals(3, other.books.single().words.single().collectionCount)
        val cet6 = collectWordInSnapshot(other, word("think"), "CET6", false, 30).first
        assertEquals(setOf("四级单词书", "六级单词书"), cet6.books.map { it.name }.toSet())
    }
    @Test fun legacyJsonStillMeansOneCollectionAndMergePreservesCountsAndContexts() {
        val legacy = Gson().fromJson("""{"surface":"READ","lemma":"Read","meaning":"我的释义","sentence":"Old source","articleId":"old","attempts":2,"correct":1}""", PersonalBookWord::class.java)
        assertEquals(1, legacy.collectionCount)
        val newer = word("read").copy(collectionTimes = 3, attempts = 4, correct = 3)
        val merged = mergeCollectedWords(listOf(legacy, newer, word("think")))
        assertEquals(2, merged.size)
        val read = merged.first { it.key == "read" }
        assertEquals(4, read.collectionCount)
        assertEquals(6, read.attempts)
        assertEquals(4, read.correct)
        assertEquals("我的释义", read.meaning)
        assertEquals(setOf("old", newer.articleId), read.encounters!!.map { it.articleId }.toSet())
    }
    @Test fun newWordWithoutMeaningDoesNotChangeOriginalSnapshot() {
        val start = PersonalBooksSnapshot("alice")
        try { collectWordInSnapshot(start, word().copy(meaning = ""), "CET4", false, 30); fail("Expected validation") }
        catch (_: IllegalArgumentException) { }
        assertTrue(start.books.isEmpty())
    }
}
