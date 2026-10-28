package cn.tonglu.app.ui.learning

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningDestinationContrastTest {
    @Test fun subjectCardsRetainReadableInkInBothThemes() {
        val themes = listOf(Color.White to Color(0xFF17181C), Color(0xFF1D2026) to Color(0xFFE8EBEF))
        for ((surface, foreground) in themes) {
            for (title in listOf("英语", "数学", "书库", "竞赛", "其他")) {
                val background = learningDestinationBackground(learningDestinationTint(title), surface)
                val a = background.luminance(); val b = foreground.luminance()
                val contrast = (maxOf(a,b)+0.05f)/(minOf(a,b)+0.05f)
                assertTrue("$title contrast=$contrast", contrast >= 4.5f)
            }
        }
    }
}
