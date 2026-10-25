package cn.tonglu.app.data.english.intensive

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class IntensiveReadingExperienceTest {
    private fun sentence(id: String, text: String) = IntensiveSentence(id, text, "参考", emptyList())
    @Test fun repeatedSentencesHaveIndependentTapTargets() {
        val first = sentence("p1-s1", "We learn.")
        val second = sentence("p1-s2", "We learn.")
        val paragraph = IntensiveParagraph("We learn. We learn.", "参考", listOf(first,second))
        assertEquals(listOf(0,10), intensiveSentenceRanges(paragraph).map { it.start })
        assertEquals(first, intensiveSentenceAt(paragraph, 0))
        assertEquals(second, intensiveSentenceAt(paragraph, 10))
        assertNull(intensiveSentenceAt(paragraph,9))
        assertNull(intensiveSentenceAt(paragraph,19))
    }
    @Test fun offsetsCountSurrogatesAndDoNotMatchOutsideText() {
        val first = sentence("p1-s1", "😀 We learn.")
        val next = sentence("p1-s2", "We read.")
        val paragraph = IntensiveParagraph(first.text + "\n" + next.text, "参考", listOf(first,next))
        assertEquals(13, intensiveSentenceRanges(paragraph)[1].start)
        assertEquals(next, intensiveSentenceAt(paragraph,13))
        assertNull(intensiveSentenceAt(paragraph,-1))
    }
    @Test fun translationDraftKeysIsolateArticleRevisionAndOccurrence() {
        val entry = IntensiveEntry("article", "CET4",2026,6,1,"passage1",200,2,"a",1,"text-a","model")
        val sentence = sentence("p1-s1","We learn.")
        assertNotEquals(intensiveTranslationKey(entry,sentence), intensiveTranslationKey(entry.copy(textSha256="text-b"),sentence))
        assertNotEquals(intensiveTranslationKey(entry,sentence), intensiveTranslationKey(entry,sentence.copy(id="p1-s2")))
    }
    private fun compressed(text: String): ByteArray {
        val output=ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return output.toByteArray()
    }
    @Test fun bundledGlossDecodesGzipAndKeepsRealPhonetics() {
        val bytes=compressed("""{"english":"Organic","chinese":"有机的","ukPronunciation":"ɔːˈɡænɪk"}""")
        val gloss=decodeIntensiveGlossBook(bytes,IntensiveCodec.hash(bytes),1,"synthetic").getValue("organic")
        assertEquals("有机的",gloss.chinese)
        assertEquals("ɔːˈɡænɪk",gloss.uk)
        assertNull(gloss.us)
    }
    @Test fun bundledGlossRejectsStaleDigestAndTruncatedCorpus() {
        val bytes=compressed("""{"english":"organic","chinese":"有机的"}""")
        assertThrows(IllegalArgumentException::class.java) { decodeIntensiveGlossBook(bytes,"0".repeat(64),1,"synthetic") }
        assertThrows(IllegalArgumentException::class.java) { decodeIntensiveGlossBook(bytes,IntensiveCodec.hash(bytes),2,"synthetic") }
    }
    @Test fun mainClauseDoesNotMixSubordinateSubjectsOrSplitContractions() {
        val text="They won’t say he is ready."
        fun token(id: Int,word: String,start: Int,end: Int,head: Int,relation: String,role: String,lemma: String=word)=
            IntensiveToken(id,word,word,lemma,start,end,head,relation,role,"测试")
        val tokens=listOf(
            token(1,"They",0,4,4,"nsubj","主语"),token(2,"wo",5,7,4,"aux","谓语"),
            token(3,"n’t",7,10,4,"advmod","状语","not"),token(4,"say",11,14,0,"root","谓语"),
            token(5,"he",15,17,7,"nsubj","主语"),token(6,"is",18,20,7,"cop","谓语"),
            token(7,"ready",21,26,4,"ccomp","从句"))
        val parts=intensiveMainClause(IntensiveSentence("p1-s1",text,"参考",tokens)).toMap()
        assertEquals("They",parts["主语"])
        assertEquals("won’t say",parts["谓语"])
        assertFalse(parts.values.any { it=="he" || it.contains(" he") || it=="is" })
    }
}
