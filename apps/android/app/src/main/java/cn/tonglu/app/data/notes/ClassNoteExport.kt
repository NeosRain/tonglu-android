package cn.tonglu.app.data.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class ClassNoteExportFormat(val label: String, val extension: String, val mime: String, val detail: String) {
    Text("纯文本 TXT", "txt", "text/plain", "保存全文，适合复制和通用阅读"),
    Markdown("Markdown", "md", "text/markdown", "保存文字和段落，适合继续整理"),
    Word("Word 文档", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "可编辑文档，包含文字和图片"),
    Pdf("PDF 文档", "pdf", "application/pdf", "自动分页，包含文字和图片"),
    Archive("完整资料包 ZIP", "zip", "application/zip", "文字、原图和录音一起保存"),
}

internal data class ClassNoteExportData(
    val id: String,
    val course: String,
    val text: String,
    val createdAt: Long,
    val images: List<File> = emptyList(),
    val recordings: List<File> = emptyList(),
    val warnings: List<String> = emptyList(),
    val html: String? = null,
    val markdown: String? = null,
    val title: String = "",
)

/** One immutable note snapshot. No account preferences, other notes, or WebView data are read. */
internal object ClassNoteExport {
    fun filename(note: ClassNoteExportData, format: ClassNoteExportFormat): String {
        val first = note.title.takeIf { it.isNotBlank() } ?: note.text.lineSequence().firstOrNull { it.isNotBlank() } ?: note.course
        val clean = first.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().trimEnd('.')
        val short = buildString { clean.codePoints().limit(36).forEach { appendCodePoint(it) } }.ifBlank { "课堂笔记" }
        return "$short.${format.extension}"
    }

    fun text(note: ClassNoteExportData, markdown: Boolean = false): String = buildString {
        append(if (markdown) "# " else "").append(note.title.ifBlank { "课堂笔记" }).append("\n\n")
        append(note.course).append('\n').append(date(note)).append("\n\n")
        append(if (markdown) note.markdown ?: note.text else note.text)
        if (note.images.isNotEmpty() || note.recordings.isNotEmpty()) {
            append("\n\n附件：图片 ${note.images.size} 张，录音 ${note.recordings.size} 段。")
            append("本文件保存文字，原始附件可通过完整资料包导出。\n")
        }
        if (note.warnings.isNotEmpty()) append("\n\n").append(note.warnings.joinToString("\n"))
    }

    /** Returns visible warnings for individual unavailable attachments; never silently drops one. */
    fun write(note: ClassNoteExportData, format: ClassNoteExportFormat, output: OutputStream): List<String> {
        val warnings = note.warnings.toMutableList()
        when (format) {
            ClassNoteExportFormat.Text -> output.write(text(note).toByteArray(Charsets.UTF_8))
            ClassNoteExportFormat.Markdown -> output.write(text(note, true).toByteArray(Charsets.UTF_8))
            ClassNoteExportFormat.Word -> word(note, output, warnings)
            ClassNoteExportFormat.Pdf -> pdf(note, output, warnings)
            ClassNoteExportFormat.Archive -> archive(note, output, warnings)
        }
        return warnings
    }

