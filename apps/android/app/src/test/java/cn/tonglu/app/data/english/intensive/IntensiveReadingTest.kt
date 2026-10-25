package cn.tonglu.app.data.english.intensive

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IntensiveReadingTest {
    @Test fun emptyCatalogIsValidAndDoesNotInventArticles() {
        assertTrue(IntensiveCodec.catalog("""{"schemaVersion":1,"articles":[]}""").isEmpty())
    }
    private fun fixture(): Pair<IntensiveEntry, JSONObject> {
        val sentence = "We help them."
        val root = JSONObject("""{"id":"cet4-2026-06-1-passage1","complete":true,"modelFingerprint":"MODEL","grammarDevice":"cuda:0","translationDevice":"cuda:0","sourceUrl":"https://example.org/paper","license":"review","original":"We help them.","fills":[],"analysisNotice":"机器分析","paragraphs":[{"id":1,"text":"We help them.","translation":"我们帮助他们。","sentences":[{"id":"p1-s1","text":"We help them.","translation":"我们帮助他们。","sha256":"HASH","tokens":[{"id":1,"start":0,"end":2,"text":"We","lemma":"we","head":2,"relation":"nsubj","role":"主语","posLabel":"代词"},{"id":2,"start":3,"end":7,"text":"help","lemma":"help","head":0,"relation":"root","role":"谓语","posLabel":"动词"},{"id":3,"start":8,"end":12,"text":"them","lemma":"they","head":2,"relation":"obj","role":"宾语","posLabel":"代词"},{"id":4,"start":12,"end":13,"text":".","lemma":".","head":2,"relation":"punct","role":"标点","posLabel":"标点"}]}]}]}""")
        root.put("modelFingerprint", "a".repeat(64))
        root.getJSONArray("paragraphs").getJSONObject(0).getJSONArray("sentences").getJSONObject(0)
            .put("sha256", IntensiveCodec.hash(sentence.toByteArray()))
        val bytes = root.toString().toByteArray()
        return IntensiveEntry(root.getString("id"), "CET4", 2026, 6, 1, "passage1", 110, 1,
            IntensiveCodec.hash(bytes), bytes.size, IntensiveCodec.hash(sentence.toByteArray()), "a".repeat(64)) to root
    }
    private fun decode(mutator: (JSONObject) -> Unit = {}): IntensiveArticle {
        val (entry, root) = fixture(); mutator(root)
        val bytes = root.toString().toByteArray()
        return IntensiveCodec.article(entry.copy(sha256 = IntensiveCodec.hash(bytes), bytes = bytes.size), bytes)
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("must reject invalid pack") } catch (_: IllegalArgumentException) { }
    }
    @Test fun validSentenceRetainsOffsetsAndDependencies() {
        val sentence = decode().paragraphs.single().sentences.single()
        assertEquals("我们帮助他们。", sentence.translation)
        assertEquals("主语", sentence.tokens.first().role)
        assertEquals(2, sentence.tokens.first().head)
        sentence.tokens.forEach { assertEquals(it.text, sentence.text.substring(it.start, it.end)) }
    }
    @Test fun changedTextCannotReuseOldSentenceProgress() {
        val entry = fixture().first
        assertNotEquals(entry.progressKey, entry.copy(textSha256 = "b".repeat(64)).progressKey)
    }
    @Test fun rejectsModifiedBodyEvenWithValidJson() {
        val (entry, root) = fixture(); root.put("license", "changed")
        rejects { IntensiveCodec.article(entry, root.toString().toByteArray()) }
    }
    @Test fun rejectsCpuOrStaleModel() {
        rejects { decode { it.put("grammarDevice", "cpu") } }
        rejects { decode { it.put("translationDevice", "cpu") } }
        rejects { decode { it.put("modelFingerprint", "b".repeat(64)) } }
    }
    @Test fun rejectsTokenMisalignmentAndDependencyCycles() {
        rejects { decode { it.getJSONArray("paragraphs").getJSONObject(0).getJSONArray("sentences")
            .getJSONObject(0).getJSONArray("tokens").getJSONObject(0).put("start", 1) } }
        rejects { decode {
            val tokens = it.getJSONArray("paragraphs").getJSONObject(0).getJSONArray("sentences").getJSONObject(0).getJSONArray("tokens")
            tokens.getJSONObject(0).put("head", 3); tokens.getJSONObject(2).put("head", 1)
        } }
    }
    @Test fun rejectsOmittedParagraphTextAndMissingTranslation() {
        rejects { decode { it.getJSONArray("paragraphs").getJSONObject(0).put("text", "We help them. Lost sentence.") } }
        rejects { decode { it.getJSONArray("paragraphs").getJSONObject(0).getJSONArray("sentences").getJSONObject(0).put("translation", "") } }
    }
}
