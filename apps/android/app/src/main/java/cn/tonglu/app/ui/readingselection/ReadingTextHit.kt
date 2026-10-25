package cn.tonglu.app.ui.readingselection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import java.text.BreakIterator
import java.util.Locale

/** 原文 UTF-16 范围；单词偏移相对于句子，重复词不依赖字符串首匹配。 */
data class ReadingTextHit(val start: Int, val end: Int, val wordOffset: Int?)

/** 首轮只精确处理普通正文；富文本继续交给 Markdown 渲染器，不能把源码位置冒充可见文字位置。 */
fun supportsPreciseReading(text: String): Boolean = text.isNotBlank() &&
    text.none { it in "*_`[]<>\\|#!" } && !text.contains("://")

private val abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e")

/** 使用英文句界规则并合并称谓缩写；结果始终是 original 的子串，不改空格、标点或代理对。 */
fun readingTextHit(original: String, blockStart: Int, blockEnd: Int, offset: Int, word: Boolean,
    plainText: Boolean = false): ReadingTextHit? {
    require(blockStart >= 0 && blockEnd > blockStart && blockEnd <= original.length) { "正文范围无效" }
    val text = original.substring(blockStart, blockEnd)
    if ((!plainText && !supportsPreciseReading(text)) || offset !in text.indices || text[offset].isWhitespace()) return null
    val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH).apply { setText(text) }
    val boundaries = mutableListOf(0)
    var next = iterator.next()
    while (next != BreakIterator.DONE) {
        val prefix = text.substring(0, next).trimEnd().trimEnd('"', '”', '\'')
        val last = Regex("([A-Za-z.]+)\\.$").find(prefix)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        if (next == text.length || last !in abbreviations) boundaries += next
        next = iterator.next()
    }
    val span = boundaries.zipWithNext().firstOrNull { (start, end) -> offset in start until end } ?: return null
    var start = span.first
    var end = span.second
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    if (offset !in start until end || end - start > 4000) return null
    val token = if (word) Regex("[\\p{L}\\p{M}]+(?:['’‑-][\\p{L}\\p{M}]+)*")
        .findAll(text.substring(start, end)).firstOrNull { offset - start in it.range } ?: return null else null
    return ReadingTextHit(blockStart + start, blockStart + end, token?.range?.first)
}

/**
 * 手指落点 → **真正被按住的字符偏移**；三格都框不住（点在行尾空白、段落内边距或字间空隙）返回 null。
 *
 * 🔴 为什么不能直接拿 `getOffsetForPosition` 当判据（BUGS S118，2026-10-15 设备实测）：
 * 它返回的是「离落点最近的**游标**位置」，**不是包含落点的字符** —— 落点靠近字与字的分界时，
 * 它常常指向右边那个字符。实测同一段落里 x=60 明明落在 'u' 的字形框 [45,71] 内，它却返回 3='d'
 * （框 [71,98]），于是 `getBoundingBox(offset).contains(position)` 为 false，一次正常的点词被
 * **静默吞掉**：用户看到的是「同一段里有的地方点了没反应」，连提示都没有。
 * 修法：向左右各看一格，取真的框住落点的那个字符；都框不住才算点在空白上（保持「点空白不动作」）。
 */
fun TextLayoutResult.characterAt(position: Offset, textLength: Int): Int? {
    val nearest = getOffsetForPosition(position)
    return listOf(nearest, nearest - 1, nearest + 1).firstOrNull { offset ->
        offset in 0 until textLength && getBoundingBox(offset).contains(position)
    }
}
