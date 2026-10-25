package cn.tonglu.app.ui.clubs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 创建社团表单的规则单测（UI-REPAIR-2026-10-13 §3 验收矩阵的判定层）。
 *
 * 判据落在**函数返回值**上，每条关键文案都精确断言（不是 contains 糊弄）；
 * 边界值取服务端 `CreateClubDto` 口径：名称 2–50、分类 2–30、简介 5–1000。
 * 任务书点名的一 case：简介 `test`（4 字）必须解释「还需 1 字」，不是按钮灰着让人猜。
 */
class ClubCreateRulesTest {

    // ---------------------------------------------------------------- 名称 2–50

    @Test
    fun `名称为空提示请输入`() {
        assertEquals("请输入社团名称（2-50 字）", clubNameError(""))
        assertEquals("请输入社团名称（2-50 字）", clubNameError("   "))
    }

    @Test
    fun `名称1字差1字`() {
        assertEquals("社团名至少 2 字，还需 1 字", clubNameError("社"))
    }

    @Test
    fun `名称2字与50字合法`() {
        assertNull(clubNameError("社团"))
        assertNull(clubNameError("字".repeat(50)))
    }

    @Test
    fun `名称首尾空白按trim后计`() {
        // 输入框不拦空格，判定口径与服务端一致：trim 后 2 字即合法
        assertNull(clubNameError(" 社团 "))
        assertEquals("社团名至少 2 字，还需 1 字", clubNameError(" 社 "))
    }

    // ---------------------------------------------------------------- 分类 2–30

    @Test
    fun `分类为空提示请选择`() {
        assertEquals("请选择或输入分类（2-30 字）", clubCategoryError(""))
    }

    @Test
    fun `分类1字差1字`() {
        assertEquals("分类至少 2 字，还需 1 字", clubCategoryError("球"))
    }

    @Test
    fun `分类2字与30字合法`() {
        assertNull(clubCategoryError("运动"))
        assertNull(clubCategoryError("类".repeat(30)))
    }

    // ---------------------------------------------------------------- 简介 5–1000（任务书点名 test4）

    @Test
    fun `test4字必须解释还需1字`() {
        // 🔴 精确断言，不是 contains：这句话就是验收矩阵的判据
        assertEquals("社团介绍至少 5 字，还需 1 字", clubDescriptionError("test"))
    }

    @Test
    fun `简介为空提示至少5字`() {
        assertEquals("社团介绍至少 5 字", clubDescriptionError(""))
    }

    @Test
    fun `简介3字差2字`() {
        assertEquals("社团介绍至少 5 字，还需 2 字", clubDescriptionError("abc"))
    }

    @Test
    fun `简介5字与1000字合法`() {
        assertNull(clubDescriptionError("12345"))
        assertNull(clubDescriptionError("字".repeat(1000)))
    }

    // ---------------------------------------------------------------- 组合：ready / firstHint

    @Test
    fun `三条全过才ready`() {
        val okName = "读书会"
        val okCat = "文学阅读"
        val okDesc = "每周共读一本书并交流心得。"
        assertTrue(clubCreateReady(okName, okCat, okDesc))

        // 对照组：每缺一项 ready 都必须翻红（判据真的在判，不是恒真）
        assertFalse(clubCreateReady("", okCat, okDesc))
        assertFalse(clubCreateReady(okName, "球", okDesc))
        assertFalse(clubCreateReady(okName, okCat, "test"))
    }

    @Test
    fun `firstHint取第一条不满足的`() {
        // 名称与简介都不足 → 只报名称（不铺一屏红字）
        assertEquals("社团名至少 2 字，还需 1 字", clubCreateFirstHint("社", "球", "test"))
        // 名称过了 → 报分类
        assertEquals("分类至少 2 字，还需 1 字", clubCreateFirstHint("读书会", "球", "test"))
        // 全过 → null
        assertNull(clubCreateFirstHint("读书会", "文学阅读", "每周共读一本书并交流心得。"))
    }

    // ---------------------------------------------------------------- 预设与文案工具

    @Test
    fun `分类预设与PC端同一份清单`() {
        // 与 PC 端 clubs.vue 的 CATEGORY_PRESETS 对齐；改一边必须改两边
        assertEquals(
            listOf("文学阅读", "体育运动", "科技编程", "公益志愿", "游戏动漫", "学习互助"),
            CLUB_CATEGORY_PRESETS,
        )
    }

    @Test
    fun `belowMinimumHint单数字特判`() {
        assertEquals("社团介绍至少 5 字，还需 1 字", belowMinimumHint("社团介绍", 4, 5))
        assertEquals("社团介绍至少 5 字，还需 3 字", belowMinimumHint("社团介绍", 2, 5))
    }
}
