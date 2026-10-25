package cn.tonglu.app.data.readingimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cn.tonglu.app.data.english.EnglishRepository
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** 文件名永不使用 URI 的路径；PDF 副本仅位于本账号的私有目录。 */
object ReadingImportFiles {
    fun directory(context: Context, owner: String): File {
        require(owner.isNotBlank()) { "缺少学习数据归属" }
        val hash = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "reading-import/$hash")
    }
    fun pdf(context: Context, owner: String, name: String): File {
        require(Regex("[a-f0-9-]{36}\\.pdf").matches(name)) { "PDF 文件引用无效，请重新导入" }
        return File(directory(context, owner), name)
    }
}

data class ReadingImportResult(val title: String, val parts: Int, val warnings: List<String>,
    /** 是否按「试卷」保存（原版 + 逐页待校对；不自动生成题目/答案）。 */
    val paper: Boolean = false)

class ReadingFileImport(context: Context, private val repository: EnglishRepository) {
    private val app = context.applicationContext

    /**
     * @param tags 归入的分类标签（考试精读导入时传「四级」/「六级」，普通导入留空）。
     * @param paper 试卷模式：PDF 每页都**标记为待校对**（textAvailable=false），原版保留格式作为核对；
     *              不把逐页 OCR 结果冒充已识别试题，也不自动生成题目/答案。其它格式按文章导入并如实提示。
     */
    suspend fun import(owner: String, uri: Uri, source: String, license: String,
        tags: String = "",
        paper: Boolean = false,
        onProgress: (String) -> Unit): ReadingImportResult = withContext(Dispatchers.IO) {
        require(source.isNotBlank() && license.isNotBlank()) { "请填写来源和版权备注" }
        require(source.length <= 2000 && license.length <= 2000) { "来源和版权备注各不能超过 2000 字" }
        var name = "导入文件"
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)?.take(240)?.takeIf { it.isNotBlank() } ?: name
        }
        val format = ReadingImportParser.format(name, app.contentResolver.getType(uri))
        val temporary = File.createTempFile("reading-", ".import", app.cacheDir)
        var retained: File? = null
        var saved = false
        try {
            withContext(Dispatchers.Main) { onProgress("正在读取 $name…") }
            app.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= ReadingImportParser.MAX_FILE_BYTES) { "单个文件不能超过 50 MB，请拆分后导入" }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0) { "文件为空" }
                }
            } ?: throw IllegalArgumentException("无法打开文件，请重新选择或先下载到本机")
            // 试卷模式只对 PDF 有意义：原版翻页核对 + 逐页待校对。其它格式按文章导入并明确提示。
            val asPaper = paper && format == "PDF"
            val parsed = when (format) {
                "EPUB" -> ReadingImportParser.epub(temporary, name.substringBeforeLast('.', name))
                "PDF" -> parsePdf(temporary, name, asPaper, onProgress)
                else -> ReadingImportParser.text(temporary, name, format)
            }
            coroutineContext.ensureActive()
            if (format == "PDF") {
                retained = ReadingImportFiles.pdf(app, owner, "${UUID.randomUUID()}.pdf")
                check(retained.parentFile!!.isDirectory || retained.parentFile!!.mkdirs()) { "无法创建本机阅读目录" }
                // 同一应用私有分区内原子移动；失败不入库，原始用户文件完全不动。
                check(temporary.renameTo(retained)) { "无法保存 PDF 副本，请检查剩余储存空间" }
            }
            // withContext 的取消恢复不能把已提交文件误当失败清走。
            withContext(kotlinx.coroutines.NonCancellable) {
                repository.saveImportedDocument(owner, parsed, "$source｜文件：$name", license, retained?.name, tags)
                saved = true
            }
            val warnings = parsed.warnings + if (paper && !asPaper)
                listOf("试卷模式仅支持 PDF 原版；本文件按阅读文章导入。") else emptyList()
            ReadingImportResult(parsed.title, parsed.chapters.size, warnings, asPaper)
        } finally {
            if (temporary.exists() && !temporary.delete()) android.util.Log.w("ReadingImport", "导入临时文件未能清理")
            if (!saved) retained?.let { if (it.exists() && !it.delete()) android.util.Log.w("ReadingImport", "失败导入的副本未能清理") }
        }
    }

    private suspend fun parsePdf(file: File, name: String, paper: Boolean,
        onProgress: (String) -> Unit): ParsedReadingDocument {
        PDFBoxResourceLoader.init(app)
        val loaded = try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(app.cacheDir))
        } catch (cause: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw IllegalArgumentException("PDF 需要密码，暂不支持导入；请使用可自由阅读的副本", cause)
        }
        loaded.use { pdf ->
            require(!pdf.isEncrypted) { "暂不支持加密或需要密码的 PDF，请使用可自由阅读的副本" }
            require(pdf.numberOfPages in 1..1000) { "PDF 页数必须在 1—1000 页之间，请拆分后导入" }
            val canExtract = pdf.currentAccessPermission.canExtractContent()
            val title = pdf.documentInformation.title?.trim()?.take(240)?.takeIf { it.isNotBlank() }
                ?: name.substringBeforeLast('.', name)
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val chapters = mutableListOf<ImportedChapter>()
            var chars = 0
            var garbledPages = 0
            var blankPages = 0
            for (page in 1..pdf.numberOfPages) {
                coroutineContext.ensureActive()
                withContext(Dispatchers.Main) { onProgress("正在处理 $name · 第 $page / ${pdf.numberOfPages} 页") }
                val output = BoundedTextWriter(ReadingImportParser.MAX_TEXT_CHARS - chars)
                if (canExtract) {
                    stripper.startPage = page
                    stripper.endPage = page
                    stripper.writeText(pdf, output)
                }
                val text = output.toString().trim()
                chars += text.length
                // 用户提供的四级试题册就是「视觉清楚、文字层乱码」：乱码不能当正文存进阅读器。
                // 判为不可用时只留一句说明（textAvailable=false），页面仍可 OCR 识别 + 用户校对后写回。
                val trimmed = text.trim()
                // 试卷模式：**每一页都算待校对**，不把文字层或 OCR 结果直接当已识别试题；
                // 原版 PDF 保留格式作为核对底稿。
                val usable = if (paper) false else ReadingTextQuality.isUsablePageText(trimmed)
                // 「乱码」与「文字太少（封面/页眉）」要如实分开：前者可能是坏字体映射，后者只是内容少。
                val garbled = if (paper) false else trimmed.isNotEmpty() && ReadingTextQuality.looksGarbled(trimmed)
                if (garbled) garbledPages += 1
                if (!usable && !garbled && !paper) blankPages += 1
                chapters += ReadingImportParser.split(ImportedChapter("第 $page 页",
                    when {
                        paper -> "试卷第 $page 页：待校对（以 PDF 原版为准；OCR 只生成页级草稿，不自动识别题目结构/答案）。"
                        usable -> ReadingImportParser.plainMarkdown(trimmed)
                        garbled -> "本页文字疑似乱码，未导入乱码正文。请查看 PDF 原版并点「OCR 识别并校对」。"
                        trimmed.isNotEmpty() -> "本页可提取文字过少（封面/页眉等），未作为正文导入。可查看 PDF 原版并点「OCR 识别并校对」。"
                        else -> "本页没有可提取文字。可查看 PDF 原版并点「OCR 识别并校对」。"
                    }, page, usable))
                require(chapters.size <= ReadingImportParser.MAX_PARTS) { "PDF 文字分段过多，请拆分后导入" }
            }
            return ParsedReadingDocument(title, "PDF", chapters, buildList {
                if (paper) {
                    add("已按试卷保存：共 ${pdf.numberOfPages} 页，全部标记为待校对；原版可左右滑动查看。")
                    add("OCR 只生成页级文字草稿，需对照原图逐字校对；不自动识别题目结构、不生成题目与答案，也不承诺复刻字体/版式。")
                } else {
                    add("PDF 原版已保存在本机；文字按页提取，多栏、公式等复杂排版请对照原版。")
                    if (garbledPages > 0) add("$garbledPages 页文字疑似乱码，已保留 PDF 原版、未导入乱码正文；可在「查看 PDF 原版」里 OCR 识别并校对。")
                    if (blankPages > 0) add("$blankPages 页没有足够的可提取文字（扫描页/封面等）；可查看原版并 OCR 识别校对。")
                }
            })
        }
    }
}

/** 避免异常 PDF 的一页就生成无限长字符串。 */
internal class BoundedTextWriter(private val limit: Int) : Writer() {
    private val builder = StringBuilder()
    override fun write(chars: CharArray, offset: Int, length: Int) {
        require(builder.length + length <= limit) { "PDF 文字过多，请拆分后导入" }
        builder.append(chars, offset, length)
    }
    override fun flush() = Unit
    override fun close() = Unit
    override fun toString() = builder.toString()
}
