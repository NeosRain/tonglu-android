package cn.tonglu.app.data.readingimport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF 文字层质量判据（纯 JVM，可单测）。
 *
 * 用户提供的四级试题册就是典型：**页面视觉清楚、直接取文是乱码短文本**。
 * 乱码绝不能当正文存进阅读器（那等于把用户导入的文章变成一串不可读字符），
 * 也不能因为「取到了字符串」就宣称可精读 —— 这里把「不可用」如实判出来，
 * 交给界面提示「可 OCR 识别并校对」。
 */
object ReadingTextQuality {
    /** 一页正文少于这个字数就不算「可读正文」（页眉/页码/零星字符）。 */
    const val MIN_USEFUL_PAGE_CHARS = 40

    private val asciiPunctuation = " \t\n.,;:!?'\"()[]{}-–—%&/@#*+=|~_$^<>\\".toSet()

    /** 文本层是否可当正文用：字数够 + 不是乱码。 */
    fun isUsablePageText(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length >= MIN_USEFUL_PAGE_CHARS && !looksGarbled(trimmed)
    }

    /**
     * 乱码判据（宁可判重，交给用户 OCR 校对；不把疑似乱码静默存成正文）：
     * - 出现控制符 / 私用区字符 / 未分配字符 / 替换符（典型坏 ToUnicode 映射）；
     * - 通篇是「可打印但非拉丁/汉字」的字母（错映射常见的希腊/西里尔/扩展拉丁杂串）；
     * - 一页英文 40+ 字母却几乎没有空格（正常英文页必有词间空格）。
     */
    fun looksGarbled(text: String): Boolean {
        val sample = text.replace('\u00A0', ' ').trim()
        if (sample.isEmpty()) return true
        val total = sample.length
        var bad = 0
        var latin = 0
        var han = 0
        var otherLetter = 0
        var spaces = 0
        for (ch in sample) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
                ch.isDigit() -> Unit
                ch == ' ' -> spaces++
                ch in asciiPunctuation -> Unit
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> han++
                ch == '\uFFFD' || Character.isISOControl(ch) -> bad++
                Character.getType(ch.code) == Character.PRIVATE_USE.toInt() -> bad++
                Character.getType(ch.code) == Character.UNASSIGNED.toInt() -> bad++
                ch.isLetter() -> otherLetter++
            }
        }
        // 任一控制符/私用区/未分配字符占比达到 1% 就按乱码处理（正常文本里这些应为 0）。
        if (bad > 0 && bad * 100 / total >= 1) return true
        val letters = latin + han + otherLetter
        if (letters == 0) return true
        if (otherLetter > letters / 2 && latin < letters / 5) return true
        if (latin >= 40 && spaces <= 1) return true
        return false
    }

    /**
     * 把 OCR 的逐行输出整理成可读段落：行内多余空格压掉、英文断行拼回句子、
     * 空行作为段落分隔。**只做排版整理，不改字** —— 纠错由用户在校对框里做。
     */
    fun tidyOcrText(raw: String): String {
        val normalized = raw.replace('\u00A0', ' ').replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                paragraphs += current.toString().trim()
                current.clear()
            }
        }
        for (rawLine in normalized.split('\n')) {
            val line = rawLine.replace(Regex("[ \\t]{2,}"), " ").trim()
            if (line.isEmpty()) { flush(); continue }
            if (current.isNotEmpty()) current.append(' ')
            current.append(line)
        }
        flush()
        return paragraphs.joinToString("\n\n")
            .replace(Regex("\\s+([.,;:!?])"), "$1")
            .replace(Regex("([.,;:!?])([A-Za-z])"), "$1 $2")
            .trim()
    }
}

