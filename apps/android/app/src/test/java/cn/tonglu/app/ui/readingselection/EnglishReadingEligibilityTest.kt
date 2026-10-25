package cn.tonglu.app.ui.readingselection

import cn.tonglu.app.data.LearningArticle
import org.junit.Assert.*
import org.junit.Test

class EnglishReadingEligibilityTest {
    private fun article(body: String, kind: String = "ARTICLE", title: String = "英语学习") = LearningArticle(
        "id", "english", title, "", "", kind, "", "ORIGINAL", "", "", "", "v1", "", false, 0, body)
    private val prose = "Reading opens a window to another world. We learn something new every day."

    @Test fun englishProseAllowsDirectLookup() = assertTrue(isEnglishReadingArticle(article(prose)))
    @Test fun languageComesFromBodyRatherThanTitle() = assertTrue(isEnglishReadingArticle(article(prose, title = "中文标题")))
    @Test fun chineseTechnicalArticleKeepsNormalReading() = assertFalse(isEnglishReadingArticle(article(
        "这是一篇中文技术教程，讲解如何使用组件构建界面，首先应该理解状态与生命周期。" +
            "Kotlin Compose Android State View Model Flow Coroutine")))
    @Test fun linkOnlyEntryDoesNotEnableLookupOnSummary() = assertFalse(isEnglishReadingArticle(article(prose, "LINK")))
    @Test fun codeAndUrlsDoNotMasqueradeAsEnglishProse() = assertFalse(isEnglishReadingArticle(article(
        "```kotlin\nval first = second when another return true else false\n```\nhttps://example.com/another/long/path")))
    @Test fun blankBodyKeepsNormalReading() = assertFalse(isEnglishReadingArticle(article("")))
}
