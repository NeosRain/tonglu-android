package cn.tonglu.app.data.notes

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ClassNoteExportTest {
    private val body = "  第一行 & <tag> 🍃\t缩进\n\n" + (1..160).joinToString("\n") { "第${it}段 中文与 English 均完整保留" } + "\n  末尾标记  \n"
    private fun note() = ClassNoteExportData("qa-note", "示例课程 & 实验", body, 1_700_000_000_000L)
    private fun export(format: ClassNoteExportFormat, data: ClassNoteExportData = note()): ByteArray =
        ByteArrayOutputStream().also { ClassNoteExport.write(data, format, it) }.toByteArray()
    private fun entries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()); zip.closeEntry() }
        }
    }

    @Test fun `文字导出保留长文空行缩进与Unicode`() {
        for (format in listOf(ClassNoteExportFormat.Text, ClassNoteExportFormat.Markdown)) {
            val text = export(format).toString(Charsets.UTF_8)
            assertTrue(text.contains(body)); assertTrue(text.contains("示例课程 & 实验"))
        }
    }

    @Test fun `Word文档真实XML可解析且全文逐行无损`() {
        val files = entries(export(ClassNoteExportFormat.Word))
        assertTrue(files.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels")))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        files.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { factory.newDocumentBuilder().parse(ByteArrayInputStream(it)) }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(files.getValue("word/document.xml")))
        val paragraphs = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p")
        val lines = (3 until paragraphs.length).map { index ->
            val paragraph = paragraphs.item(index)
            buildString {
                val runs = paragraph.childNodes
                for (r in 0 until runs.length) {
                    val nodes = runs.item(r).childNodes
                    for (i in 0 until nodes.length) when (nodes.item(i).localName) {
                        "t" -> append(nodes.item(i).textContent)
                        "tab" -> append('\t')
                    }
                }
            }
        }
        assertEquals(body, lines.joinToString("\n"))
    }

    @Test fun `资料包只含当前笔记和明确附件且原字节不变`() {
        val root = Files.createTempDirectory("note-export-").toFile()
        val image = File(root, "private-original.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val audio = File(root, "private-recording.m4a").apply { writeBytes(byteArrayOf(8, 7, 6, 5)) }
        try {
            val files = entries(export(ClassNoteExportFormat.Archive, note().copy(images = listOf(image), recordings = listOf(audio))))
            assertEquals(setOf("笔记.md", "note.json", "attachments/图片1.jpg", "attachments/录音1.m4a"), files.keys)
            assertArrayEquals(image.readBytes(), files.getValue("attachments/图片1.jpg"))
            assertArrayEquals(audio.readBytes(), files.getValue("attachments/录音1.m4a"))
            val manifest = org.json.JSONObject(files.getValue("note.json").toString(Charsets.UTF_8))
            assertEquals(body, manifest.getString("text")); assertEquals("qa-note", manifest.getString("id"))
            assertFalse(manifest.toString().contains(root.absolutePath))
        } finally { image.delete(); audio.delete(); root.delete() }
    }

    @Test fun `文件名不含路径并按完整码点截断`() {
        val name = ClassNoteExport.filename(note().copy(text = "../路径:名称? " + "🍃".repeat(80)), ClassNoteExportFormat.Word)
        assertTrue(name.endsWith(".docx")); assertFalse(name.contains('/')); assertFalse(name.contains(':')); assertFalse(name.contains('?'))
        val stem = name.removeSuffix(".docx")
        assertTrue(stem.codePointCount(0, stem.length) <= 36)
        assertEquals(name, name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }

    @Test fun `写入失败必须向调用者报告`() {
        for (format in listOf(ClassNoteExportFormat.Text, ClassNoteExportFormat.Markdown, ClassNoteExportFormat.Word, ClassNoteExportFormat.Archive)) {
            assertThrows(IOException::class.java) {
                ClassNoteExport.write(note(), format, object : OutputStream() {
                    override fun write(value: Int) { throw IOException("QA destination unavailable") }
                })
            }
        }
    }
    @Test fun `富文本导出保留样式 空段落和编号起点`() {
        val html = "<h2>题目</h2><p><b>加粗</b><u>下划线</u></p><p><br/></p><ol start=\"3\"><li>第三</li><li><i>第四</i></li></ol>"
        val paragraphs = ClassNoteExport.styledParagraphs(html)
        assertEquals(listOf("题目", "加粗下划线", "", "3. 第三", "4. 第四"), paragraphs.map { row -> row.joinToString("") { it.text } })
        assertTrue(paragraphs[1].first().bold)
        assertTrue(paragraphs[1].last().underline)
        assertTrue(paragraphs.last().last().italic)
        val rich = note().copy(html = html, markdown = "## 题目\n\n**加粗**")
        assertTrue(ClassNoteExport.text(rich, true).contains("## 题目\n\n**加粗**"))
        assertTrue(ClassNoteExport.text(rich).contains(rich.text))
        val output = java.io.ByteArrayOutputStream()
        ClassNoteExport.write(rich, ClassNoteExportFormat.Word, output)
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            var xml = ""
            while (entry != null) {
                if (entry.name == "word/document.xml") xml = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
            assertTrue(xml.contains("<w:b/>"))
            assertTrue(xml.contains("<w:u w:val=\"single\"/>"))
            assertTrue(xml.contains("3. "))
        }
    }
}
