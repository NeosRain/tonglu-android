package cn.tonglu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 等级规则的判据测试。
 *
 * 🔴 这些不是"锦上添花的覆盖率"，每一条都对应一个**已经想清楚的坑**：
 *等级地板（日上限/删帖后不降级）、距下一级永不为负、客户端与服务端阈值一致。
 * 目的是**故意把代码改回bug 的样子时，测试会变红**（红线：「修法写进代码」≠「被固定住」）。
 */
class LevelsTest {
    @Test fun 零经验是Lv1() {
        assertEquals(1, Levels.levelFromExp(0))
        assertEquals("新生", Levels.name(1))
    }

    /** 边界值：恰好等于阈值就该升一级（>= 而不是 >）。 */
    @Test fun 恰好等于阈值即升级() {
        assertEquals(1, Levels.levelFromExp(49))
        assertEquals(2, Levels.levelFromExp(50))
        assertEquals(2, Levels.levelFromExp(149))
        assertEquals(3, Levels.levelFromExp(150))
        assertEquals(8, Levels.levelFromExp(6000))
    }

    /** 超量不报错，也不越界：EXP 超过 Lv8 阈值仍是 Lv8。 */
    @Test fun 超量不会越界() {
        assertEquals(8, Levels.levelFromExp(999_999))
        assertEquals("同路人", Levels.name(8))
        assertEquals(0, Levels.expToNext(999_999))
    }

    /**
     * 🔴 「距下一级」永不为负 —— plan/66 §2.2 明确要求。
     * 这里覆盖那个倒挂场景：用户可能同时看到「Lv6」和「已超额」。
     */
    @Test fun 距下一级永不为负且不越界() {
        assertEquals(50, Levels.expToNext(0))
        assertEquals(0, Levels.expToNext(6000))
        assertTrue(Levels.expToNext(999_999) >= 0)
    }

    /** 进度条边界。 */
    @Test fun 进度在零到一之间() {
        assertEquals(0f, Levels.progress(0), 0.001f)
        assertEquals(0.5f, Levels.progress(25), 0.001f)
        assertEquals(1f, Levels.progress(6000), 0.001f)
        assertEquals(1f, Levels.progress(999_999), 0.001f)
    }

    /**
     * 🔴 客户端与服务端必须共用同一张阈值表 ——
     * 不一致的症状是「App 显示 Lv3、网页显示 Lv2」，极难排查。
     * 这里的数字与 `backend/src/levels/levels.rules.ts` 的 LEVEL_THRESHOLDS 一一对应。
     */
    @Test fun 阈值表与服务端一致() {
        val expected = listOf(0, 50, 150, 400, 900, 1800, 3500, 6000)
        assertEquals(expected, Levels.THRESHOLDS.toList())
    }

    /** TL 名只用于本人视角；越界输入要夹住而不是崩。 */
    @Test fun 等级名越界被夹住() {
        assertEquals("新生", Levels.name(0))
        assertEquals("同路人", Levels.name(99))
        assertEquals("观察中", Levels.trustName(-1))
        assertEquals("主理", Levels.trustName(3))
    }

    /**
     * 🔴 红线 1：等级**不换权限**。这里把「Lv 越高 accentIndex 越大」钉住，
     * 是为了让「颜色随等级变」这件事保持纯装饰 —— 权限判定绝不能出现在这一层。
     */
    @Test fun 徽标配色随等级递增且单调() {
        val indices = (1..8).map { Levels.accentIndex(it) }
        assertEquals(indices.sorted(), indices)
        assertTrue(indices.first() >= 1 && indices.last() <= 4)
    }

    /**
     * 学习线「首次学会」判据（plan/66 §2.3 words.learned）。
     *
     * 🔴 为什么要专门测：这条判据错了不会崩，只会**静默把数字算成几十倍**——
     * 用户复习同一个词 50 遍就上报 50 个「新学会的词」。而且日上限也拦不住，
     * 因为上限管的是「一天最多给多少 EXP」，不是「这个词是不是新的」。
     */
    @Test fun 只有第一次答对才算新学会() {
        assertTrue(Levels.isFirstLearned(correct = true, prevLevel = 0, prevAttempts = 0))
        // 答错了：不算（无论见过几次）
        assertTrue(!Levels.isFirstLearned(correct = false, prevLevel = 0, prevAttempts = 0))
        // 已经记住了（level > 0）：复习答对不算新词 —— 这是最关键的一条
        assertTrue(!Levels.isFirstLearned(correct = true, prevLevel = 1, prevAttempts = 1))
        assertTrue(!Levels.isFirstLearned(correct = true, prevLevel = 3, prevAttempts = 9))
        // 见过但还没记住（答错过，level 归 0）：也不算「新学会」，它属于复习
        assertTrue(!Levels.isFirstLearned(correct = true, prevLevel = 0, prevAttempts = 2))
    }

    /**
     * 对照组：把判据写成「只看 correct」这种错误实现，上面的断言必须能抓住它。
     * 若这个测试变红，说明判据真的被固定住了（红线：改回 bug 的样子要变红）。
     */
    @Test fun 对照组_只看correct的错实现确实会误判() {
        val buggy = { correct: Boolean, _: Int, _: Int -> correct }
        // 复习答对：正确实现给 false，错误实现给 true —— 两者必须不同。
        assertTrue(!Levels.isFirstLearned(true, prevLevel = 2, prevAttempts = 5))
        assertTrue(buggy(true, 2, 5))
    }
}