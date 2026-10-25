package cn.tonglu.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编译不会发现重复盖层和下层被卸载，明确检查这两条结构契约。 */
class LearningLayerStructureTest {
    private fun source(name: String): String {
        val roots = listOf("src/main/java", "app/src/main/java", "../app/src/main/java")
        val file = roots.map { File(it, "cn/tonglu/app/ui/$name") }.firstOrNull { it.isFile }
            ?: error("找不到界面源码：$name")
        return file.readText()
    }

    @Test
    fun `互传自带窗口不能再包不透明宿主`() {
        val source = source("TongluApp.kt")
        val block = source.substringAfter("    if (showFileTransfer) {").substringBefore("    if (showNetdisk)")
        assertTrue(block.contains("LocalSendScreen("))
        assertFalse(block.contains("OverlayLayerHost("))
    }

    @Test
    fun `竞赛原公告画在竞赛层之上`() {
        val source = source("TongluApp.kt")
        assertTrue(source.indexOf("    if (browserTarget != null)") > source.indexOf("        if (showCompetitions)"))
    }

    @Test
    fun `题库练习与自测不卸载目录窗口`() {
        val source = source("learning/QuestionBankScreen.kt")
        val dialog = source.indexOf("    Dialog(")
        assertTrue(dialog >= 0)
        assertTrue(source.indexOf("    if (openBank != null)") > dialog)
        assertTrue(source.indexOf("    if (diagnosisOpen)") > dialog)
    }
}
