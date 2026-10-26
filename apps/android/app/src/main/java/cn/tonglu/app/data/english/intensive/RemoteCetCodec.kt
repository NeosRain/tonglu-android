package cn.tonglu.app.data.english.intensive

import org.json.JSONObject
import java.net.URI
import java.text.Normalizer

/** This descriptor contains hashes, offsets and grammatical labels, never source prose. */
data class RemoteCetSpec(val value: JSONObject) {
    val id: String get() = value.getString("id")
    val sourceUrl: String get() = value.getString("sourceUrl")
    val sourceSha256: String get() = value.getString("sourceSha256")
    val format: String get() = value.getString("sourceFormat")
    fun entry() = IntensiveEntry(id, value.getString("exam"), value.getInt("year"), value.getInt("month"),
        value.getInt("suite"), value.getString("kind"), value.getInt("wordCount"), value.getInt("sentences"),
        value.getString("bodySha256"), value.toString().toByteArray().size, value.getString("textSha256"),
        value.getString("modelFingerprint"), qualityNotice = if (format == "markdown") "文字转录来源，阅读时请对照原卷核对。" else "", remote = this)
}

object RemoteCetCodec {
    private val sha = Regex("[a-f0-9]{64}")
    fun allowedSource(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in setOf("raw.githubusercontent.com", "media.githubusercontent.com") &&
            uri.userInfo == null && uri.port == -1 && uri.rawQuery == null && uri.fragment == null &&
            Regex("/(?:media/)?[^/]+/[^/]+/[a-f0-9]{40}/.+").matches(uri.rawPath)
    }.getOrDefault(false)

