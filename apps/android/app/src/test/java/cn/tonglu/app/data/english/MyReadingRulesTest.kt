package cn.tonglu.app.data.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我的精读文章」纯规则单测。
 *
 * 🔴 来源与版权**必填**是用户点名的要求（「一定要备注文章来源和版权信息，别忘了」），
 * 这组用例钉的就是这条 —— 谁把它改成可选，先过这组测试再说。
 */
class MyReadingRulesTest {

    // 🔴 夹具用**四个具名默认值**，不用返回 Tuple 的 helper —— 第一版返回
    // Triple(…, source to license) 再解构，第三位实际是 Pair，调用处全炸
    // （编译期就拦下了，但纯属自己给自己挖坑：四个参数直接给默认值最直白）。
    private val okTitle = "2024年6月四级真题 卷二 阅读B"
    private val okBody = "Passage One ..."
    private val okSource = "2024年6月四级真题 卷二"
    private val okLicense = "个人学习使用，来源：纸质试卷"

    @Test fun validInputPasses() {
        assertNull(MyReadingRules.validate(okTitle, okBody, okSource, okLicense))
        assertTrue(MyReadingRules.canSave(okTitle, okBody, okSource, okLicense))
    }

    @Test fun blankTitleIsRejected() {
        assertEquals("标题不能为空", MyReadingRules.validate("  ", okBody, okSource, "x"))
        assertFalse(MyReadingRules.canSave("  ", okBody, okSource, "x"))
    }

    @Test fun blankBodyIsRejected() {
        assertEquals("正文不能为空", MyReadingRules.validate(okTitle, "", okSource, "x"))
    }

    @Test fun blankSourceIsRejected() {
        // 🔴 用户点名的两项之一：来源空着必须被拦下
        assertEquals("来源不能为空（如：2024年6月四级真题 卷二 阅读B）",
            MyReadingRules.validate(okTitle, okBody, "", "x"))
    }

    @Test fun blankLicenseIsRejected() {
        // 🔴 用户点名的两项之一：版权空着必须被拦下
        assertEquals("版权信息不能为空（如：个人学习使用，来源：纸质试卷）",
            MyReadingRules.validate(okTitle, okBody, okSource, ""))
    }

    @Test fun whitespaceOnlyCountsAsBlank() {
        assertTrue(MyReadingRules.validate(okTitle, okBody, "  \n ", "x")
            ?.startsWith("来源不能为空") == true)
    }

    @Test fun errorOrderFollowsFormFieldOrder() {
        // 全空时报第一条（标题）：从上往下改最顺手
        assertEquals("标题不能为空", MyReadingRules.validate("", "", "", ""))
    }

    // ---- 标签归一 ----

    @Test fun tagsAcceptMultipleSeparators() {
        assertEquals("2024-06|四级|卷二", MyReadingRules.normalizeTags("2024-06，四级 卷二"))
    }

    @Test fun tagsTrimAndDeduplicate() {
        assertEquals("四级|卷二", MyReadingRules.normalizeTags(" 四级 ，卷二、四级 "))
    }

    @Test fun tagsKeepSpacesInsideATag() {
        // 🔴 空格也是分隔符：「卷 二」会被拆成两片 —— 刻意行为（分隔符统一），
        // 此处钉下来防误解。要含空格的标签得靠竖线写。
        assertEquals("卷|二", MyReadingRules.normalizeTags("卷 二"))
    }

    @Test fun blankTagsNormalizeToEmpty() {
        assertEquals("", MyReadingRules.normalizeTags("   "))
        assertEquals("", MyReadingRules.normalizeTags(""))
    }

    @Test fun pipeInputPassesThrough() {
        assertEquals("a|b", MyReadingRules.normalizeTags("a|b"))
    }
}
