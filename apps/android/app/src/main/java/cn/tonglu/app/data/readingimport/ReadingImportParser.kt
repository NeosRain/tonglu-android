package cn.tonglu.app.data.readingimport

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

data class ImportedChapter(val title: String, val body: String, val page: Int? = null,
    val textAvailable: Boolean = true)
data class ParsedReadingDocument(val title: String, val format: String,
    val chapters: List<ImportedChapter>, val warnings: List<String> = emptyList())

/** 有界解析：ZIP 不落地，不执行脚本、不请求网络，不以文件扩展名冒充格式校验。 */
object ReadingImportParser {
    const val MAX_FILE_BYTES = 50L * 1024 * 1024
    const val MAX_TEXT_CHARS = 8_000_000
    const val MAX_CHAPTER_CHARS = 120_000 // UTF-8 最坏仍远小于单行 CursorWindow 容量。
    const val MAX_PARTS = 1500
    const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

    fun format(name: String, mime: String?): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "PDF"
        "epub" -> "EPUB"
        "md", "markdown" -> "MARKDOWN"
        "txt" -> "TEXT"
        else -> when (mime?.lowercase()) {
            "application/pdf" -> "PDF"
            "application/epub+zip" -> "EPUB"
            "text/markdown", "text/x-markdown" -> "MARKDOWN"
            "text/plain" -> "TEXT"
            else -> throw IllegalArgumentException("支持 TXT、Markdown、PDF 和 EPUB，请检查文件格式")
        }
    }

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("读取已取消")
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "文件内容过大，请拆分后导入" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    fun text(file: File, name: String, format: String): ParsedReadingDocument {
        val bytes = file.inputStream().use { readBounded(it, MAX_TEXT_CHARS) }
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                .removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("文本编码不支持，请另存为 UTF-8 或带 BOM 的 UTF-16", error)
        }
        require(text.isNotBlank() && !text.contains('\u0000')) { "文件没有可读文字，或并非文本文件" }
        val title = name.substringBeforeLast('.', name)
        return ParsedReadingDocument(title, format, split(ImportedChapter(title,
            if (format == "TEXT") plainMarkdown(text) else text)))
    }

    /** 转为安全的本机 ZIP 路径。href 允许合法 ../，但绝不允许逃出 ZIP 根目录。 */
    internal fun resolve(base: String, href: String): String {
        val raw = URLDecoder.decode(href.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
        require(!raw.startsWith('/') && !raw.contains('\\') && !raw.contains(':') && !raw.contains('\u0000')) {
            "EPUB 包含非法资源路径"
        }
        val parts = base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (segment in raw.split('/')) when (segment) {
            "", "." -> Unit
            ".." -> { require(parts.isNotEmpty()) { "EPUB 资源路径越界" }; parts.removeAt(parts.lastIndex) }
            else -> parts.add(segment)
        }
        return parts.joinToString("/")
    }

    fun epub(file: File, fallbackTitle: String): ParsedReadingDocument = ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().take(6001).toList()
        require(entries.size <= 6000) { "EPUB 包含过多资源，请拆分后导入" }
        require(entries.sumOf { it.size.coerceAtLeast(0) } <= 96L * 1024 * 1024) { "EPUB 解压后过大" }
        val names = hashSetOf<String>()
        entries.forEach { entry ->
            require(!entry.name.startsWith('/') && !entry.name.contains('\\') &&
                entry.name.split('/').none { it == ".." || it == "." } &&
                !entry.name.contains(':') && names.add(entry.name)) { "EPUB 包含重复或非法资源路径" }
        }
        var readBytes = 0L
        fun read(path: String, limit: Int = MAX_ENTRY_BYTES): ByteArray {
            val entry = zip.getEntry(path) ?: throw IllegalArgumentException("EPUB 缺少资源：$path")
            require(entry.size <= limit) { "EPUB 单个章节过大，请拆分后导入" }
            val bytes = zip.getInputStream(entry).use { readBounded(it, limit) }
            readBytes += bytes.size
            require(readBytes <= 32L * 1024 * 1024) { "EPUB 正文过大，请拆分后导入" }
            return bytes
        }
        fun xml(path: String) = Jsoup.parse(java.io.ByteArrayInputStream(read(path, 1024 * 1024)), null, "", Parser.xmlParser())
        fun Element.local(name: String) = getAllElements().filter { it.tagName().substringAfter(':') == name }
        val container = xml("META-INF/container.xml")
        val packagePath = container.local("rootfile").firstOrNull()?.attr("full-path")
            ?.let { resolve("", it) } ?: throw IllegalArgumentException("EPUB 缺少书籍目录")
        val opf = xml(packagePath)
        val title = (opf.local("title").firstOrNull()?.text()?.takeIf { it.isNotBlank() } ?: fallbackTitle).take(240)
        val manifest = opf.local("manifest").firstOrNull()?.children()?.associateBy { it.attr("id") }
            ?: throw IllegalArgumentException("EPUB 缺少资源清单")
        val spine = opf.local("spine").firstOrNull()
            ?: throw IllegalArgumentException("EPUB 缺少阅读顺序")
        val spineItems = spine.children().filter { it.tagName().substringAfter(':') == "itemref" && it.attr("linear") != "no" }
            .map { ref -> manifest[ref.attr("idref")] ?: throw IllegalArgumentException("EPUB 章节引用不完整") }
        require(spineItems.isNotEmpty() && spineItems.size <= MAX_PARTS) { "EPUB 章节为空或过多" }
        val paths = spineItems.map { resolve(packagePath, it.attr("href")) }
        if (zip.getEntry("META-INF/encryption.xml") != null) {
            val encrypted = xml("META-INF/encryption.xml").local("CipherReference")
                .map { resolve("", it.attr("URI")) }
            require(paths.none { it in encrypted }) { "这本 EPUB 的正文受 DRM 保护，暂不支持导入" }
        }
        // EPUB3 nav 与 EPUB2 NCX 只用来命名，真正顺序始终以 OPF spine 为准。
        val labels = linkedMapOf<String, String>()
        manifest.values.firstOrNull { "nav" in it.attr("properties").split(' ') }?.let { nav ->
            val path = resolve(packagePath, nav.attr("href"))
            val doc = Jsoup.parse(String(read(path), Charsets.UTF_8))
            doc.select("nav a[href]").forEach { a ->
                if (a.text().isNotBlank()) labels.putIfAbsent(resolve(path, a.attr("href")), a.text())
            }
        }
        manifest[spine.attr("toc")]?.let { ncx ->
            val path = resolve(packagePath, ncx.attr("href"))
            xml(path).local("navPoint").forEach { point ->
                val content = point.children().firstOrNull { it.tagName().substringAfter(':') == "content" }
                val label = point.children().firstOrNull { it.tagName().substringAfter(':') == "navLabel" }?.text()
                if (content != null && !label.isNullOrBlank()) labels.putIfAbsent(resolve(path, content.attr("src")), label)
            }
        }
        var chars = 0
        var skipped = 0
        val chapters = paths.flatMapIndexed { index, path ->
            val document = Jsoup.parse(java.io.ByteArrayInputStream(read(path)), null, "")
            document.select("script,style,noscript,iframe,object,svg").remove()
            val chapterTitle = labels[path] ?: document.selectFirst("h1,h2")?.text()?.takeIf { it.isNotBlank() }
                ?: document.title().takeIf { it.isNotBlank() } ?: "第 ${index + 1} 章"
            val body = htmlMarkdown(document.body())
            chars += body.length
            require(chars <= MAX_TEXT_CHARS) { "EPUB 正文过大，请拆分后导入" }
            if (body.isBlank()) { skipped++; emptyList() } else split(ImportedChapter(chapterTitle.take(240), body))
        }
        require(chapters.isNotEmpty()) { "EPUB 没有可提取的正文；暂不支持纯图片或受保护的电子书" }
        require(chapters.size <= MAX_PARTS) { "章节过多，请拆分电子书" }
        ParsedReadingDocument(title, "EPUB", chapters,
            buildList {
                add("EPUB 导入文字与章节；原书图片、音视频及复杂排版暂不保留。")
                if (skipped > 0) add("有 $skipped 个空白或无文字章节未导入。")
            })
    }

    internal fun plainMarkdown(text: String): String = text.replace(Regex("([\\\\`*{}\\[\\]<>#_])"), "\\\\$1")

    internal fun htmlMarkdown(node: Node, depth: Int = 0): String {
        require(depth <= 128) { "EPUB 正文嵌套过深，请转换为普通文本后导入" }
        if (node is TextNode) return plainMarkdown(node.wholeText.replace(Regex("\\s+"), " "))
        if (node !is Element) return ""
        val inner = node.childNodes().joinToString("") { htmlMarkdown(it, depth + 1) }
        return when (val tag = node.normalName()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> "\n\n${"#".repeat(tag.last().digitToInt())} ${inner.trim()}\n\n"
            "p", "div", "section", "article", "blockquote", "tr" -> "\n\n${inner.trim()}\n\n"
            "li" -> "\n- ${inner.trim()}\n"
            "ul", "ol" -> "\n$inner\n"
            "br" -> "\n"
            "strong", "b" -> "**$inner**"
            "em", "i" -> "*$inner*"
            "a" -> {
                val href = node.attr("href")
                if ((href.startsWith("https://") || href.startsWith("http://")) &&
                    href.none { it.isWhitespace() || it in "()<>" }) "[$inner]($href)" else inner
            }
            "img" -> node.attr("alt").takeIf { it.isNotBlank() }?.let { "【图片：${plainMarkdown(it)}】" } ?: ""
            "script", "style", "noscript", "iframe", "object", "svg" -> ""
            else -> inner
        }.let { if (tagIsBody(node)) it.replace(Regex("\n[ \\t]+"), "\n").replace(Regex("\n{3,}"), "\n\n").trim() else it }
    }
    private fun tagIsBody(node: Element) = node.normalName() == "body"

    internal fun split(chapter: ImportedChapter): List<ImportedChapter> {
        if (chapter.body.length <= MAX_CHAPTER_CHARS) return listOf(chapter)
        val result = mutableListOf<ImportedChapter>()
        var start = 0
        while (start < chapter.body.length) {
            var end = (start + MAX_CHAPTER_CHARS).coerceAtMost(chapter.body.length)
            if (end < chapter.body.length) {
                val paragraph = chapter.body.lastIndexOf("\n\n", end)
                if (paragraph > start + MAX_CHAPTER_CHARS / 2) end = paragraph
                if (Character.isHighSurrogate(chapter.body[end - 1])) end--
            }
            result += chapter.copy(title = "${chapter.title}（${result.size + 1}）", body = chapter.body.substring(start, end))
            start = end
        }
        return result
    }
}