    fun catalog(text: String): List<IntensiveEntry> {
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1 && root.getBoolean("metadataOnly"))
        val rows = root.getJSONArray("articles")
        require(rows.length() in 1..400)
        return (0 until rows.length()).map { index ->
            val v = rows.getJSONObject(index)
            val spec = RemoteCetSpec(v)
            require(allowedSource(spec.sourceUrl) && sha.matches(spec.sourceSha256))
            require(spec.format in setOf("pdf", "markdown"))
            require(v.getString("grammarDevice").startsWith("cuda"))
            require(v.getInt("originalLength") in 100..20000 && v.getInt("sentences") in 1..150)
            for (key in listOf("originalSha256", "prefixSha256", "bodySha256", "textSha256")) require(sha.matches(v.getString(key)))
            require(v.getString("exam") in setOf("CET4", "CET6") && v.getInt("month") in setOf(6, 12))
            require(v.getInt("year") in 2000..2099 && v.getInt("suite") in 1..3)
            require(v.getString("kind") in setOf("cloze", "passage1", "passage2"))
            val expected = v.getString("exam").lowercase() + "-" + v.getInt("year") + "-" +
                v.getInt("month").toString().padStart(2, '0') + "-" + v.getInt("suite") + "-" + v.getString("kind")
            require(spec.id == expected)
            spec.entry()
        }.also { require(it.map { row -> row.id }.distinct().size == it.size) }
    }

    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private fun hash(value: String) = IntensiveCodec.hash(value.toByteArray(Charsets.UTF_8))

    private class Indexed(val text: String) {
        val positions = text.indices.filter { text[it].lowercaseChar() in 'a'..'z' || text[it] in '0'..'9' }
        val normalized = positions.joinToString("") { text[it].lowercaseChar().toString() }
        fun piece(start: Int, end: Int): String {
            require(start in positions.indices && end in (start + 1)..positions.size)
            return text.substring(if (start == 0) 0 else positions[start], if (end == positions.size) text.length else positions[end]).trim()
        }
    }

    /** Match complete normalized source fingerprints before applying any GPU offset. */
    fun article(entry: IntensiveEntry, pdfText: String): IntensiveArticle {
        val spec = requireNotNull(entry.remote)
        val v = spec.value
        val cleaned = pdfText.replace(Regex("202[1-6]\\s*年\\s*\\d{1,2}\\s*月\\s*英语[四六]级真题第\\s*\\d\\s*套\\s*第\\s*\\d+\\s*页[，,]?\\s*共\\s*\\d+\\s*页"), "")
        val flattened = Normalizer.normalize(cleaned, Normalizer.Form.NFKC)
            .replace(Regex("-\\s*\n\\s*"), "").replace(Regex("\\s+"), " ").trim()
        val full = Indexed(flattened)
        val length = v.getInt("originalLength")
        val begin = (0..(full.normalized.length - length).coerceAtLeast(-1)).firstOrNull { index ->
            hash(full.normalized.substring(index, index + 48)) == v.getString("prefixSha256") &&
                hash(full.normalized.substring(index, index + length)) == v.getString("originalSha256")
        } ?: throw IllegalArgumentException("原卷文字与目录指纹不一致，请重试或核对原卷")
        val original = full.piece(begin, begin + length)
        var body = original
        val fills = v.getJSONArray("fills")
        val answers = mutableListOf<String>()
        for (index in 0 until fills.length()) {
            val fill = fills.getJSONObject(index)
            val number = fill.getInt("number")
            val word = fill.getString("word")
            require(number == 26 + index && word.matches(Regex("[A-Za-z’'-]+")))
            val pattern = Regex("_+\\s*${number}\\s*_+|\\[\\s*${number}\\s*\\]")
            body = if (pattern.containsMatchIn(body)) pattern.replaceFirst(body, word)
                else Regex("(?<![0-9])${number}(?![0-9])").replaceFirst(body, word)
            answers += "$number. ${fill.getString("letter")} · $word"
        }
        val indexed = Indexed(body)
        require(hash(indexed.normalized) == v.getString("bodySha256")) { "选词或正文匹配失败，未套用旧分析" }
        val paragraphs = v.getJSONArray("paragraphs")
        var offset = 0
        val result = (0 until paragraphs.length()).map { p ->
            val paragraph = paragraphs.getJSONObject(p)
            val rows = paragraph.getJSONArray("sentences")
            val before = offset
            val sentences = (0 until rows.length()).map { s ->
                val row = rows.getJSONObject(s)
                val count = row.getInt("length")
                val sentence = indexed.piece(offset, offset + count)
                offset += count
                val local = Indexed(sentence)
                require(local.positions.size == count)
                val raw = row.getJSONArray("tokens")
                val tokens = (0 until raw.length()).map { t ->
                    val token = raw.getJSONArray(t)
                    val a = token.getInt(1); val b = token.getInt(2)
                    require(a >= 0 && b > a && b <= count && token.getInt(0) == t + 1)
                    val start = local.positions[a]; val end = local.positions[b - 1] + 1
                    val word = sentence.substring(start, end)
                    val head = token.getInt(3)
                    require(head in 0..raw.length() && head != t + 1)
                    IntensiveToken(t + 1, word, word, word.lowercase(), start, end, head,
                        token.getString(6), token.getString(4), token.getString(5))
                }
                require(tokens.count { it.head == 0 } == 1)
                for (token in tokens) {
                    val visited = mutableSetOf<Int>()
                    var head = token.id
                    while (head != 0) { require(visited.add(head)) { "句法标注存在循环，未显示旧颜色" }; head = tokens[head - 1].head }
                }
                IntensiveSentence(row.getString("id"), sentence, "", tokens)
            }
            require(offset - before == paragraph.getInt("length"))
            IntensiveParagraph(sentences.joinToString(" ") { it.text }, "", sentences)
        }
        require(offset == indexed.normalized.length && result.sumOf { it.sentences.size } == entry.sentences)
        return IntensiveArticle(entry, result, original, answers, spec.sourceUrl, v.getString("rightsNotice"),
            "原卷首次联网读取并保存在本机；语法使用 Stanza EWT 的 GPU 预分析位置，全文指纹一致后才映射。机器分析需结合原卷核对。")
    }
}
