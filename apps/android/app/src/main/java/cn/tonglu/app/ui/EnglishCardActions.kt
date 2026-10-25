package cn.tonglu.app.ui

/** 翻卡评分不依赖是否展开释义；保存中禁止重复提交。 */
internal fun canGradeCard(saving: Boolean): Boolean = !saving

internal data class ExampleToken(val text: String, val spoken: Boolean)

/** 保留空白与标点，缩写和连字符单词作为一个朗读单元。 */
internal fun exampleTokens(text: String): List<ExampleToken> {
    val pattern = Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*")
    val tokens = mutableListOf<ExampleToken>()
    var offset = 0
    pattern.findAll(text).forEach { match ->
        if (match.range.first > offset) tokens += ExampleToken(text.substring(offset, match.range.first), false)
        tokens += ExampleToken(match.value, true)
        offset = match.range.last + 1
    }
    if (offset < text.length) tokens += ExampleToken(text.substring(offset), false)
    return tokens
}
