package cn.tonglu.app.data.english.intensive

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteCetCodecTest {
    // Original test prose, not an exam passage.
    private val text = "Students read books in the library because careful reading helps them understand new ideas and communicate with others."
    private fun hash(value: String) = IntensiveCodec.hash(value.toByteArray())
    private fun spec(): JSONObject {
        val normalized = RemoteCetCodec.normalized(text)
        val tokens = JSONArray().put(JSONArray(listOf(1, 0, 8, 2, "主语", "名词", "nsubj")))
            .put(JSONArray(listOf(2, 8, 12, 0, "谓语", "动词", "root")))
            .put(JSONArray(listOf(3, 12, 17, 2, "宾语", "名词", "obj")))
        val sentences = JSONArray().put(JSONObject().put("id", "p1-s1").put("length", normalized.length).put("tokens", tokens))
        return JSONObject().put("id", "cet4-2026-06-1-passage1").put("exam", "CET4").put("year", 2026).put("month", 6)
            .put("suite", 1).put("kind", "passage1").put("wordCount", 20).put("sentences", 1)
            .put("sourceUrl", "https://raw.githubusercontent.com/example/repo/" + "a".repeat(40) + "/paper.pdf")
            .put("sourceSha256", hash("source fixture")).put("sourceFormat", "pdf").put("grammarDevice", "cuda:0").put("modelFingerprint", "fixture")
            .put("originalLength", normalized.length).put("originalSha256", hash(normalized)).put("prefixSha256", hash(normalized.take(48)))
            .put("bodySha256", hash(normalized)).put("textSha256", hash(text)).put("fills", JSONArray())
            .put("rightsNotice", "Original unit-test fixture").put("paragraphs", JSONArray().put(JSONObject()
                .put("length", normalized.length).put("sentences", sentences)))
    }
    private fun entry(v: JSONObject = spec()): IntensiveEntry = RemoteCetCodec.catalog(JSONObject()
        .put("schemaVersion", 1).put("metadataOnly", true).put("articles", JSONArray().put(v)).toString()).single()

    @Test fun originalDocumentMustMatchBeforeOffsetsAreApplied() {
        val result = RemoteCetCodec.article(entry(), "Directions.\n$text\nQuestions follow.")
        assertEquals(text, result.paragraphs.single().text)
        val sentence = result.paragraphs.single().sentences.single()
        assertEquals(listOf("Students", "read", "books"), sentence.tokens.map { it.text })
        assertEquals(listOf("主语", "谓语", "宾语"), sentence.tokens.map { it.role })
        assertTrue(sentence.tokens.all { sentence.text.substring(it.start, it.end) == it.text })
    }

    @Test fun changedDocumentDoesNotReceiveStaleColors() {
        assertThrows(IllegalArgumentException::class.java) { RemoteCetCodec.article(entry(), text.replace("read books", "write notes")) }
    }

    @Test fun prefixAloneCannotSelectAnUnrelatedPassage() {
        val falseMatch = text.replace("communicate", "collaborate")
        assertEquals(text, RemoteCetCodec.article(entry(), "$falseMatch\n$text").paragraphs.single().text)
    }

    @Test fun invalidTokenOffsetsAreRejected() {
        val v = spec()
        v.getJSONArray("paragraphs").getJSONObject(0).getJSONArray("sentences").getJSONObject(0)
            .getJSONArray("tokens").getJSONArray(0).put(2, 10000)
        assertThrows(IllegalArgumentException::class.java) { RemoteCetCodec.article(entry(v), text) }
    }

    @Test fun sourceMustBeHttpsAndCommitPinned() {
        val good = spec().getString("sourceUrl")
        assertTrue(RemoteCetCodec.allowedSource(good))
        for (bad in listOf(good.replace("https:", "http:"), good.replace("a".repeat(40), "main"), good + "?x=1", "https://localhost/paper.pdf"))
            assertFalse(RemoteCetCodec.allowedSource(bad))
    }

    @Test fun normalizationHandlesPdfLigaturesAndFullWidthAscii() {
        assertEquals("afistudents", RemoteCetCodec.normalized("Ａ ﬁ Students"))
    }
}
