package cn.tonglu.app.ui.readingselection

import cn.tonglu.app.data.LearningArticle

/** 标题或所在目录不能代表正文语言；中文技术文章含几个英文词也不能进入英语续读。 */
internal fun isEnglishReadingArticle(article: LearningArticle): Boolean {
    if (article.kind == "LINK" || article.body.isBlank()) return false
    val sample = article.body.take(12000)
        .replace(Regex("```[\\s\\S]*?```"), "")
        .replace(Regex("https?://\\S+"), "")
    val latin = sample.count { it in 'a'..'z' || it in 'A'..'Z' }
    val han = sample.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    return Regex("[A-Za-z]{2,}").findAll(sample).take(8).count() >= 8 &&
        latin.toFloat() / (latin + han).coerceAtLeast(1) >= .85f
}
