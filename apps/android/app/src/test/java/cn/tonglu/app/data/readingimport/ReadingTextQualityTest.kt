package cn.tonglu.app.data.readingimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 文字层质量判据的 JVM 单测。
 *
 * 背景：用户提供的 2025 年 6 月四级试题册「页面视觉清楚、直接取文是乱码」。
 * 这些用例钉住「乱码不导入、正常英文/中文可导入」两类结果，避免回归成
 * 「取到字符串就当正文」（那样用户会得到一篇不可读的文章）。
 */
class ReadingTextQualityTest {

    @Test
    fun normalEnglishPageIsUsable() {
        val text = "In this section, you will hear three news reports. At the end of each news report, " +
            "you will hear two or three questions. Both the news report and the questions will be " +
            "spoken only once, so you should listen carefully."
        assertFalse(ReadingTextQuality.looksGarbled(text))
        assertTrue(ReadingTextQuality.isUsablePageText(text))
    }

    @Test
    fun privateUseAndControlCharactersAreGarbled() {
        val garbled = "\uE000\uE001\u0001\uE002 \uE003\uE004\u0002 \uE005\uE006\u0007".repeat(8)
        assertTrue(ReadingTextQuality.looksGarbled(garbled))
        assertFalse(ReadingTextQuality.isUsablePageText(garbled))
    }

    @Test
    fun bulkNonLatinLettersWithoutLatinIsGarbled() {
        val text = "Δοκιμή κείμενο χωρίς λατινικά γράμματα. ".repeat(3)
        assertTrue(ReadingTextQuality.looksGarbled(text))
    }

    @Test
    fun longLatinWithoutWordSpacesIsGarbled() {
        assertTrue(ReadingTextQuality.looksGarbled("abcdefghij".repeat(12)))
    }

    @Test
    fun shortPageIsNotUsableButNotNecessarilyGarbled() {
        assertFalse(ReadingTextQuality.isUsablePageText("Unit 1"))
        assertFalse(ReadingTextQuality.looksGarbled("Unit 1"))
    }

    @Test
    fun chinesePageWithPunctuationIsUsable() {
        val text = "2025年6月大学英语四级考试试题册。本页为阅读理解部分，请认真作答；" +
            "文章内容与题目均在下方，注意时间分配与答题卡填涂。"
        assertFalse(ReadingTextQuality.looksGarbled(text))
        assertTrue(ReadingTextQuality.isUsablePageText(text))
    }

    @Test
    fun emptyOrBlankTextIsNotUsable() {
        assertTrue(ReadingTextQuality.looksGarbled("   "))
        assertFalse(ReadingTextQuality.isUsablePageText(""))
        assertFalse(ReadingTextQuality.isUsablePageText("   "))
    }

    @Test
    fun replacementCharacterIsGarbled() {
        assertTrue(ReadingTextQuality.looksGarbled("Good morning \uFFFD\uFFFD\uFFFD everyone, welcome to the test."))
    }

    @Test
    fun ocrTidyJoinsWrappedLinesAndKeepsParagraphs() {
        val raw = "The first line of a sentence\ncontinues on the second line .\n\nNext paragraph starts here .\n"
        val tidied = ReadingTextQuality.tidyOcrText(raw)
        assertEquals("The first line of a sentence continues on the second line.\n\nNext paragraph starts here.", tidied)
    }
}