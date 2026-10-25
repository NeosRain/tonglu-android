package cn.tonglu.app.ui

import org.junit.Assert.*
import org.junit.Test

class EnglishCardActionsTest {
    @Test fun `未展开释义也允许评分 保存中禁止重复点击`() {
        assertTrue(canGradeCard(false))
        assertFalse(canGradeCard(true))
    }
    @Test fun `分词保留原句 标点不朗读 缩写不拆断`() {
        val line = "Hello, don't re-enter! 中文。"
        val tokens = exampleTokens(line)
        assertEquals(line, tokens.joinToString("") { it.text })
        assertEquals(listOf("Hello", "don't", "re-enter"), tokens.filter { it.spoken }.map { it.text })
    }
    @Test fun `空例句与纯中文不生成单词`() {
        assertTrue(exampleTokens("").isEmpty())
        assertTrue(exampleTokens("中文。").none { it.spoken })
    }
}
