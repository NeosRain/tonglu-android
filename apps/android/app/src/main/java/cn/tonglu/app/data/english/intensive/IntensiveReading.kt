package cn.tonglu.app.data.english.intensive

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

data class IntensiveEntry(val id: String, val exam: String, val year: Int, val month: Int,
    val suite: Int, val kind: String, val words: Int, val sentences: Int,
    val sha256: String, val bytes: Int, val textSha256: String, val model: String, val qualityNotice: String = "") {
    val title: String get() = when(kind) {
        "cloze" -> "选词填空"; "passage1" -> "Section C · 第一篇"; else -> "Section C · 第二篇"
    }
    val subtitle: String get() = year.toString() + " 年 " + month + " 月 · " +
        (if (exam == "CET4") "四级" else "六级") + " · 第 " + suite + " 套"
    val progressKey: String get() = id + ":" + textSha256
}
data class IntensiveToken(val id: Int, val text: String, val form: String, val lemma: String, val start: Int,
    val end: Int, val head: Int, val relation: String, val role: String, val pos: String)
data class IntensiveSentence(val id: String, val text: String, val translation: String,
    val tokens: List<IntensiveToken>)
data class IntensiveParagraph(val text: String, val translation: String, val sentences: List<IntensiveSentence>)
data class IntensiveArticle(val entry: IntensiveEntry, val paragraphs: List<IntensiveParagraph>,
    val original: String, val answers: List<String>, val sourceUrl: String, val license: String,
    val notice: String)

/** All offsets use Android UTF-16. Refuse stale, truncated or misaligned packs. */
object IntensiveCodec {
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(text: String) = hash(text.toByteArray(Charsets.UTF_8))
    fun catalog(text: String): List<IntensiveEntry> {
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1)
        val rows = root.getJSONArray("articles")
        require(rows.length() in 0..400)
        val result = (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            val entry = IntensiveEntry(r.getString("id"), r.getString("exam"), r.getInt("year"),
                r.getInt("month"), r.getInt("suite"), r.getString("kind"), r.getInt("wordCount"),
                r.getInt("sentences"), r.getString("sha256"), r.getInt("bytes"),
                r.getString("textSha256"), r.getString("modelFingerprint"), r.optString("qualityNotice", ""))
            val expected = entry.exam.lowercase() + "-" + entry.year + "-" +
                entry.month.toString().padStart(2, '0') + "-" + entry.suite + "-" + entry.kind
            require(entry.id == expected && entry.exam in setOf("CET4", "CET6"))
            require(entry.year in 2021..2026 && entry.month in setOf(6, 12) && entry.suite in 1..3)
            require(entry.year != 2026 || entry.month == 6)
            require(entry.kind in setOf("cloze", "passage1", "passage2"))
            require(entry.words in 110..1000 && entry.sentences in 1..150 && entry.bytes in 1..8_000_000)
            require(listOf(entry.sha256, entry.textSha256, entry.model).all { it.matches(Regex("[a-f0-9]{64}")) })
            entry
        }
        require(result.map { it.id }.distinct().size == result.size)
        return result
    }
    fun article(entry: IntensiveEntry, bytes: ByteArray): IntensiveArticle {
        require(bytes.size == entry.bytes && hash(bytes) == entry.sha256) { "文章校验失败，请更新应用" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("id") == entry.id && root.getBoolean("complete"))
        require(root.getString("modelFingerprint") == entry.model)
        require(root.getString("grammarDevice").startsWith("cuda"))
        require(root.getString("translationDevice").startsWith("cuda"))
        val array = root.getJSONArray("paragraphs")
        require(array.length() in 1..80)
        val paragraphs = (0 until array.length()).map { p ->
            val value = array.getJSONObject(p)
            val text = value.getString("text")
            val rows = value.getJSONArray("sentences")
            require(rows.length() in 1..150)
            var cursor = 0
            val sentences = (0 until rows.length()).map { s ->
                val row = rows.getJSONObject(s)
                val sentence = row.getString("text")
                val offset = text.indexOf(sentence, cursor)
                require(sentence.isNotBlank() && offset >= cursor && text.substring(cursor, offset).isBlank())
                cursor = offset + sentence.length
                require(hash(sentence) == row.getString("sha256"))
                require(row.getString("id") == "p" + (p + 1) + "-s" + (s + 1))
                val raw = row.getJSONArray("tokens")
                require(raw.length() in 1..300)
                val tokens = (0 until raw.length()).map { t ->
                    val token = raw.getJSONObject(t)
                    val start = token.getInt("start"); val end = token.getInt("end")
                    require(start >= 0 && end > start && end <= sentence.length)
                    require(sentence.substring(start, end) == token.getString("text"))
                    require(token.getInt("id") == t + 1)
                    val head = token.getInt("head")
                    require(head in 0..raw.length() && head != t + 1)
                    IntensiveToken(t + 1, token.getString("text"), token.optString("form", token.getString("text")), token.getString("lemma"), start, end,
                        head, token.getString("relation"), token.getString("role"), token.getString("posLabel"))
                }
                require(tokens.count { it.head == 0 } == 1)
                for (token in tokens) {
                    val seen = mutableSetOf<Int>(); var head = token.id
                    while (head != 0) { require(seen.add(head)); head = tokens[head - 1].head }
                }
                val translated = row.getString("translation"); require(translated.isNotBlank())
                IntensiveSentence(row.getString("id"), sentence, translated, tokens)
            }
            require(text.substring(cursor).isBlank())
            val translated = value.getString("translation"); require(translated.isNotBlank())
            IntensiveParagraph(text, translated, sentences)
        }
        require(paragraphs.sumOf { it.sentences.size } == entry.sentences)
        require(hash(paragraphs.joinToString("\n\n") { it.text }) == entry.textSha256)
        val fills = root.getJSONArray("fills")
        val answers = (0 until fills.length()).map { i ->
            val fill = fills.getJSONObject(i)
            require(fill.getInt("number") == 26 + i)
            fill.getInt("number").toString() + ". " + fill.getString("letter") + " · " + fill.getString("word")
        }
        require(if (entry.kind == "cloze") answers.size == 10 else answers.isEmpty())
        val source = root.getString("sourceUrl")
        require(java.net.URI(source).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null })
        val analysisNotice = root.getString("analysisNotice")
        val qualityNotice = root.optString("qualityNotice", "")
        val notice = analysisNotice + if (qualityNotice.isNotBlank() && !analysisNotice.contains(qualityNotice)) qualityNotice else ""
        return IntensiveArticle(entry, paragraphs, root.getString("original"), answers, source,
            root.getString("license"), notice)
    }
}

class IntensiveRepository(context: Context) {
    private val assets = context.applicationContext.assets
    suspend fun catalog(): List<IntensiveEntry> = withContext(Dispatchers.IO) {
        assets.open("intensive/catalog.json").bufferedReader().use { IntensiveCodec.catalog(it.readText()) }
    }
    suspend fun article(entry: IntensiveEntry): IntensiveArticle = withContext(Dispatchers.IO) {
        // AAPT removes .gz and expands source assets during packaging.
        assets.open("intensive/articles/" + entry.id + ".json").use {
            IntensiveCodec.article(entry, it.readBytes())
        }
    }
}
