package cn.tonglu.app.data.english.intensive

import org.json.JSONObject
import java.util.Locale
import java.util.zip.GZIPInputStream

data class IntensiveSentenceRange(val sentence: IntensiveSentence, val start: Int, val end: Int)

/** Repeated sentences and supplementary characters retain their own UTF-16 span. */
fun intensiveSentenceRanges(paragraph: IntensiveParagraph): List<IntensiveSentenceRange> {
    var cursor = 0
    val ranges = paragraph.sentences.map { sentence ->
        val start = paragraph.text.indexOf(sentence.text, cursor)
        require(start >= cursor && paragraph.text.substring(cursor, start).isBlank())
        cursor = start + sentence.text.length
        IntensiveSentenceRange(sentence, start, cursor)
    }
    require(paragraph.text.substring(cursor).isBlank())
    return ranges
}

fun intensiveSentenceAt(paragraph: IntensiveParagraph, offset: Int): IntensiveSentence? =
    intensiveSentenceRanges(paragraph).firstOrNull { offset >= it.start && offset < it.end }?.sentence

fun intensiveTranslationKey(entry: IntensiveEntry, sentence: IntensiveSentence): String =
    "translation:" + entry.progressKey + ":" + sentence.id

const val IntensiveTranslationLimit = 4000

/** Only the matrix root's dependents belong in the main-clause summary. */
fun intensiveMainClause(sentence: IntensiveSentence): List<Pair<String,String>> {
    val root=sentence.tokens.firstOrNull { it.head==0 } ?: return emptyList()
    fun nounPhrase(head: IntensiveToken): List<IntensiveToken> {
        val ids=mutableSetOf(head.id)
        var changed=true
        while(changed) {
            changed=false
            sentence.tokens.forEach { token ->
                if(token.head in ids && token.relation in setOf("det","amod","compound","nummod","nmod:poss") && ids.add(token.id)) changed=true
            }
        }
        return sentence.tokens.filter { it.id in ids }
    }
    fun surface(tokens: List<IntensiveToken>): String {
        val sorted=tokens.distinctBy { it.start to it.end }.sortedBy { it.start }
        if(sorted.isEmpty()) return ""
        val parts=mutableListOf<String>()
        var start=sorted.first().start
        var end=sorted.first().end
        sorted.drop(1).forEach { token ->
            val gap=if(token.start>=end) sentence.text.substring(end,token.start) else ""
            if(token.start<=end || gap.isBlank() || gap.all { it=='-' || it=='\'' || it=='’' }) end=maxOf(end,token.end)
            else {parts+=sentence.text.substring(start,end);start=token.start;end=token.end}
        }
        parts+=sentence.text.substring(start,end)
        return parts.joinToString(" … ")
    }
    val direct=sentence.tokens.filter { it.head==root.id }
    val rows=mutableListOf<Pair<String,String>>()
    val subject=surface(direct.filter { it.role=="主语" }.flatMap { nounPhrase(it) })
    if(subject.isNotBlank()) rows+="主语" to subject
    val predicate=surface((if(root.role=="谓语") listOf(root) else emptyList())+direct.filter {
        it.relation.substringBefore(':') in setOf("aux","cop") || it.lemma.lowercase() in setOf("not","n't","n’t")
    })
    if(predicate.isNotBlank()) rows+="谓语" to predicate
    val obj=surface(direct.filter { it.relation in setOf("obj","iobj") }.flatMap { nounPhrase(it) })
    if(obj.isNotBlank()) rows+="宾语" to obj
    if(root.role=="表语") rows+="表语" to surface(nounPhrase(root))
    val complement=surface(direct.filter { it.relation=="xcomp" }.flatMap { nounPhrase(it) })
    if(complement.isNotBlank()) rows+="补语" to complement
    return rows
}

data class IntensiveGloss(val english: String,val chinese: String,val uk: String?,val us: String?,val source: String)

/** Existing .book assets contain gzip NDJSON; verify compressed bytes before decoding. */
fun decodeIntensiveGlossBook(bytes: ByteArray,sha256: String,expectedCount: Int,source: String): Map<String,IntensiveGloss> {
    require(IntensiveCodec.hash(bytes)==sha256)
    val result=linkedMapOf<String,IntensiveGloss>()
    var count=0
    GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.filter { it.isNotBlank() }.forEach { line ->
            val word=JSONObject(line)
            val english=word.getString("english")
            val chinese=word.getString("chinese")
            require(english.isNotBlank() && chinese.isNotBlank())
            count++
            require(count<=expectedCount)
            val uk=word.optString("ukPronunciation").takeUnless { it.isBlank() || it=="null" }
            val us=word.optString("pronunciation").takeUnless { it.isBlank() || it=="null" }
            result.putIfAbsent(english.lowercase(Locale.ROOT),IntensiveGloss(english,chinese,uk,us,source))
        }
    }
    require(count==expectedCount)
    return result
}