    private fun date(note: ClassNoteExportData) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(note.createdAt))
    private fun xml(value: String) = buildString {
        value.codePoints().forEach { cp ->
            when (cp) {
                38 -> append("&amp;")
                60 -> append("&lt;")
                62 -> append("&gt;")
                34 -> append("&quot;")
                else -> appendCodePoint(if (cp == 9 || cp == 10 || cp == 13 || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF) cp else 0xFFFD)
            }
        }
    }
    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
    }
    private fun ZipOutputStream.entry(name: String, text: String) = entry(name, text.toByteArray(Charsets.UTF_8))

    private fun paragraph(value: String, style: String? = null): String =
        "<w:p>" + (style?.let { "<w:pPr><w:pStyle w:val=\"$it\"/></w:pPr>" } ?: "") +
            "<w:r>" + value.split('\t').joinToString("<w:tab/>") { "<w:t xml:space=\"preserve\">${xml(it)}</w:t>" } + "</w:r></w:p>"

    internal data class StyledRun(val text: String, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val points: Float = 12f)
    internal fun styledParagraphs(html: String): List<List<StyledRun>> {
        val output = mutableListOf<List<StyledRun>>()
        var current = mutableListOf<StyledRun>()
        fun flush(empty: Boolean = false) { if (current.isNotEmpty() || empty) { output += current.toList(); current = mutableListOf() } }
        fun visit(node: org.jsoup.nodes.Node, inherited: StyledRun, insideListItem: Boolean = false) {
            if (node is org.jsoup.nodes.TextNode) {
                val text = node.wholeText
                if (text.isNotEmpty() && !(node.parent() is org.jsoup.nodes.Element && (node.parent() as org.jsoup.nodes.Element).tagName() == "body" && text.isBlank())) current += inherited.copy(text = text)
                return
            }
            if (node !is org.jsoup.nodes.Element) return
            val tag = node.normalName()
            if (tag in setOf("script", "style", "head")) return
            if (tag == "br") {
                val onlyBreak = node.parent()?.childNodes()?.all { it === node || it is org.jsoup.nodes.TextNode && it.wholeText.isBlank() } == true
                if (!onlyBreak) current += inherited.copy(text = "\n")
                return
            }
            val css = node.attr("style").split(';').mapNotNull { declaration ->
                val pair = declaration.split(':', limit = 2); if (pair.size == 2) pair[0].trim().lowercase() to pair[1].trim().lowercase() else null
            }.toMap()
            var style = inherited.copy(text = "",
                bold = tag in setOf("b", "strong", "h1", "h2", "h3", "h4", "h5", "h6") || inherited.bold,
                italic = tag in setOf("i", "em") || inherited.italic, underline = tag == "u" || inherited.underline)
            val headingSize = when (tag) { "h1" -> 24f; "h2" -> 18f; "h3" -> 16f; "h4" -> 14f; else -> null }
            headingSize?.let { style = style.copy(points = it) }
            css["font-weight"]?.let { style = style.copy(bold = it == "bold" || (it.toIntOrNull() ?: 0) >= 600) }
            css["font-style"]?.let { style = style.copy(italic = it == "italic") }
            css["text-decoration"]?.let { style = style.copy(underline = "underline" in it) }
            css["font-size"]?.let { value ->
                val number = value.removeSuffix("em").removeSuffix("px").removeSuffix("pt").removeSuffix("%").toFloatOrNull()
                if (number != null) style = style.copy(points = when { value.endsWith("em") -> inherited.points * number; value.endsWith("%") -> inherited.points * number / 100; value.endsWith("px") -> number * .75f; else -> number }.coerceIn(6f, 72f))
            }
            val block = tag in setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li") && !(insideListItem && tag in setOf("p", "div"))
            if (block) flush()
            val before = output.size
            if (tag == "li") {
                val depth = node.parents().count { it.normalName() in setOf("ol", "ul") }.coerceAtLeast(1)
                val prefix = if (node.parent()?.normalName() == "ol") "${node.previousElementSiblings().count { it.normalName() == "li" } + (node.parent()?.attr("start")?.toIntOrNull() ?: 1)}. " else "• "
                current += style.copy(text = "  ".repeat(depth - 1) + prefix)
            }
            node.childNodes().forEach { visit(it, style, insideListItem || tag == "li") }
            if (block) flush(empty = output.size == before)
        }
        org.jsoup.Jsoup.parseBodyFragment(html).body().childNodes().forEach { visit(it, StyledRun("")) }
        flush()
        return output
    }
    private fun styledParagraph(runs: List<StyledRun>): String = "<w:p>" + runs.joinToString("") { run ->
        "<w:r><w:rPr>" + (if (run.bold) "<w:b/>" else "") + (if (run.italic) "<w:i/>" else "") +
            (if (run.underline) "<w:u w:val=\"single\"/>" else "") + "<w:sz w:val=\"${(run.points * 2).toInt()}\"/></w:rPr>" +
            run.text.split('\n').joinToString("<w:br/>") { line -> line.split('\t').joinToString("<w:tab/>") { "<w:t xml:space=\"preserve\">${xml(it)}</w:t>" } } + "</w:r>"
    } + "</w:p>"
    private fun styledText(html: String): CharSequence {
        val result = android.text.SpannableStringBuilder()
        styledParagraphs(html).forEachIndexed { index, runs ->
            if (index > 0) result.append('\n')
            runs.forEach { run ->
                val start = result.length; result.append(run.text); val end = result.length
                if (start < end) {
                    val type = (if (run.bold) Typeface.BOLD else 0) or (if (run.italic) Typeface.ITALIC else 0)
                    if (type != 0) result.setSpan(android.text.style.StyleSpan(type), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (run.underline) result.setSpan(android.text.style.UnderlineSpan(), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    result.setSpan(android.text.style.AbsoluteSizeSpan(run.points.toInt()), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return result
    }

    private data class Picture(val bitmap: Bitmap, val bytes: ByteArray)
    private fun picture(file: File): Picture {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法读取" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        var bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        val matrix = Matrix()
        val orientation = try { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        catch (cause: Exception) { android.util.Log.w("ClassNoteExport", "图片方向信息无法读取，保留原方向", cause); ExifInterface.ORIENTATION_NORMAL }
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
        }
        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        bitmap.recycle()
        return try {
            val bytes = ByteArrayOutputStream().use { buffer -> check(opaque.compress(Bitmap.CompressFormat.JPEG, 92, buffer)); buffer.toByteArray() }
            Picture(opaque, bytes)
        } catch (cause: Exception) { opaque.recycle(); throw cause }
    }

    private fun word(note: ClassNoteExportData, output: OutputStream, warnings: MutableList<String>) {
        val body = StringBuilder(paragraph(note.title.ifBlank { "课堂笔记" }, "Title") + paragraph(note.course) + paragraph(date(note)))
        if (note.html != null) styledParagraphs(note.html).forEach { body.append(styledParagraph(it)) }
        else note.text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { body.append(paragraph(it)) }
        val images = mutableListOf<String>()
        ZipOutputStream(output).use { zip ->
        note.images.forEachIndexed { index, file ->
            try {
                val image = picture(file)
                try {
                    val ratio = minOf(480f / image.bitmap.width, 620f / image.bitmap.height)
                    val w = (image.bitmap.width * ratio * 12700).toLong(); val h = (image.bitmap.height * ratio * 12700).toLong()
                    val id = index + 1
                    zip.entry("word/media/image$id.jpg", image.bytes)
                    images += "image$id.jpg"
                    body.append("<w:p><w:pPr><w:keepNext/></w:pPr><w:r><w:drawing><wp:inline><wp:extent cx=\"$w\" cy=\"$h\"/><wp:docPr id=\"$id\" name=\"图片$id\" descr=\"笔记图片$id\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"image$id.jpg\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"image$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
                    body.append(paragraph("图片$id"))
                } finally { image.bitmap.recycle() }
            } catch (cause: Exception) {
                android.util.Log.w("ClassNoteExport", "笔记图片导出失败", cause)
                warnings += "图片${index + 1}未能导出"; body.append(paragraph("图片${index + 1}无法读取"))
            }
        }
        if (note.recordings.isNotEmpty()) body.append(paragraph("本篇含 ${note.recordings.size} 段录音，音频文件请使用完整资料包导出。"))
        warnings.forEach { body.append(paragraph(it)) }
            zip.entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""")
            zip.entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="document" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            zip.entry("word/styles.xml", """<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Microsoft YaHei"/><w:color w:val="000000"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="320" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="180"/></w:pPr><w:rPr><w:b/><w:color w:val="000000"/><w:sz w:val="36"/></w:rPr></w:style></w:styles>""")
            zip.entry("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" + images.joinToString("") { name -> "<Relationship Id=\"${name.substringBefore('.')}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$name\"/>" } + "</Relationships>")
            zip.entry("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""" + body + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>")
        }
    }

    private fun pdf(note: ClassNoteExportData, output: OutputStream, warnings: MutableList<String>) {
        val document = PdfDocument()
        try {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f; typeface = Typeface.DEFAULT }
            var page: PdfDocument.Page? = null; var y = 44f; var number = 0
            fun nextPage() {
                page?.let { document.finishPage(it); page = null }
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
                y = 44f
                page!!.canvas.drawText(number.toString(), 290f, 818f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f })
            }
            fun drawText(value: CharSequence, size: Float = 12f, bold: Boolean = false) {
                paint.textSize = size; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, 507).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).setLineSpacing(3f, 1f).build()
                var line = 0
                while (line < layout.lineCount) {
                    if (page == null || y + layout.getLineBottom(line) - layout.getLineTop(line) > 790f) nextPage()
                    val top = layout.getLineTop(line); var end = line + 1
                    while (end < layout.lineCount && y + layout.getLineBottom(end) - top <= 790f) end++
                    val height = layout.getLineBottom(end - 1) - top
                    page!!.canvas.apply { save(); clipRect(44f, y, 551f, y + height); translate(44f, y - top); layout.draw(this); restore() }
                    y += height; line = end
                    if (line < layout.lineCount) nextPage()
                }
                y += 10f
            }
            try {
                drawText(note.title.ifBlank { "课堂笔记" }, 20f, true); drawText(note.course, 11f); drawText(date(note), 10f)
                drawText(note.html?.let(::styledText) ?: note.text)
                note.images.forEachIndexed { index, file ->
                    try {
                        val image = picture(file)
                        try {
                            val ratio = minOf(507f / image.bitmap.width, 650f / image.bitmap.height)
                            val w = image.bitmap.width * ratio; val h = image.bitmap.height * ratio
                            if (page == null || y + h + 28f > 790f) nextPage()
                            page!!.canvas.drawBitmap(image.bitmap, null, RectF(44f, y, 44f + w, y + h), null)
                            y += h + 6f; drawText("图片${index + 1}", 10f)
                        } finally { image.bitmap.recycle() }
                    } catch (cause: Exception) {
                        android.util.Log.w("ClassNoteExport", "笔记图片导出失败", cause)
                        warnings += "图片${index + 1}未能导出"; drawText("图片${index + 1}无法读取")
                    }
                }
                if (note.recordings.isNotEmpty()) drawText("本篇含 ${note.recordings.size} 段录音，音频文件请使用完整资料包导出。", 10f)
                warnings.forEach { drawText(it, 10f) }
                page?.let { document.finishPage(it); page = null }; document.writeTo(output)
            } finally { page?.let { document.finishPage(it) } }
        } finally { document.close() }
    }

    private fun archive(note: ClassNoteExportData, output: OutputStream, warnings: MutableList<String>) {
        ZipOutputStream(output).use { zip ->
            zip.entry("笔记.md", text(note, true))
            val attachments = JSONArray()
            listOf("图片" to note.images, "录音" to note.recordings).forEach { (kind, files) ->
                files.forEachIndexed { index, file ->
                    val ext = file.extension.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                    val name = "attachments/$kind${index + 1}.$ext"
                    try {
                        file.inputStream().use { input -> zip.putNextEntry(ZipEntry(name)); input.copyTo(zip); zip.closeEntry() }
                        attachments.put(JSONObject().put("type", kind).put("file", name))
                    } catch (cause: Exception) {
                        android.util.Log.w("ClassNoteExport", "笔记附件导出失败", cause)
                        warnings += "$kind${index + 1}未能导出"
                    }
                }
            }
            zip.entry("note.json", JSONObject().put("id", note.id).put("course", note.course).put("createdAt", note.createdAt)
                .put("title", note.title).put("text", note.text).putOpt("html", note.html).putOpt("markdown", note.markdown).put("attachments", attachments).put("warnings", JSONArray(warnings)).toString(2))
        }
    }
}
