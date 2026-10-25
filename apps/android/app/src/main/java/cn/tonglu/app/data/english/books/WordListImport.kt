package cn.tonglu.app.data.english.books

/**
 * EF11 词单导入解析（纯函数，可单测口径）。
 *
 * **如实支持的格式**（写入界面说明，不冒称支持更多）：
 *  1. TSV/多列：`单词<TAB>释义[<TAB>原型]` —— 释义列缺失的行标「需补释义」；
 *  2. 分隔符行：`单词 - 释义`、`单词 — 释义`、`单词：释义`、`单词: 释义`；
 *  3. 纯单词行（一行一词）—— 标「需补释义」。
 *
 * 每行保留 lineNo + rawLine，预览里用户改的是 surface/meaning/lemma 的**工作副本**，
 * 原始值永远可回看；确认前不写任何正式存储。
 */
data class ImportRow(
    val lineNo: Int,
    val rawLine: String,
    val surface: String,
    val meaning: String,
    val lemma: String = surface,
) {
    val key: String get() = PersonalBookWord.normalize(lemma)
    val needsMeaning: Boolean get() = meaning.isBlank()
    /** 稳定键冲突在批次内标出（首出现为原，后续为重复）；与既有词书的重复在确认阶段由 store.add 返回。 */
    var duplicateOfLine: Int? = null
        internal set
}

data class ImportParseResult(
    val rows: List<ImportRow>,
    val skipped: List<Pair<Int, String>>, // 行号 → 跳过原因
) {
    val duplicateCount: Int get() = rows.count { it.duplicateOfLine != null }
    val needsMeaningCount: Int get() = rows.count { it.needsMeaning }
}

object WordListImport {
    private val separators = listOf("\t", " - ", " — ", "：", ":")

    fun parse(text: String): ImportParseResult {
        val rows = mutableListOf<ImportRow>()
        val skipped = mutableListOf<Pair<Int, String>>()
        text.split('\n').forEachIndexed { index, rawLine0 ->
            val lineNo = index + 1
            val rawLine = rawLine0.trimEnd('\r')
            val line = rawLine0.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("#") || line.startsWith("//")) {
                skipped += lineNo to "注释行"
                return@forEachIndexed
            }
            // BOM / 零宽字符按内容处理，不整行丢弃
            val cleaned = line.trimStart('\uFEFF', '\u200b')
            val parts = separators.firstNotNullOfOrNull { sep ->
                cleaned.split(sep, limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
            }
            val surface: String
            val meaning: String
            val lemma: String
            when {
                parts != null -> {
                    surface = parts[0].trim()
                    val rest = parts[1].trim()
                    // 第三列按 TAB 再切：单词 \t 释义 \t 原型
                    val restParts = if (parts[0].contains('\t')) {
                        // TSV：第一刀已经把词切出来；rest 里若还有 TAB，取第一段为释义、第二段为原型
                        rest.split('\t', limit = 2)
                    } else null
                    meaning = (restParts?.get(0) ?: rest).trim()
                    lemma = restParts?.getOrNull(1)?.trim().takeUnless { it.isNullOrBlank() } ?: surface
                }
                isSingleWord(cleaned) -> { surface = cleaned; meaning = ""; lemma = cleaned }
                else -> {
                    skipped += lineNo to "无法识别的行（既没有可识别分隔符，也不像单个词）"
                    return@forEachIndexed
                }
            }
            if (!isPlausibleWord(surface)) {
                skipped += lineNo to "「${surface.take(24)}」不像英文词条（含数字/过长/空词）"
                return@forEachIndexed
            }
            rows += ImportRow(lineNo, rawLine, surface, meaning, lemma)
        }
        // 批次内去重标记：同名（含词形归一）第二次出现指向第一次的行号
        val firstSeen = HashMap<String, Int>()
        rows.forEach { row ->
            val first = firstSeen.putIfAbsent(row.key, row.lineNo)
            if (first != null) row.duplicateOfLine = first
        }
        return ImportParseResult(rows, skipped)
    }

    /** 单个词行：1–3 个不含空白分隔的拉丁字母词（允许 ' 与 - 连接），不含数字。 */
    private fun isSingleWord(line: String): Boolean =
        line.length <= 48 && !line.any { it.isDigit() } &&
            line.split(Regex("\\s+")).all { it.matches(Regex("[A-Za-z][A-Za-z'’\\-]*")) }

    /** 词条表面值合理性：非空、≤160、不全是数字/符号、不含控制字符。 */
    private fun isPlausibleWord(word: String): Boolean =
        word.isNotBlank() && word.length <= 160 && word.none { it.isDigit() || it.isISOControl() } &&
            word.any { it.isLetter() }
}