/**
 * PDF 页面 → 本机 OCR 文字（ML Kit on-device；拉丁模型 + 中文模型，图片不上传）。
 *
 * 任务B 追修（2026-10-18）：用户实测第 1 页中文封面识别为空、第 3 页英文大量串行/错字。
 * 这里不是换一个更大的 maxWidth 了事，而是：
 * - 中英文两个模型都跑，按行包围盒合并（中文封面用中文模型，英文用拉丁模型）；
 * - 用行包围盒还原版面：过滤页眉/页脚/水印/二维码，多栏行按 x 排序拼回；
 * - 只做**识别草稿**，保存前必须由用户对照原图校对；识别失败/为空如实显示。
 */
/** OCR 的一行：带像素包围盒，供版面还原用（多栏选项靠位置合并，不靠猜测）。 */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val lineHeight: Int get() = (bottom - top).coerceAtLeast(1)
}

/** 一整页的 OCR 结果：位图尺寸 + 行；`layoutText` 负责过滤页眉/水印并还原段落。 */
data class OcrPage(val width: Int, val height: Int, val lines: List<OcrLine>)

/** 一次本机识别的结果与耗时/行数：界面显示进度与诊断，报告里留真实数字。 */
data class OcrScan(
    val page: OcrPage,
    val elapsedMs: Long,
    val latinLines: Int,
    val chineseLines: Int,
    val mergedLines: Int,
    /** 原始候选（逐行文本），报告/诊断用；不覆盖合并结果。 */
    val latinRawText: String = "",
    val chineseRawText: String = "",
    /** 单模型失败/降级说明；双模型失败会直接抛错，不静默返回空。 */
    val warnings: List<String> = emptyList(),
)

object PdfPageOcr {
    /** OCR 专用渲染宽度：比预览宽（1800）略高，兼顾小字号识别与内存。 */
    private const val OCR_RENDER_WIDTH = 2200

    private val latinRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 渲染指定页为位图（只为 OCR / 校对预览用，不落盘）。 */
    fun renderPage(context: Context, owner: String, sourceFile: String, page: Int, maxWidthPx: Int = 1800): Bitmap {
        val file = ReadingImportFiles.pdf(context, owner, sourceFile)
        require(file.isFile) { "PDF 副本不存在，请重新导入" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(page in 1..renderer.pageCount) { "PDF 页码超出范围" }
                renderer.openPage(page - 1).use { pdfPage ->
                    val scale = min(maxWidthPx.toFloat() / pdfPage.width, 3200f / pdfPage.height)
                    val bitmap = Bitmap.createBitmap(
                        (pdfPage.width * scale).roundToInt().coerceAtLeast(1),
                        (pdfPage.height * scale).roundToInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                }
            }
        }
    }

