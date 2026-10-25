package cn.tonglu.app.data.readingimport

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReadingImportParserTest {
    private fun <T> epub(extra: Map<String, String> = emptyMap(), action: (File) -> T): T {
        val file = File.createTempFile("reading-fixture-", ".epub")
        try {
            val entries = linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""",
                "OEBPS/book.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>原创测试书</dc:title></metadata><manifest><item id="b" href="chapters/b.xhtml"/><item id="a" href="chapters/a.xhtml"/><item id="nav" href="nav.xhtml" properties="nav"/></manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>""",
                "OEBPS/chapters/a.xhtml" to "<html><head><title>Last</title></head><body><h1>第二章</h1><p>The second paragraph.</p></body></html>",
                "OEBPS/chapters/b.xhtml" to "<html><body><h1>第一章</h1><p>Hello <em>world</em>.</p><p>Next paragraph.</p><script>bad()</script><img src='https://invalid.test/private' alt='插图'/></body></html>",
                "OEBPS/nav.xhtml" to "<html><body><nav><a href='chapters/b.xhtml'>开始阅读</a></nav></body></html>",
            )
            entries.putAll(extra)
            ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            } }
            return action(file)
        } finally { assertTrue(file.delete()) }
    }

    @Test fun spineOrderAndNavTitleAreRespected() = epub { file ->
        val result = ReadingImportParser.epub(file, "fallback")
        assertEquals("原创测试书", result.title)
        assertEquals(listOf("开始阅读", "第二章"), result.chapters.map { it.title })
        assertTrue(result.chapters.first().body.contains("Hello *world*.\n\nNext paragraph."))
        assertFalse(result.chapters.first().body.contains("bad()"))
        assertFalse(result.chapters.first().body.contains("https://invalid.test/private"))
        assertTrue(result.chapters.first().body.contains("【图片：插图】"))
    }
    @Test fun missingSpineResourceIsRejected() = epub(mapOf("OEBPS/chapters/b.xhtml" to "")) { file ->
        // 空章节跳过，其余章节仍可阅读，不伪造该章文字。
        assertEquals(1, ReadingImportParser.epub(file, "fallback").chapters.size)
    }
    @Test fun badManifestReferenceIsRejected(): Unit = epub(mapOf("OEBPS/book.opf" to
        "<package><manifest/><spine><itemref idref='missing'/></spine></package>")) { file ->
        assertThrows(IllegalArgumentException::class.java) { ReadingImportParser.epub(file, "fallback") }
    }
    @Test fun encryptedBodyIsRejected(): Unit = epub(mapOf("META-INF/encryption.xml" to
        "<encryption><EncryptedData><CipherData><CipherReference URI='OEBPS/chapters/b.xhtml'/></CipherData></EncryptedData></encryption>")) { file ->
        assertThrows(IllegalArgumentException::class.java) { ReadingImportParser.epub(file, "fallback") }
    }
    @Test fun fontObfuscationDoesNotBlockText() = epub(mapOf("META-INF/encryption.xml" to
        "<encryption><CipherReference URI='OEBPS/fonts/font.otf'/></encryption>")) { file ->
        assertEquals(2, ReadingImportParser.epub(file, "fallback").chapters.size)
    }
    @Test fun traversalZipEntryIsRejected(): Unit = epub(mapOf("../outside.txt" to "forbidden")) { file ->
        assertThrows(IllegalArgumentException::class.java) { ReadingImportParser.epub(file, "fallback") }
    }
    @Test fun relativeLinksAreNormalizedWithoutEscapingRoot() {
        assertEquals("OEBPS/text/a.xhtml", ReadingImportParser.resolve("OEBPS/nav/toc.xhtml", "../text/a.xhtml#anchor"))
        assertEquals("OEBPS/a+b.xhtml", ReadingImportParser.resolve("OEBPS/toc.xhtml", "a+b.xhtml"))
        assertThrows(IllegalArgumentException::class.java) { ReadingImportParser.resolve("OEBPS/book.opf", "../../outside") }
        assertThrows(IllegalArgumentException::class.java) { ReadingImportParser.resolve("OEBPS/book.opf", "%2e%2e/%2e%2e/outside") }
        assertThrows(IllegalArgumentException::class.java) { ReadingImportParser.resolve("", "https://invalid.test/book") }
    }
    @Test fun oversizedEntryIsRejected(): Unit = epub(mapOf("OEBPS/chapters/b.xhtml" to "a".repeat(ReadingImportParser.MAX_ENTRY_BYTES + 1))) { file ->
        assertThrows(IllegalArgumentException::class.java) { ReadingImportParser.epub(file, "fallback") }
    }
    @Test fun splittingPreservesEveryCharacterAndSurrogates() {
        val body = "abc😀\n\n".repeat(40000)
        val parts = ReadingImportParser.split(ImportedChapter("long", body))
        assertTrue(parts.size > 1)
        assertEquals(body, parts.joinToString("") { it.body })
        assertTrue(parts.all { it.body.length <= ReadingImportParser.MAX_CHAPTER_CHARS && !Character.isHighSurrogate(it.body.last()) })
    }
    @Test fun textBomAndLiteralMarkdownAreHandled() {
        val file = File.createTempFile("reading-text-", ".txt")
        try {
            file.writeText("\uFEFFHello *world*.\r\n\r\nNext.", Charsets.UTF_16LE)
            val result = ReadingImportParser.text(file, "notes.txt", "TEXT")
            assertEquals("notes", result.title)
            assertEquals("Hello \\*world\\*.\n\nNext.", result.chapters.single().body)
        } finally { assertTrue(file.delete()) }
    }
    @Test fun boundedStreamsAndWriterRejectOversize() {
        assertThrows(IllegalArgumentException::class.java) { ReadingImportParser.readBounded("12345".byteInputStream(), 4) }
        val writer = BoundedTextWriter(4)
        writer.write("1234")
        assertEquals("1234", writer.toString())
        assertThrows(IllegalArgumentException::class.java) { writer.write("5") }
    }
    @Test fun mimeFallbackIsExplicit() {
        assertEquals("PDF", ReadingImportParser.format("file", "application/pdf"))
        assertEquals("EPUB", ReadingImportParser.format("BOOK.EPUB", "application/octet-stream"))
        assertThrows(IllegalArgumentException::class.java) { ReadingImportParser.format("file.bin", "application/octet-stream") }
    }
    @Test fun deeplyNestedMarkupIsRejected() {
        val document = org.jsoup.Jsoup.parse("<body>" + "<div>".repeat(150) + "Text" + "</div>".repeat(150) + "</body>")
        assertThrows(IllegalArgumentException::class.java) { ReadingImportParser.htmlMarkdown(document.body()) }
    }
}
