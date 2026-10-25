package cn.tonglu.app.ui.english.examentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【开发夹具】导航栈状态机单测：层级进退、筛选/滚动键保留与重置、
 * 进程重建 save/restore 往返。数据均为开发夹具，不是真实题源。
 */
class ExamEntryNavStateTest {

    // ---------- 层级推进与逐级返回 ----------

    @Test fun startsAtHomeAndCannotPop() {
        val nav = ExamEntryNavState()
        assertEquals(ExamEntryNavState.Level.HOME, nav.level)
        assertFalse(nav.canPop)
        assertFalse(nav.back())
        assertEquals(ExamEntryNavState.Level.HOME, nav.level)
    }

    @Test fun fullPathPopsBackLevelByLevel() {
        val nav = ExamEntryNavState()
        nav.openExamSelect()
        assertEquals(ExamEntryNavState.Level.EXAM_SELECT, nav.level)
        nav.openExam("cet4", "大学英语四级")
        assertEquals(ExamEntryNavState.Level.EXAM_ARTICLES, nav.level)
        assertEquals("cet4", nav.selectedExamKey)

        assertTrue(nav.back())
        assertEquals(ExamEntryNavState.Level.EXAM_SELECT, nav.level)
        // 回到考试选择再进同一考试：仍带着上下文
        nav.openExam("cet4", "大学英语四级")
        assertEquals(ExamEntryNavState.Level.EXAM_ARTICLES, nav.level)
        assertTrue(nav.back())
        assertTrue(nav.back())
        assertEquals(ExamEntryNavState.Level.HOME, nav.level)
        assertFalse(nav.canPop)
    }

    @Test fun recommendedPopsStraightHome() {
        val nav = ExamEntryNavState()
        nav.openRecommended()
        assertEquals(ExamEntryNavState.Level.RECOMMENDED, nav.level)
        assertTrue(nav.back())
        assertEquals(ExamEntryNavState.Level.HOME, nav.level)
    }

    // ---------- 筛选保留：同一列表保留，换列表重置 ----------

    @Test fun filterRetainedWhenReenteringSameExam() {
        val nav = ExamEntryNavState()
        nav.openExam("cet4", "四级")
        assertTrue(nav.updateFilter(ExamListFilter(year = 2024)))
        nav.back()
        nav.openExam("cet4", "四级")
        assertEquals(2024, nav.filter.year)
    }

    @Test fun filterResetWhenSwitchingExam() {
        val nav = ExamEntryNavState()
        nav.openExam("cet4", "四级")
        nav.updateFilter(ExamListFilter(year = 2024))
        nav.openExam("cet6", "六级")
        assertTrue(nav.filter.isDefault)
    }

    @Test fun updateFilterIsNoopWhenUnchanged() {
        val nav = ExamEntryNavState()
        nav.openExam("cet4", "四级")
        assertFalse(nav.updateFilter(ExamListFilter()))
        assertEquals(0, nav.filterVersion)
        assertTrue(nav.updateFilter(ExamListFilter(year = 2024)))
        assertEquals(1, nav.filterVersion)
    }

    // ---------- 滚动键：同列表同键（保留），换列表/改筛选换键 ----------

    @Test fun scrollKeyStableForSameListAndChangesForOtherList() {
        val nav = ExamEntryNavState()
        nav.openExam("cet4", "四级")
        val k1 = nav.listScrollKey
        nav.updateFilter(ExamListFilter(year = 2024))
        val k2 = nav.listScrollKey
        // 改筛选不改列表身份（组件层用 filterVersion 拼出新的保存 key → 从头开始）
        assertEquals(k1, k2)
        assertEquals(1, nav.filterVersion)

        nav.openExam("cet6", "六级")
        val k3 = nav.listScrollKey
        assertTrue(k3!!.startsWith("exam:cet6"))

        nav.openRecommended()
        assertEquals("recommended", nav.listScrollKey)
    }

    @Test fun recommendedFilterResetOnlyOnFreshEntry() {
        val nav = ExamEntryNavState()
        nav.openRecommended()
        nav.updateFilter(ExamListFilter(readingType = "仔细阅读"))
        // 返回再进推荐 = 同一列表：筛选保留
        nav.back()
        nav.openRecommended()
        assertEquals("仔细阅读", nav.filter.readingType)
        // 而后切去考试：重置
        nav.openExam("cet4", "四级")
        assertTrue(nav.filter.isDefault)
    }

    // ---------- save / restore 往返 ----------

    @Test fun saveRestoreRoundtripKeepsLevelExamFilterScroll() {
        val nav = ExamEntryNavState()
        nav.openExam("cet4", "大学英语四级")
        nav.updateFilter(ExamListFilter(year = 2024, session = "6月"))
        val saved = nav.save()

        val restored = ExamEntryNavState()
        assertTrue(restored.restore(saved))
        assertEquals(ExamEntryNavState.Level.EXAM_ARTICLES, restored.level)
        assertEquals("cet4", restored.selectedExamKey)
        assertEquals("大学英语四级", restored.selectedExamTitle)
        assertEquals(2024, restored.filter.year)
        assertEquals("6月", restored.filter.session)
        assertEquals(nav.listScrollKey, restored.listScrollKey)
        assertEquals(nav.filterVersion, restored.filterVersion)
    }

    @Test fun restoreRejectsGarbageAndKeepsDefaults() {
        val nav = ExamEntryNavState()
        assertFalse(nav.restore("garbage"))
        assertFalse(nav.restore("a|b|c"))
        assertEquals(ExamEntryNavState.Level.HOME, nav.level)
        assertNull(nav.selectedExamKey)
        assertTrue(nav.filter.isDefault)
    }

    @Test fun restoreUnknownLevelFallsBackToHome() {
        val restored = ExamEntryNavState()
        assertFalse(restored.restore("NOT_A_LEVEL||||||0"))
        assertEquals(ExamEntryNavState.Level.HOME, restored.level)
    }

    // ---------- 默认值 ----------

    @Test fun freshStateHasNoListKey() {
        assertNull(ExamEntryNavState().listScrollKey)
    }
}