    private suspend fun linesFrom(recognizer: TextRecognizer, bitmap: Bitmap): List<OcrLine> =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val lines = ArrayList<OcrLine>()
                    for (block in text.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            val value = line.text.trim()
                            if (value.isNotEmpty()) {
                                lines += OcrLine(value, box.left, box.top, box.right, box.bottom)
                            }
                        }
                    }
                    continuation.resume(lines)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    private fun cjkCount(text: String): Int = text.count { ch ->
        Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
    }

    /**
     * 可读性评分：拉丁/汉字/数字都算可读，符号与「非拉丁非汉字的字母」（错映射常见）扣分。
     * 用来在两个模型的重叠结果里择优，而不是拿「拉丁字母 < 4」这种拍脑袋阈值。
     */
    private fun textQuality(text: String): Int {
        var readable = 0
        var noise = 0
        for (ch in text) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> readable++
                ch.isDigit() -> readable++
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> readable++
                ch.isWhitespace() -> Unit
                ch.isLetter() -> noise++
                else -> noise++
            }
        }
        return readable - noise * 2
    }

    /** 两个模型都识别到同一区域时选更可读的一份；中文封面优先保留中文，英文正文优先拉丁。 */
    private fun pickBetter(latin: OcrLine, chinese: OcrLine): OcrLine {
        val latinScore = textQuality(latin.text)
        val chineseScore = textQuality(chinese.text)
        return when {
            chineseScore > latinScore -> chinese
            latinScore > chineseScore -> latin
            cjkCount(chinese.text) > cjkCount(latin.text) -> chinese
            else -> latin
        }
    }

    private fun overlaps(a: OcrLine, b: OcrLine): Boolean {
        val x = min(a.right, b.right) - max(a.left, b.left)
        val y = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (x <= 0 || y <= 0) return false
        val minWidth = min(a.right - a.left, b.right - b.left).coerceAtLeast(1)
        val minHeight = min(a.bottom - a.top, b.bottom - b.top).coerceAtLeast(1)
        return x >= minWidth * 35 / 100 && y >= minHeight * 35 / 100
    }

    /**
     * 合并中英文两套识别结果（同一页）：重叠区域默认保留拉丁结果（英文更准）；
     * 但若中文行含汉字、而拉丁结果在该区域几乎没有拉丁字母（典型中文封面/标题被拉丁模型识别成噪声），
     * 则用中文行替换；不重叠的中文行（中文说明/页眉）直接补进来。
     */
    private fun mergeLines(latin: List<OcrLine>, chinese: List<OcrLine>): List<OcrLine> {
        val result = latin.toMutableList()
        for (line in chinese) {
            val match = result.firstOrNull { overlaps(it, line) }
            if (match == null) {
                result += line
                continue
            }
            val index = result.indexOf(match)
            if (index >= 0) result[index] = pickBetter(match, line)
        }
        // 同一位置完全重复的行去掉（两个模型可能都返回相同英文行）。
        return result.distinctBy { "${it.left / 8}:${it.top / 8}:${it.text}" }
    }

    /**
     * 渲染 + 中英文双模型结构化识别一页（含行包围盒与耗时）。
     * 两套模型并行跑；单套失败不拖垮整页（保留另一套结果，并在行数里如实反映）。
     */
    /** 对已渲染的位图跑双模型 + 合并（调用方负责预览位图生命周期）。 */
    suspend fun recognizeScan(bitmap: Bitmap): OcrScan = withContext(Dispatchers.Default) {
        scanBitmap(bitmap)
    }

    /** 渲染 + 双模型识别；识别完即回收位图（只返回文字/行，不返回位图）。 */
    suspend fun recognizeScan(context: Context, owner: String, sourceFile: String, page: Int): OcrScan =
        withContext(Dispatchers.IO) {
            val bitmap = renderPage(context, owner, sourceFile, page, maxWidthPx = OCR_RENDER_WIDTH)
            var completed = false
            try {
                val result = recognizeScan(bitmap)
                completed = true
                result
            } finally {
                // ⚠️ 用户取消时 ML Kit 仍可能持有位图：不能在未完成时 recycle（会 use-after-free），
                // 交给 GC 回收；只有正常完成才立即释放，避免大图常驻。
                if (completed) bitmap.recycle()
            }
        }

    /** 单模型尝试：失败返回 (空结果, 可见说明)；取消直接抛出，不吞 CancellationException。 */
    private suspend fun attempt(
        recognizer: TextRecognizer,
        name: String,
        bitmap: Bitmap,
    ): Pair<List<OcrLine>, String?> =
        try {
            linesFrom(recognizer, bitmap) to null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            emptyList<OcrLine>() to "$name 识别失败：${cause.message ?: cause.javaClass.simpleName}"
        }

    private suspend fun scanBitmap(bitmap: Bitmap): OcrScan = coroutineScope {
        val started = SystemClock.elapsedRealtime()
        val latinAttempt = async { attempt(latinRecognizer, "拉丁模型", bitmap) }
        val chineseAttempt = async { attempt(chineseRecognizer, "中文模型", bitmap) }
        val (latin, latinError) = latinAttempt.await()
        val (chinese, chineseError) = chineseAttempt.await()
        val warnings = listOfNotNull(latinError, chineseError)
        // 双模型都失败才抛错（界面必须看到真实原因）；单模型失败保留另一套结果并显示降级说明。
        if (latinError != null && chineseError != null) {
            throw IllegalStateException(warnings.joinToString("；") + "；本页未产生任何识别结果")
        }
        val merged = mergeLines(latin, chinese)
        OcrScan(
            page = OcrPage(bitmap.width, bitmap.height, merged),
            elapsedMs = SystemClock.elapsedRealtime() - started,
            latinLines = latin.size,
            chineseLines = chinese.size,
            mergedLines = merged.size,
            latinRawText = latin.joinToString("\n") { it.text },
            chineseRawText = chinese.joinToString("\n") { it.text },
            warnings = warnings,
        )
    }

    /**
     * 版面还原：过滤页眉/页脚/水印/二维码噪声，按行包围盒聚成「视觉行」，
     * 多栏（如 A) … C) … 同排）按 x 排序拼回一行，再由上层按题号/选项切结构。
     *
     * 只做**排版整理，不改字**：错字仍由用户对照原图校对。
     */
    fun layoutText(page: OcrPage): String {
        if (page.lines.isEmpty() || page.width <= 0 || page.height <= 0) return ""
        val usable = page.lines.filter { line ->
            val text = line.text.trim()
            if (text.isEmpty()) return@filter false
            // 纯符号装饰（如封面 ☆☆☆…）没有可读内容，去掉；含字母/数字的行一律保留。
            if (text.none { it.isLetterOrDigit() }) return@filter false
            // 只去非正文覆盖层：二维码旁注/斜向水印；**不按「听力/真题/考试委员会」整行删**，
            // 那些词出现在真实考生说明与署名里（root 14:19 门槛）。
            if (text.length < 30 && Regex("(扫码听听力|学丞|晓艳|二维码)").containsMatchIn(text)) {
                return@filter false
            }
            if (Regex("^[-—\\s]*\\d{1,3}[-—\\s]*$").matches(text)) return@filter false
            val letters = text.count { it.isLetterOrDigit() }
            // 二维码/条码被误识别成带 * # 的短噪声行（正常英文行不会以符号为主）；长行不碰。
            if (text.length < 40 && (text.contains('*') || text.contains('#')) && letters <= text.length / 2) {
                return@filter false
            }
            true
        }.sortedWith(compareBy({ it.top }, { it.left }))
        if (usable.isEmpty()) return ""
        data class Row(val top: Int, val bottom: Int, val lines: MutableList<OcrLine>)
        val rows = ArrayList<Row>()
        for (line in usable) {
            val row = rows.lastOrNull()
            if (row != null && line.centerY in (row.top - line.lineHeight * 6 / 10)..(row.bottom + line.lineHeight * 6 / 10)) {
                row.lines += line
                // Row 是 data class 但字段 val；用可变的 top/bottom 需要普通类，这里用局部重新赋值会复杂；
                // 直接把行放进去、行范围保持首行范围即可（同一视觉行高度差很小）。
            } else {
                rows += Row(line.top, line.bottom, mutableListOf(line))
            }
        }
        val textRows = rows.map { row ->
            row.lines.sortedBy { it.left }.joinToString("  ") { it.text }
        }.map { it.replace(Regex("[ \\t]{2,}"), " ").trim() }.filter { it.isNotEmpty() }
        // 行内标点后补空格；去掉明显的「词-词」多余空格（只做排版，不改字母）。
        return textRows.joinToString("\n")
            .replace(Regex("([.,;:!?])([A-Za-z])"), "$1 $2")
            .replace(Regex("\\(\\s+"), "(")
            .replace(Regex("\\s+\\)"), ")")
            .trim()
    }

    /** 渲染 + 识别一页，返回版面整理后的文字；调用方负责把结果交给用户校对。 */
    suspend fun recognizePage(context: Context, owner: String, sourceFile: String, page: Int): String =
        layoutText(recognizeScan(context, owner, sourceFile, page).page)
}
