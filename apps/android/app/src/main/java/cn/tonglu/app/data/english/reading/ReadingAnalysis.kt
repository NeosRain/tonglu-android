package cn.tonglu.app.data.english.reading

import com.google.gson.JsonParser
import java.security.MessageDigest

/** 原文定位使用 Kotlin/JavaScript 共用的 UTF-16 偏移；由阅读器提供完整句子，不按句点猜句界。 */
data class ReadingSentence(
    val articleId: String,
    val revision: String,
    val start: Int,
    val end: Int,
    val text: String,
    val hash: String,
) {
    companion object {
        fun select(articleId: String, revision: String, original: String, start: Int, end: Int): ReadingSentence {
            require(articleId.isNotBlank() && revision.isNotBlank()) { "缺少文章或内容版本" }
            require(start >= 0 && end > start && end <= original.length) { "选中文本范围无效" }
            fun boundary(offset: Int) = offset == 0 || offset == original.length ||
                !(original[offset - 1].isHighSurrogate() && original[offset].isLowSurrogate())
            require(boundary(start) && boundary(end)) { "不能截断字符" }
            val text = original.substring(start, end)
            require(text.isNotBlank() && text.length <= 4000) { "请选择不超过4000字符的句子" }
            return ReadingSentence(articleId, revision, start, end, text, readingDigest(text))
        }
    }
}

data class ReadingToken(val id: Int, val start: Int, val end: Int, val text: String)
data class SentenceComponent(val role: String, val tokenIds: List<Int>, val explanation: String)
data class SentenceAnalysis(val translation: String, val summary: String,
    val components: List<SentenceComponent>, val uncertainty: String?)

fun readingDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** 保留重复词的独立编号，也保留标点和缩写原貌；token 偏移相对于句子而非整篇 Markdown。 */
fun readingTokens(sentence: ReadingSentence): List<ReadingToken> =
    Regex("[\\p{L}\\p{M}]+(?:['’‑-][\\p{L}\\p{M}]+)*|\\p{N}+(?:[.,]\\p{N}+)*|[^\\s]")
        .findAll(sentence.text).mapIndexed { index, match ->
            ReadingToken(index, match.range.first, match.range.last + 1, match.value)
        }.toList()

/** 模型只能引用已提供的 token ID，不能自造偏移。坏结果必须报错，不能画错线后当作成功。 */
object ReadingAnalysisCodec {
    const val PROMPT_VERSION = "sentence-v2"
    val roles = setOf("主语", "谓语", "宾语", "定语", "状语", "补语", "从句")

    fun decode(raw: String, tokens: List<ReadingToken>): SentenceAnalysis {
        require(raw.length <= 60000) { "句子分析响应过大" }
        val root = JsonParser.parseString(raw)
        require(root.isJsonObject) { "句子分析必须是JSON对象" }
        val obj = root.asJsonObject
        fun text(name: String, max: Int): String {
            val value = obj.get(name)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "缺少文本字段：$name" }
            return value.asString.also { require(it.isNotBlank() && it.length <= max) { "文本字段无效：$name" } }
        }
        val translation = text("translation", 6000)
        val summary = text("summary", 4000)
        val list = obj.get("components")
        require(list != null && list.isJsonArray && list.asJsonArray.size() in 1..32) { "缺少句子成分" }
        val validIds = tokens.map { it.id }.toSet()
        val components = list.asJsonArray.map { element ->
            require(element.isJsonObject) { "句子成分格式错误" }
            val part = element.asJsonObject
            fun field(name: String): String {
                val v = part.get(name)
                require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) { "成分字段无效：$name" }
                return v.asString
            }
            val role = field("role")
            require(role in roles) { "未知句子成分：$role" }
            val ids = part.get("tokenIds")
            require(ids != null && ids.isJsonArray && ids.asJsonArray.size() in 1..tokens.size) { "成分词元范围无效" }
            val numbers = ids.asJsonArray.map { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                    value.asString.matches(Regex("0|[1-9][0-9]*"))) { "词元编号必须是非负整数" }
                value.asString.toInt()
            }
            require(numbers.distinct().size == numbers.size && numbers.all { it in validIds }) { "词元编号越界或重复" }
            require(numbers.zipWithNext().all { (a, b) -> b == a + 1 }) { "一个成分必须对应连续文本" }
            val explanation = field("explanation")
            require(explanation.isNotBlank() && explanation.length <= 3000) { "缺少成分解释" }
            SentenceComponent(role, numbers, explanation)
        }
        val uncertainty = obj.get("uncertainty")?.let {
            if (it.isJsonNull) null else {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length <= 3000) { "歧义说明格式错误" }
                it.asString.takeIf(String::isNotBlank)
            }
        }
        return SentenceAnalysis(translation, summary, components, uncertainty)
    }
}
